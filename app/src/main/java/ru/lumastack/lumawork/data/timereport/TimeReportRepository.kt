package ru.lumastack.lumawork.data.timereport

import java.time.LocalDate
import java.time.LocalDateTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.lumastack.lumawork.data.simpleone.SimpleOneApi
import ru.lumastack.lumawork.data.simpleone.SimpleOneFailure
import ru.lumastack.lumawork.data.simpleone.SimpleOneSessionStore
import ru.lumastack.lumawork.domain.timereport.TimeReportEntry
import ru.lumastack.lumawork.domain.timereport.TimeReportSnapshot
import ru.lumastack.lumawork.domain.timereport.TimeReportSource

class TimeReportRepository(
    private val api: SimpleOneApi,
    private val sessionStore: SimpleOneSessionStore,
    private val cache: TimeReportCache,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) {
    suspend fun read(ownerUserId: String, simpleOneUserId: String): TimeReportSnapshot? =
        cache.read(ownerUserId)?.takeIf { it.simpleOneUserId == simpleOneUserId }

    suspend fun refresh(ownerUserId: String, simpleOneUserId: String): TimeReportSnapshot {
        val authKey = sessionStore.read(ownerUserId)?.authKey ?: throw SimpleOneFailure.MissingCredentials
        return try {
            val (incoming, source) = try {
                val bytes = api.exportTimeReport(simpleOneUserId, authKey)
                withContext(Dispatchers.Default) { TimeReportParser.parseXlsx(bytes) } to TimeReportSource.Xlsx
            } catch (error: CancellationException) {
                throw error
            } catch (error: SimpleOneFailure.Unauthorized) {
                throw error
            } catch (_: Exception) {
                api.timeReportEntries(authKey) to TimeReportSource.ListApi
            }
            if (incoming.isEmpty()) throw SimpleOneFailure.InvalidResponse
            val existing = read(ownerUserId, simpleOneUserId)?.entries.orEmpty()
            TimeReportSnapshot(
                simpleOneUserId = simpleOneUserId,
                entries = merge(existing, incoming),
                updatedAtEpochMillis = nowEpochMillis(),
                source = source,
            ).also { cache.save(ownerUserId, it) }
        } catch (error: SimpleOneFailure.Unauthorized) {
            withContext(Dispatchers.IO) { sessionStore.clear(ownerUserId) }
            throw error
        }
    }

    suspend fun deleteAll(ownerUserId: String, simpleOneUserId: String): Int {
        val count = read(ownerUserId, simpleOneUserId)?.entries?.size ?: 0
        cache.delete(ownerUserId)
        return count
    }

    suspend fun deleteRange(
        ownerUserId: String,
        simpleOneUserId: String,
        startDate: LocalDate,
        endDate: LocalDate,
    ): Int {
        val snapshot = read(ownerUserId, simpleOneUserId) ?: return 0
        val lower = minOf(startDate, endDate)
        val upper = maxOf(startDate, endDate)
        val remaining = snapshot.entries.filter { entry ->
            entry.createdAtDateTime()?.toLocalDate()?.let { it < lower || it > upper } ?: true
        }
        val deleted = snapshot.entries.size - remaining.size
        if (deleted == 0) return 0
        if (remaining.isEmpty()) cache.delete(ownerUserId)
        else cache.save(ownerUserId, snapshot.copy(entries = remaining))
        return deleted
    }

    private fun merge(
        existing: List<TimeReportEntry>,
        incoming: List<TimeReportEntry>,
    ): List<TimeReportEntry> = (existing + incoming)
        .associateBy(TimeReportEntry::stableId)
        .values
        .sortedWith(
            compareByDescending<TimeReportEntry> { it.createdAtDateTime() ?: LocalDateTime.MIN }
                .thenBy(TimeReportEntry::activity),
        )
        .take(MAX_CACHED_ENTRIES)

    private companion object {
        const val MAX_CACHED_ENTRIES = 10_000
    }
}
