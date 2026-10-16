package ru.lumastack.lumawork.data.route

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.lumastack.lumawork.domain.model.AppSession
import ru.lumastack.lumawork.domain.route.RouteWorkType
import ru.lumastack.lumawork.domain.route.RouteDayRecord
import ru.lumastack.lumawork.domain.route.RouteSettings
import ru.lumastack.lumawork.domain.route.defaultRouteDay
import ru.lumastack.lumawork.domain.route.normalizeRouteDay

class RouteRepository(
    private val api: RouteApi,
    private val cache: RouteCache,
) {
    private val cacheMutex = Mutex()
    suspend fun monthlyMileage(session: AppSession, monthKey: String): RouteMonthlyMileage? {
        val records = cache.readMonth(session.user.id, monthKey)
        val distanceByDate = records.mapNotNull { record ->
            record.distanceKm?.takeIf { it >= 0 }?.let { record.workType.storageKey(record.date) to it }
        }.toMap()
        if (distanceByDate.isEmpty()) return null
        return RouteMonthlyMileage(
            monthKey = monthKey,
            latestDate = distanceByDate.keys.map { it.take(10) }.maxOrNull() ?: "$monthKey-01",
            totalKm = distanceByDate.values.sum(),
        )
    }
    suspend fun readLocal(
        session: AppSession,
        date: String,
        settings: RouteSettings,
        workType: RouteWorkType = RouteWorkType.Pos,
    ): RouteDayRecord? = cache.read(session.user.id, date, workType)?.let {
        normalizeRouteDay(it, date, settings).copy(periodStartOdometer = if (cache.hasOdometerSetting(session.user.id, date.take(7))) cache.readOdometer(session.user.id, date.take(7)) else it.periodStartOdometer)
    }

    suspend fun readQueued(session: AppSession): List<RouteDayRecord> = cache.readQueued(session.user.id)
    suspend fun readMonth(session: AppSession, month: String): List<RouteDayRecord> = cache.readMonth(session.user.id, month)
    suspend fun refreshMonth(session: AppSession, month: String, settings: RouteSettings): List<RouteDayRecord> {
        val remote = api.fetchMonth(session.token, month, settings)
        cacheMutex.withLock {
            remote.forEach { candidate ->
                val local = cache.read(session.user.id, candidate.date, candidate.workType)
                if (local == null || local.sent) cache.save(session.user.id, candidate)
            }
        }
        return remote
    }
    suspend fun suggestedOdometer(session: AppSession, month: String): Int? = api.suggestedOdometer(session.token, month)
    suspend fun sendGsmReport(session: AppSession, month: String): GsmReportResult = api.sendGsmReport(session.token, month)
    suspend fun officeAddresses(session: AppSession): List<String> = api.fetchOfficeAddresses(session.token)
    suspend fun hasOdometerSetting(session: AppSession, month: String): Boolean = cache.hasOdometerSetting(session.user.id, month)
    suspend fun readOdometer(session: AppSession, month: String): Int? = cache.readOdometer(session.user.id, month)
    suspend fun saveOdometer(session: AppSession, month: String, value: Int?) = cache.saveOdometer(session.user.id, month, value)

    suspend fun refresh(
        session: AppSession,
        date: String,
        settings: RouteSettings,
        local: RouteDayRecord?,
        workType: RouteWorkType = RouteWorkType.Pos,
    ): RouteDayRecord? {
        if (local != null && !local.sent && local.copy(periodStartOdometer = null) != defaultRouteDay(date, settings, workType)) return null
        // The caller accepts and caches this candidate only if the selected day has not been edited.
        return api.fetchDay(session.token, date, settings, workType)
    }

    suspend fun saveLocal(session: AppSession, record: RouteDayRecord) {
        cacheMutex.withLock { cache.save(session.user.id, record) }
    }

    suspend fun send(
        session: AppSession,
        record: RouteDayRecord,
    ): RouteDayRecord {
        api.sendDay(session.token, record)
        return record.copy(sent = true, queuedForSend = false)
    }
}

data class RouteMonthlyMileage(val monthKey: String, val latestDate: String, val totalKm: Int)
