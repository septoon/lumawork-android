package ru.lumastack.lumawork.data.route

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.lumastack.lumawork.domain.model.AppSession
import ru.lumastack.lumawork.domain.model.AppUser
import ru.lumastack.lumawork.domain.route.RouteDayRecord
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
    fun `remote day is cached under current user`() = runBlocking {
        val remote = defaultRouteDay(date, settings).copy(distanceKm = 42)
        val cache = FakeRouteCache()
        val repository = RouteRepository(FakeRouteApi(remote), cache)

        val refreshed = repository.refresh(session, date, settings, local = null)

        assertEquals(42, refreshed?.distanceKm)
        assertEquals(remote, cache.values["user-1" to date])
    }

    @Test
    fun `successful send resets only the local day and marks it sent`() = runBlocking {
        val cache = FakeRouteCache()
        val api = FakeRouteApi(remote = null)
        val repository = RouteRepository(api, cache)
        val record = defaultRouteDay(date, settings).copy(distanceKm = 20, periodStartOdometer = 1000)

        val reset = repository.send(session, record, settings)

        assertEquals(1, api.sendCount)
        assertEquals(true, reset.sent)
        assertEquals(null, reset.distanceKm)
        assertEquals(reset, cache.values["user-1" to date])
    }
}

private class FakeRouteApi(private val remote: RouteDayRecord?) : RouteApi {
    var fetchCount = 0
    var sendCount = 0

    override suspend fun fetchDay(
        token: String,
        date: String,
        settings: RouteSettings,
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

    override suspend fun read(userId: String, date: String): RouteDayRecord? = values[userId to date]

    override suspend fun save(userId: String, record: RouteDayRecord) {
        values[userId to record.date] = record
    }
}
