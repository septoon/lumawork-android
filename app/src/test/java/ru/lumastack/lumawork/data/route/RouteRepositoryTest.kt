package ru.lumastack.lumawork.data.route

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.lumastack.lumawork.domain.model.AppSession
import ru.lumastack.lumawork.domain.model.AppUser
import ru.lumastack.lumawork.domain.route.RouteDayRecord
import ru.lumastack.lumawork.domain.route.RouteWorkType
import ru.lumastack.lumawork.domain.route.RouteSettings
import ru.lumastack.lumawork.domain.route.defaultRouteDay

class RouteRepositoryTest {
    private val date = "2026-08-11"
    private val settings = RouteSettings("Склад", "")
    private val session = AppSession("token", AppUser("user-1", "user@example.com", "user"))

    @Test
    fun `unsent local draft is never overwritten by refresh`() = runBlocking {
        val draft = defaultRouteDay(date, settings).copy(
            stops = defaultRouteDay(date, settings).stops.mapIndexed { index, stop ->
                if (index == 1) stop.copy(address = "Клиент") else stop
            },
        )
        val api = FakeRouteApi(remote = defaultRouteDay(date, settings).copy(distanceKm = 99))
        val repository = RouteRepository(api, FakeRouteCache())

        val refreshed = repository.refresh(session, date, settings, draft)

        assertNull(refreshed)
        assertEquals(0, api.fetchCount)
    }

    @Test
    fun `remote day remains a candidate until current selection accepts it`() = runBlocking {
        val remote = defaultRouteDay(date, settings).copy(distanceKm = 42)
        val cache = FakeRouteCache()
        val repository = RouteRepository(FakeRouteApi(remote), cache)

        val refreshed = repository.refresh(session, date, settings, local = null)

        assertEquals(42, refreshed?.distanceKm)
        assertNull(cache.values["user-1" to date])
    }

    @Test
    fun `successful send retains submitted route and mileage without overwriting newer cache`() = runBlocking {
        val cache = FakeRouteCache()
        val api = FakeRouteApi(remote = null)
        val repository = RouteRepository(api, cache)
        val record = defaultRouteDay(date, settings).copy(distanceKm = 20, periodStartOdometer = 1000)

        val sent = repository.send(session, record)

        assertEquals(1, api.sendCount)
        assertEquals(record.copy(sent = true), sent)
        assertNull(cache.values["user-1" to date])
    }
    @Test fun `shared monthly odometer alone must not suppress remote day loading`() = runBlocking {
        val api = FakeRouteApi(defaultRouteDay(date, settings).copy(distanceKm = 42))
        val repository = RouteRepository(api, FakeRouteCache())
        val refreshed = repository.refresh(session, date, settings, defaultRouteDay(date, settings).copy(periodStartOdometer = 1000))
        assertEquals(1, api.fetchCount)
        assertEquals(42, refreshed?.distanceKm)
    }

}

private class FakeRouteApi(private val remote: RouteDayRecord?) : RouteApi {
    var fetchCount = 0
    var sendCount = 0

    override suspend fun fetchDay(
        token: String,
        date: String,
        settings: RouteSettings,
        workType: RouteWorkType,
    ): RouteDayRecord? {
        fetchCount += 1
        return remote
    }

    override suspend fun sendDay(token: String, record: RouteDayRecord) {
        sendCount += 1
    }
}

private class FakeRouteCache : RouteCache {
    val values = mutableMapOf<Pair<String, String>, RouteDayRecord>()

    override suspend fun read(userId: String, date: String, workType: RouteWorkType): RouteDayRecord? = values[userId to date]

    override suspend fun save(userId: String, record: RouteDayRecord) {
        values[userId to record.date] = record
    }
}
