package ru.lumastack.lumawork.data.route

import ru.lumastack.lumawork.domain.model.AppSession
import ru.lumastack.lumawork.domain.route.RouteDayRecord
import ru.lumastack.lumawork.domain.route.RouteSettings
import ru.lumastack.lumawork.domain.route.defaultRouteDay
import ru.lumastack.lumawork.domain.route.hasWorkData
import ru.lumastack.lumawork.domain.route.normalizeRouteDay

class RouteRepository(
    private val api: RouteApi,
    private val cache: RouteCache,
) {
    suspend fun monthlyMileage(session: AppSession, monthKey: String): RouteMonthlyMileage? {
        val records = cache.readMonth(session.user.id, monthKey)
        val distanceByDate = records.mapNotNull { record ->
            record.distanceKm?.takeIf { it >= 0 }?.let { record.date to it }
        }.toMap()
        if (distanceByDate.isEmpty()) return null
        return RouteMonthlyMileage(
            monthKey = monthKey,
            latestDate = distanceByDate.keys.maxOrNull() ?: "$monthKey-01",
            totalKm = distanceByDate.values.sum(),
        )
    }
    suspend fun readLocal(
        session: AppSession,
        date: String,
        settings: RouteSettings,
    ): RouteDayRecord? = cache.read(session.user.id, date)?.let {
        normalizeRouteDay(it, date, settings)
    }

    suspend fun refresh(
        session: AppSession,
        date: String,
        settings: RouteSettings,
        local: RouteDayRecord?,
    ): RouteDayRecord? {
        if (local != null && local.hasWorkData() && !local.sent) return null
        return api.fetchDay(session.token, date, settings)?.also {
            cache.save(session.user.id, it)
        }
    }

    suspend fun saveLocal(session: AppSession, record: RouteDayRecord) {
        cache.save(session.user.id, record)
    }

    suspend fun send(
        session: AppSession,
        record: RouteDayRecord,
        settings: RouteSettings,
    ): RouteDayRecord {
        api.sendDay(session.token, record)
        return defaultRouteDay(record.date, settings).copy(sent = true).also {
            cache.save(session.user.id, it)
        }
    }
}

data class RouteMonthlyMileage(val monthKey: String, val latestDate: String, val totalKm: Int)
