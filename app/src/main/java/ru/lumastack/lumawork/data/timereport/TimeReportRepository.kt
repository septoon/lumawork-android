package ru.lumastack.lumawork.data.timereport

import java.time.LocalDate
import java.time.LocalDateTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ru.lumastack.lumawork.data.simpleone.ClosedRequestsXlsxExport
import ru.lumastack.lumawork.data.simpleone.SimpleOneApi
import ru.lumastack.lumawork.data.simpleone.SimpleOneFailure
import ru.lumastack.lumawork.data.simpleone.SimpleOneSessionStore
import ru.lumastack.lumawork.data.simpleone.simpleOneSessionFingerprint
import ru.lumastack.lumawork.domain.timereport.TimeReportEntry
import ru.lumastack.lumawork.domain.timereport.TimeReportSnapshot
import ru.lumastack.lumawork.domain.timereport.TimeReportSource

class TimeReportRepository(
    private val api: SimpleOneApi,
    private val sessionStore: SimpleOneSessionStore,
    private val cache: TimeReportCache,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()

    suspend fun read(ownerUserId: String, simpleOneUserId: String = "", allowOfflineCache: Boolean = false): TimeReportSnapshot? {
        val cached = cache.read(ownerUserId) ?: return null
        if (simpleOneUserId.isNotBlank()) return cached.takeIf { it.simpleOneUserId == simpleOneUserId }
        if (allowOfflineCache) return cached
        val session = withContext(Dispatchers.IO) { sessionStore.read(ownerUserId) } ?: return null
        return cached.takeIf { it.sessionFingerprint == simpleOneSessionFingerprint(session) }
    }

    suspend fun refresh(ownerUserId: String, simpleOneUserId: String, onProgress: (String) -> Unit = {}): TimeReportSnapshot = mutex.withLock {
        val session = withContext(Dispatchers.IO) { sessionStore.read(ownerUserId) } ?: throw SimpleOneFailure.MissingCredentials
        try {
            val userId = simpleOneUserId.ifBlank {
                onProgress("Определяю пользователя SimpleOne.")
                api.currentUser(session.authKey).sysId
            }
            onProgress("Читаю трудозатраты из SimpleOne.")
            val (incoming, source) = try {
                val entries = api.timeReportEntries(session.authKey) { count -> onProgress("Получено строк: $count") }
                if (entries.isEmpty()) throw SimpleOneFailure.InvalidResponse
                entries to TimeReportSource.ListApi
            } catch (error: CancellationException) {
                throw error
            } catch (error: SimpleOneFailure.Unauthorized) {
                throw error
            } catch (_: Exception) {
                onProgress("Список недоступен. Жду XLSX SimpleOne.")
                val bytes = api.exportTimeReport(userId, session.authKey)
                onProgress("Разбираю XLSX.")
                withContext(Dispatchers.Default) { TimeReportParser.parseXlsx(bytes) } to TimeReportSource.Xlsx
            }
            onProgress("Сохраняю локальный архив: ${incoming.size} строк.")
            val existing = read(ownerUserId, userId)?.entries.orEmpty()
            val snapshot = withContext(Dispatchers.Default) {
                TimeReportSnapshot(userId, merge(existing, incoming), nowEpochMillis(), source, simpleOneSessionFingerprint(session))
            }
            currentCoroutineContext().ensureActive()
            if (withContext(Dispatchers.IO) { sessionStore.read(ownerUserId) } != session) throw CancellationException("SimpleOne session changed")
            cache.save(ownerUserId, snapshot)
            snapshot
        } catch (error: SimpleOneFailure.Unauthorized) {
            withContext(Dispatchers.IO) { if (sessionStore.read(ownerUserId) == session) sessionStore.clear(ownerUserId) }
            throw error
        }
    }

    suspend fun importXlsx(ownerUserId: String, simpleOneUserId: String, bytes: ByteArray): TimeReportSnapshot = mutex.withLock {
        require(simpleOneUserId.isNotBlank()) { "Сначала войдите в SimpleOne." }
        val incoming = withContext(Dispatchers.Default) { TimeReportParser.parseXlsx(bytes) }
        val previous = read(ownerUserId, simpleOneUserId)
        val existing = previous?.entries.orEmpty()
        val fingerprint = withContext(Dispatchers.IO) { sessionStore.read(ownerUserId)?.let(::simpleOneSessionFingerprint) }.orEmpty()
        withContext(Dispatchers.Default) {
            TimeReportSnapshot(simpleOneUserId, merge(existing, incoming), nowEpochMillis(), TimeReportSource.Xlsx, fingerprint)
        }.also { cache.save(ownerUserId, it) }
    }

    suspend fun exportXlsx(entries: List<TimeReportEntry>): ByteArray = withContext(Dispatchers.Default) {
        ClosedRequestsXlsxExport.createTable("Трудозатраты", listOf(
            "ID", "Активность", "Период", "Когда создано", "Дата проведения работ", "Время работ (м)",
            "Время в дороге (м)", "Внеурочные работы (м)", "Рабочие заметки", "Трудозатраты нерабочие", "Внеурочные работы", "Исполнитель",
        ), entries.map { entry -> listOf(
            entry.simpleOneRecordId, entry.activity, entry.period, entry.createdAt, entry.workDate,
            entry.workMinutes.toString(), entry.travelMinutes.toString(), entry.overtimeMinutes.toString(),
            entry.notes, entry.nonWorkCosts, if (entry.isOvertime) "Да" else "Нет", entry.executor,
        ) })
    }

    suspend fun deleteAll(ownerUserId: String, simpleOneUserId: String): Int = mutex.withLock {
        val snapshot = read(ownerUserId, simpleOneUserId) ?: return@withLock 0
        cache.delete(ownerUserId)
        snapshot.entries.size
    }

    suspend fun deleteRange(ownerUserId: String, simpleOneUserId: String, startDate: LocalDate, endDate: LocalDate): Int = mutex.withLock {
        val snapshot = read(ownerUserId, simpleOneUserId) ?: return@withLock 0
        val lower = minOf(startDate, endDate)
        val upper = maxOf(startDate, endDate)
        val remaining = snapshot.entries.filter { entry ->
            entry.effectiveWorkDateTime()?.toLocalDate()?.let { it < lower || it > upper } ?: true
        }
        val deleted = snapshot.entries.size - remaining.size
        if (deleted != 0) cache.save(ownerUserId, snapshot.copy(entries = remaining))
        deleted
    }

    private fun merge(existing: List<TimeReportEntry>, incoming: List<TimeReportEntry>): List<TimeReportEntry> =
        (existing + incoming).associateBy(TimeReportEntry::stableId).values.sortedWith(
            compareByDescending<TimeReportEntry> { it.effectiveWorkDateTime() ?: LocalDateTime.MIN }.thenBy(TimeReportEntry::activity),
        )
}
