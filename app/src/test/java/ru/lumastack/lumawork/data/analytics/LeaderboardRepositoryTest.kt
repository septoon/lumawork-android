package ru.lumastack.lumawork.data.analytics

import java.nio.file.Files
import java.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import ru.lumastack.lumawork.domain.analytics.*

@OptIn(ExperimentalCoroutinesApi::class)
class LeaderboardRepositoryTest {
    private val identity = StatisticsIdentity("owner", "token", "so-session")
    private val now = Instant.parse("2026-11-07T09:00:00Z")
    private fun response(filter: LeaderboardFilter = LeaderboardFilter(), items: List<LeaderboardEntry> = emptyList(), next: Int? = null) = LeaderboardResponse(
        filter.period, filter.category, LeaderboardCity.All, listOf(LeaderboardCity.All), false, "Europe/Moscow", now.toString(), filter.period.key(now),
        null, null, now.toString(), 1, 3, 2, 3, items, null, next)
    private fun entry(id: String) = LeaderboardEntry(id, "Инженер $id", null, 1, 3, 3, workCategories.associateWith { if (it == "portable") 3 else 0 }, true, now.toString(), now.toString(), false)
    private class Api : LeaderboardApi {
        var enabled = true
        var fail = false
        var load: suspend (LeaderboardFilter, Int) -> LeaderboardResponse = { _, _ -> error("set response") }
        override suspend fun capability(token: String): LeaderboardCapability { if (fail) error("offline"); return LeaderboardCapability(enabled, null) }
        override suspend fun leaderboard(token: String, filter: LeaderboardFilter, offset: Int) = load(filter, offset)
    }
    @Test fun onlyUploadedUsersAreVisibleIncludingZerosAndLegacyCache() = runTest {
        val zero = entry("zero").copy(rank = null, value = 0, completedWithVisitCount = 0, categoryCounts = workCategories.associateWith { 0 })
        val missing = zero.copy(userId = "missing", hasData = false, value = null, completedWithVisitCount = null, sourceSyncedAt = null, receivedAt = null, isCurrentUser = true)
        val raw = response(items = listOf(entry("positive"), zero, missing), next = 50).copy(
            currentUserInScope = true, me = missing, totalCount = 60, registeredUsersCount = 60)
        val cache = FileLeaderboardCache(Files.createTempDirectory("legacy-ranking").toFile(), Json { encodeDefaults = true })
        cache.write(identity, LeaderboardFilter(), raw, now)
        val visible = cache.read(identity, LeaderboardFilter(), now)!!.response.participantsOnly()
        assertEquals(listOf("positive", "zero"), visible.items.map { it.userId })
        assertNull(visible.me)
        assertNull(visible.nextOffset)
        assertFalse(visible.currentUserInScope)
        val empty = raw.copy(items = listOf(missing), usersWithDataCount = 0).participantsOnly()
        assertTrue(empty.items.isEmpty())
        assertNull(empty.nextOffset)
        assertEquals(50, raw.copy(usersWithDataCount = 55).participantsOnly().nextOffset)
        assertEquals("zero", raw.copy(me = zero.copy(isCurrentUser = true)).participantsOnly().me!!.userId)
    }
    @Test fun unknownCapabilityAndTransientFailureAndDisable() = runTest {
        val api = Api(); val access = LeaderboardAccessRepository(api, { "owner" to "token" })
        assertNull(access.state.value.enabled)
        access.refresh(); assertEquals(true, access.state.value.enabled)
        api.fail = true; access.refresh(); assertEquals(true, access.state.value.enabled)
        api.fail = false; api.enabled = false; access.refresh(); assertEquals(false, access.state.value.enabled)
        access.clear(); assertNull(access.state.value.enabled)
    }
    @Test fun emptyResponseIsCachedAndRestoredBeforeNetworkAndDisableRejectsLateResponse() = runTest {
        val api = Api(); val access = LeaderboardAccessRepository(api, { "owner" to "token" }); access.refresh()
        val cache = FileLeaderboardCache(Files.createTempDirectory("ranking-cache").toFile(), Json { encodeDefaults = true; ignoreUnknownKeys = true })
        val repo = LeaderboardRepository(api, cache, access, { identity }, backgroundScope, now = { now })
        api.load = { f, _ -> response(f) }
        repo.refresh().join()
        assertNotNull(repo.state.value.response)
        val waiting = CompletableDeferred<Unit>(); val started = CompletableDeferred<Unit>()
        api.load = { f, _ -> started.complete(Unit); waiting.await(); response(f, listOf(entry("late"))) }
        val cold = LeaderboardRepository(api, cache, access, { identity }, backgroundScope, now = { now })
        val load = cold.refresh(); started.await()
        assertNotNull(cold.state.value.response)
        assertTrue(cold.state.value.isSaved)
        access.disable(); cold.clear(); waiting.complete(Unit); load.join()
        assertNull(cold.state.value.response)
        assertNull(cache.read(identity, LeaderboardFilter(), now))
    }
    @Test fun livePagesDeduplicateAndRefreshReplacesRows() = runTest {
        val api = Api(); val access = LeaderboardAccessRepository(api, { "owner" to "token" }); access.refresh()
        val repo = LeaderboardRepository(api, FileLeaderboardCache(Files.createTempDirectory("ranking-page").toFile(), Json { encodeDefaults = true }), access, { identity }, backgroundScope, now = { now })
        api.load = { f, offset -> if (offset == 0) response(f, listOf(entry("one")), 1) else response(f, listOf(entry("one"), entry("two"))) }
        repo.refresh().join(); repo.loadNext().join()
        assertEquals(listOf("one", "two"), repo.state.value.response!!.items.map { it.userId })
        api.load = { f, _ -> response(f) }
        repo.refresh().join()
        assertTrue(repo.state.value.response!!.items.isEmpty())
    }
}
