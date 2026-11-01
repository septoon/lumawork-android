package ru.lumastack.lumawork.data.route

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.*
import org.junit.Test
import ru.lumastack.lumawork.domain.model.AppSession
import ru.lumastack.lumawork.domain.model.AppUser
import ru.lumastack.lumawork.domain.route.*

@OptIn(ExperimentalCoroutinesApi::class)
class RouteDeletionTest {
    @Test fun `month fetch begun before deletion cannot restore cached or returned day`() = runTest {
        val session = AppSession("token", AppUser("owner", "user@example.com", "user"))
        val date = "2026-10-23"
        val settings = RouteSettings("Склад", "")
        val response = CompletableDeferred<List<RouteDayRecord>>()
        val cache = DeletionCache()
        val api = object : RouteApi {
            override suspend fun fetchDay(token: String, date: String, settings: RouteSettings, workType: RouteWorkType): RouteDayRecord? = null
            override suspend fun fetchMonth(token: String, month: String, settings: RouteSettings) = response.await()
            override suspend fun deleteDay(token: String, date: String) {}
            override suspend fun sendDay(token: String, record: RouteDayRecord) {}
        }
        val repository = RouteRepository(api, cache)
        val loading = async { repository.refreshMonth(session, "2026-10", settings) }
        runCurrent()
        repository.deleteDay(session, date)
        response.complete(listOf(defaultRouteDay(date, settings).copy(sent = true, distanceKm = 42)))
        assertTrue(loading.await().isEmpty())
        assertTrue(cache.records.isEmpty())
        assertEquals(setOf(date), cache.excludedDates("owner"))
    }
    @Test fun `scoped deletion preserves the other type and blocks a late month response`() = runTest {
        val session = AppSession("token", AppUser("owner", "user@example.com", "user"))
        val date = "2026-10-23"
        val settings = RouteSettings("Склад", "")
        for (type in RouteWorkType.entries) {
            val response = CompletableDeferred<List<RouteDayRecord>>()
            val cache = DeletionCache()
            val originals = RouteWorkType.entries.map { defaultRouteDay(date, settings, it).copy(sent = true, distanceKm = 42) }
            originals.forEach { cache.save("owner", it) }
            val api = object : RouteApi {
                override suspend fun fetchDay(token: String, date: String, settings: RouteSettings, workType: RouteWorkType): RouteDayRecord? = null
                override suspend fun fetchMonth(token: String, month: String, settings: RouteSettings) = response.await()
                override suspend fun deleteRoute(token: String, date: String, workType: RouteWorkType) { assertEquals(type, workType) }
                override suspend fun sendDay(token: String, record: RouteDayRecord) {}
            }
            val repository = RouteRepository(api, cache)
            val loading = async { repository.refreshMonth(session, "2026-10", settings) }
            runCurrent()
            repository.deleteRoute(session, date, type)
            response.complete(originals)
            assertEquals(originals.filter { it.workType != type }, loading.await())
            assertNull(cache.read("owner", date, type))
            assertEquals(setOf(type.exclusionKey(date)), cache.excludedDates("owner"))
        }
    }

    @Test fun `restoring one type preserves the other type exclusion`() {
        val date = "2026-10-23"
        assertEquals(setOf("2026-10-23|ARM"), setOf(date).restoringRoute(date, RouteWorkType.Pos))
        assertTrue(setOf("2026-10-23|ARM").excludesRoute(date, RouteWorkType.Arm))
        assertFalse(setOf("2026-10-23|ARM").excludesRoute(date, RouteWorkType.Pos))
    }

    @Test fun `old exclusions response cannot undo successful explicit send`() = runTest {
        val session = AppSession("token", AppUser("owner", "user@example.com", "user"))
        val date = "2026-10-23"
        val settings = RouteSettings("Склад", "")
        val response = CompletableDeferred<Set<String>>()
        val cache = DeletionCache()
        cache.saveExcludedDates("owner", setOf(date))
        val api = object : RouteApi {
            override suspend fun fetchDay(token: String, date: String, settings: RouteSettings, workType: RouteWorkType): RouteDayRecord? = null
            override suspend fun excludedDates(token: String) = response.await()
            override suspend fun sendDay(token: String, record: RouteDayRecord) {}
        }
        val repository = RouteRepository(api, cache)
        val syncing = async { repository.syncExcludedDates(session) }
        runCurrent()
        repository.send(session, defaultRouteDay(date, settings))
        response.complete(setOf(date))
        assertEquals(setOf("2026-10-23|ARM"), syncing.await())
        assertEquals(setOf("2026-10-23|ARM"), cache.excludedDates("owner"))
    }

}
private class DeletionCache : RouteCache {
    val records = mutableListOf<RouteDayRecord>()
    private var excluded = emptySet<String>()
    override suspend fun read(userId: String, date: String, workType: RouteWorkType) = records.firstOrNull { it.date == date && it.workType == workType }
    override suspend fun save(userId: String, record: RouteDayRecord) { records += record }
    override suspend fun readMonth(userId: String, monthKey: String) = records.filter { it.date.startsWith(monthKey) }
    override suspend fun deleteDay(userId: String, date: String) { records.removeAll { it.date == date }; excluded = excluded + date }
    override suspend fun deleteRoute(userId: String, date: String, workType: RouteWorkType) { records.removeAll { it.date == date && it.workType == workType }; excluded = excluded + workType.exclusionKey(date) }
    override suspend fun excludedDates(userId: String) = excluded
    override suspend fun saveExcludedDates(userId: String, dates: Set<String>) { excluded = dates }
}
