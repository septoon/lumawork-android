package ru.lumastack.lumawork.data.fuel

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
    suspend fun read(ownerUserId: String): FuelSnapshot? = cache.read(ownerUserId)

    suspend fun refresh(session: AppSession): FuelSnapshot = FuelSnapshot(
        records = api.records(session.token).sortedByDescending(FuelRecord::date),
        updatedAtEpochMillis = nowEpochMillis(),
    ).also { cache.save(session.user.id, it) }

    suspend fun save(session: AppSession, editingId: String?, draft: FuelDraft): FuelSnapshot {
        val saved = if (editingId == null) api.create(session.token, draft)
        else api.update(session.token, editingId, draft)
        val previous = read(session.user.id) ?: FuelSnapshot()
        val records = if (editingId == null) previous.records + saved
        else previous.records.map { if (it.id == editingId) saved else it }
        return previous.copy(
            records = records.sortedByDescending(FuelRecord::date),
            updatedAtEpochMillis = nowEpochMillis(),
        ).also { cache.save(session.user.id, it) }
    }

    suspend fun delete(session: AppSession, record: FuelRecord): FuelSnapshot {
        val id = record.id ?: return read(session.user.id) ?: FuelSnapshot()
        api.delete(session.token, id)
        val previous = read(session.user.id) ?: FuelSnapshot()
        return previous.copy(
            records = previous.records.filterNot { it.id == id },
            updatedAtEpochMillis = nowEpochMillis(),
        ).also { cache.save(session.user.id, it) }
    }

    suspend fun syncMonthlyMileage(
        session: AppSession,
        monthKey: String,
        latestDate: String,
        totalKm: Int,
        allowRemotePersistence: Boolean,
    ): FuelSnapshot {
        val previous = read(session.user.id) ?: FuelSnapshot()
        val previousTarget = previous.monthlyMileageRecord(monthKey)
        val local = previous.applyMonthlyMileage(monthKey, latestDate, totalKm)
        cache.save(session.user.id, local)
        if (!allowRemotePersistence) return local
        if (totalKm <= 0 && previousTarget?.id == null) return local
        val comment = "$ROUTE_MONTHLY_MILEAGE_PREFIX|$latestDate|$totalKm"
        if (previousTarget?.comment == comment && previousTarget.id != null) return local
        val draft = FuelDraft(
            recordType = FuelRecordType.Fuel,
            date = latestDate,
            mileage = totalKm.toString(),
            comment = comment,
        )
        val saved = previousTarget?.id?.let { api.update(session.token, it, draft) }
            ?: api.create(session.token, draft)
        return local.replaceMonthlyMileage(monthKey, saved).copy(updatedAtEpochMillis = nowEpochMillis())
            .also { cache.save(session.user.id, it) }
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
