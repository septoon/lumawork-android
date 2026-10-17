package ru.lumastack.lumawork.data.fuel

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.lumastack.lumawork.domain.fuel.FuelDraft
import ru.lumastack.lumawork.domain.fuel.FuelRecord
import ru.lumastack.lumawork.domain.fuel.FuelRecordType
import ru.lumastack.lumawork.domain.fuel.FuelSnapshot
import ru.lumastack.lumawork.domain.model.AppSession

class FuelRepository(
    private val api: FuelApi,
    private val cache: FuelCache,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) {
    private val cacheMutex = Mutex()
    private val mileageMutex = Mutex()
    private val revisions = mutableMapOf<String, Long>()
    private val mutableChanges = MutableSharedFlow<Pair<String, FuelSnapshot>>(replay = 1, extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val changes = mutableChanges.asSharedFlow()

    suspend fun fuelTypes(session: AppSession): List<String> {
        val types = api.fuelTypes(session.token).map(String::trim).filter(String::isNotBlank).distinct()
        update(session.user.id) { it.copy(fuelTypes = types) }
        return types
    }

    suspend fun read(ownerUserId: String): FuelSnapshot? = cache.read(ownerUserId)

    suspend fun refresh(session: AppSession): FuelSnapshot {
        val owner = session.user.id
        val revision = cacheMutex.withLock { revisions[owner] ?: 0L }
        val records = api.records(session.token)
        return update(owner) { previous ->
            if ((revisions[owner] ?: 0L) != revision) previous
            else {
                var merged = previous.copy(records = records, updatedAtEpochMillis = nowEpochMillis())
                previous.pendingMileageMonths.forEach { month ->
                    val pending = previous.monthlyMileageRecord(month) ?: return@forEach
                    val serverId = merged.monthlyMileageRecord(month)?.id ?: pending.id
                    merged = merged.replaceMonthlyMileage(month, pending.copy(id = serverId))
                }
                merged
            }
        }
    }

    suspend fun save(session: AppSession, editingId: String?, draft: FuelDraft): FuelSnapshot {
        beginMutation(session.user.id)
        val saved = if (editingId == null) api.create(session.token, draft) else api.update(session.token, editingId, draft)
        return update(session.user.id, mutation = true) { previous ->
            val index = previous.records.indexOfFirst { it.id != null && (it.id == editingId || it.id == saved.id) }
            val records = previous.records.toMutableList()
            if (index >= 0) records[index] = saved else records.add(0, saved)
            previous.copy(records = records.sortedByDescending(FuelRecord::date), updatedAtEpochMillis = nowEpochMillis())
        }
    }

    suspend fun delete(session: AppSession, record: FuelRecord): FuelSnapshot {
        val id = record.id?.takeIf(String::isNotBlank) ?: throw FuelFailure.Message("Нельзя удалить запись без id.")
        beginMutation(session.user.id)
        api.delete(session.token, id)
        return update(session.user.id, mutation = true) { previous -> previous.copy(
            records = previous.records.filterNot { it.id == id }, updatedAtEpochMillis = nowEpochMillis()) }
    }

    suspend fun syncMonthlyMileage(session: AppSession, monthKey: String, latestDate: String, totalKm: Int,
        allowRemotePersistence: Boolean): FuelSnapshot = mileageMutex.withLock {
        val comment = "$ROUTE_MONTHLY_MILEAGE_PREFIX|$latestDate|$totalKm"
        var confirmed = false
        val local = update(session.user.id, mutation = true) { previous ->
            val target = previous.monthlyMileageRecord(monthKey)
            confirmed = target?.id != null && target.comment == comment && monthKey !in previous.pendingMileageMonths
            previous.applyMonthlyMileage(monthKey, latestDate, totalKm).copy(
                pendingMileageMonths = if (confirmed) previous.pendingMileageMonths else previous.pendingMileageMonths + monthKey)
        }
        if (!allowRemotePersistence || confirmed) return@withLock local
        val target = local.monthlyMileageRecord(monthKey)
        if (totalKm <= 0 && target?.id == null) return@withLock local
        val draft = FuelDraft(recordType = FuelRecordType.Fuel, date = latestDate, mileage = totalKm.toString(), comment = comment)
        val saved = target?.id?.let { api.update(session.token, it, draft) } ?: api.create(session.token, draft)
        update(session.user.id, mutation = true) { previous -> previous.replaceMonthlyMileage(monthKey, saved).copy(
            pendingMileageMonths = previous.pendingMileageMonths - monthKey, updatedAtEpochMillis = nowEpochMillis()) }
    }

    suspend fun retryPendingMileage(session: AppSession): FuelSnapshot {
        val previous = read(session.user.id) ?: return FuelSnapshot()
        previous.pendingMileageMonths.sorted().forEach { month ->
            val record = previous.monthlyMileageRecord(month) ?: return@forEach
            try { syncMonthlyMileage(session, month, record.date, (record.mileage ?: 0.0).toInt(), true) }
            catch (error: kotlinx.coroutines.CancellationException) { throw error }
            catch (_: Exception) { /* Remains pending for the next successful refresh. */ }
        }
        return read(session.user.id) ?: previous
    }

    private suspend fun beginMutation(owner: String) = cacheMutex.withLock {
        currentCoroutineContext().ensureActive()
        revisions[owner] = (revisions[owner] ?: 0L) + 1
    }
    private suspend fun update(owner: String, mutation: Boolean = false, transform: (FuelSnapshot) -> FuelSnapshot): FuelSnapshot = cacheMutex.withLock {
        currentCoroutineContext().ensureActive()
        val previous = read(owner) ?: FuelSnapshot()
        if (mutation) revisions[owner] = (revisions[owner] ?: 0L) + 1
        transform(previous).also {
            if (it != previous) cache.save(owner, it)
            currentCoroutineContext().ensureActive()
            mutableChanges.tryEmit(owner to it)
        }
    }

    private fun FuelSnapshot.applyMonthlyMileage(monthKey: String, latestDate: String, totalKm: Int): FuelSnapshot {
        val target = monthlyMileageRecord(monthKey)
        val local = FuelRecord(
            id = target?.id,
            date = latestDate,
            mileage = totalKm.toDouble(),
            comment = "$ROUTE_MONTHLY_MILEAGE_PREFIX|$latestDate|$totalKm",
        )
        return replaceMonthlyMileage(monthKey, local)
    }

    private fun FuelSnapshot.replaceMonthlyMileage(monthKey: String, record: FuelRecord): FuelSnapshot {
        val index = monthlyMileageRecordIndex(monthKey)
        val updated = records.toMutableList()
        if (index != null) updated[index] = record else if ((record.mileage ?: 0.0) > 0) updated += record
        return copy(records = updated.sortedByDescending(FuelRecord::date))
    }

    private fun FuelSnapshot.monthlyMileageRecord(monthKey: String): FuelRecord? =
        monthlyMileageRecordIndex(monthKey)?.let(records::get)

    private fun FuelSnapshot.monthlyMileageRecordIndex(monthKey: String): Int? {
        val indices = records.indices.filter {
            val record = records[it]
            record.recordType == FuelRecordType.Fuel && record.date.take(7) == monthKey
        }
        return indices.firstOrNull { records[it].comment?.startsWith(ROUTE_MONTHLY_MILEAGE_PREFIX) == true }
            ?: indices.filter {
                val record = records[it]
                record.mileage != null && record.liters == null && record.fuelCost == null
            }.maxByOrNull { records[it].date }
    }

    companion object { const val ROUTE_MONTHLY_MILEAGE_PREFIX = "route_monthly_mileage" }
}
