package ru.lumastack.lumawork.data.coordination

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import ru.lumastack.lumawork.data.simpleone.*
import ru.lumastack.lumawork.domain.backpack.BackpackItem
import ru.lumastack.lumawork.domain.coordination.CoordinationRegion
import ru.lumastack.lumawork.domain.simpleone.SimpleOneRequest
import ru.lumastack.lumawork.domain.simpleone.SimpleOneUser
import ru.lumastack.lumawork.domain.timereport.TimeReportEntry

@OptIn(ExperimentalCoroutinesApi::class)
class CoordinationGroupClosedRequestsRepositoryTest {
    @Test fun `switching profile city clears data uses new query and keeps separate offline caches`() = runTest {
        var city = "alushta"
        val api = ArchiveApi { _, _, _ -> GroupClosedRequestsPage(listOf(record(city)), 1, false) }
        val cache = ArchiveCache()
        val repository = CoordinationGroupClosedRequestsRepository(api, ArchiveSession(), cache, this,
            preparationDispatcher = StandardTestDispatcher(testScheduler))
        repository.activate("owner", "simple", true, CoordinationRegion.Alushta)
        advanceUntilIdle()
        assertEquals("alushta", repository.state.value.records.single().id)
        city = "yalta"
        repository.activate("owner", "simple", true, CoordinationRegion.Yalta)
        assertTrue(repository.state.value.records.isEmpty())
        assertEquals(CoordinationRegion.Yalta, repository.state.value.region)
        advanceUntilIdle()
        assertEquals("yalta", repository.state.value.records.single().id)
        assertEquals(listOf(CoordinationRegion.Alushta, CoordinationRegion.Yalta), api.regions)
        repository.activate("owner", "simple", true, CoordinationRegion.Alushta, startOffline = true)
        assertEquals("alushta", repository.state.value.records.single().id)
        repository.activate("owner", "simple", true, region = null)
        advanceUntilIdle()
        assertTrue(repository.state.value.records.isEmpty())
        assertFalse(repository.state.value.isLoading)
        assertEquals(2, api.calls.size)
    }

    @Test fun `late response from former profile city cannot replace the new city archive`() = runTest {
        val oldResponse = CompletableDeferred<GroupClosedRequestsPage>()
        var call = 0
        val api = ArchiveApi { _, _, _ ->
            if (call++ == 0) kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { oldResponse.await() }
            else GroupClosedRequestsPage(listOf(record("yalta")), 1, false)
        }
        val cache = ArchiveCache()
        val repository = CoordinationGroupClosedRequestsRepository(api, ArchiveSession(), cache, this,
            preparationDispatcher = StandardTestDispatcher(testScheduler))
        repository.activate("owner", "simple", true, CoordinationRegion.Alushta)
        runCurrent()
        repository.activate("owner", "simple", true, CoordinationRegion.Yalta)
        runCurrent()
        assertEquals("yalta", repository.state.value.records.single().id)
        oldResponse.complete(GroupClosedRequestsPage(listOf(record("old-alushta")), 1, false))
        advanceUntilIdle()
        assertEquals(CoordinationRegion.Yalta, repository.state.value.region)
        assertEquals("yalta", repository.state.value.records.single().id)
        assertEquals(1, cache.writes.size)
    }

    @Test fun `legacy archive migrates only to Alushta never another profile city`() = runTest {
        val legacyKey = groupClosedDigest("5:owner6:simple")
        val cache = ArchiveCache().apply { values[legacyKey] = GroupClosedRequestsSnapshot(
            records = listOf(record("legacy-alushta")), nextPage = null, updatedAtEpochMillis = 123L,
        ) }
        val api = ArchiveApi { _, _, _ -> error("Offline must not fetch") }
        val repository = CoordinationGroupClosedRequestsRepository(api, ArchiveSession(), cache, this,
            preparationDispatcher = StandardTestDispatcher(testScheduler))
        repository.activate("owner", "simple", true, CoordinationRegion.Yalta, startOffline = true)
        assertTrue(repository.state.value.records.isEmpty())
        assertFalse(legacyKey in cache.readKeys)
        repository.activate("owner", "simple", true, CoordinationRegion.Alushta, startOffline = true)
        assertEquals("legacy-alushta", repository.state.value.records.single().id)
        assertTrue(legacyKey in cache.readKeys)
    }

    @Test fun `completed cache publishes prepared items before blocked network refresh`() = runTest {
        val session = ArchiveSession()
        val cache = ArchiveCache()
        val first = CoordinationGroupClosedRequestsRepository(
            ArchiveApi { _, _, _ -> GroupClosedRequestsPage(listOf(record("cached").copy(requestType = "install")), 1, false) },
            session, cache, this, preparationDispatcher = StandardTestDispatcher(testScheduler),
        )
        first.activate("owner", "simple", true, CoordinationRegion.Alushta)
        advanceUntilIdle()
        val response = CompletableDeferred<GroupClosedRequestsPage>()
        val restored = CoordinationGroupClosedRequestsRepository(
            ArchiveApi { _, _, _ -> response.await() }, session, cache, this,
            preparationDispatcher = StandardTestDispatcher(testScheduler),
        )
        restored.activate("owner", "simple", true, CoordinationRegion.Alushta)
        runCurrent()
        val ready = restored.state.value
        assertTrue(ready.didLoadCache)
        assertTrue(ready.isLoading)
        assertEquals("cached", ready.preparedItems.single().request.id)
        assertEquals(listOf("install" to "Установка"), ready.availableTypes)
        assertEquals("owner", ready.ownerUserId)
        assertEquals("simple", ready.simpleOneUserId)
        response.completeExceptionally(SimpleOneFailure.InvalidResponse)
        advanceUntilIdle()
        assertSame(ready.preparedItems, restored.state.value.preparedItems)
        assertNotNull(restored.state.value.errorMessage)
    }

    @Test fun `repeated activation and unchanged refresh reuse prepared list instance`() = runTest {
        val api = ArchiveApi { _, _, _ -> GroupClosedRequestsPage(listOf(record("cached")), 1, false) }
        val repository = CoordinationGroupClosedRequestsRepository(
            api, ArchiveSession(), ArchiveCache(), this, preparationDispatcher = StandardTestDispatcher(testScheduler),
        )
        repository.activate("owner", "simple", true, CoordinationRegion.Alushta)
        advanceUntilIdle()
        val prepared = repository.state.value.preparedItems
        val types = repository.state.value.availableTypes
        repository.activate("owner", "simple", true, CoordinationRegion.Alushta)
        assertSame(prepared, repository.state.value.preparedItems)
        advanceUntilIdle()
        assertSame(prepared, repository.state.value.preparedItems)
        assertSame(types, repository.state.value.availableTypes)
    }

    @Test fun `process recreation reuses completed account cache after auth rotation`() = runTest {
        val cache = ArchiveCache()
        val session = ArchiveSession()
        val api = ArchiveApi { _, _, _ -> GroupClosedRequestsPage(listOf(record("cached")), 1, false) }
        val first = CoordinationGroupClosedRequestsRepository(
            api, session, cache, this, preparationDispatcher = StandardTestDispatcher(testScheduler),
        )
        first.activate("owner", "simple", true, CoordinationRegion.Alushta)
        advanceUntilIdle()
        session.auth = "rotated-secret"
        val restored = CoordinationGroupClosedRequestsRepository(
            api, session, cache, this, preparationDispatcher = StandardTestDispatcher(testScheduler),
        )
        restored.activate("owner", "simple", true, CoordinationRegion.Alushta, startOffline = true)
        assertTrue(restored.state.value.didLoadCache)
        assertEquals("cached", restored.state.value.preparedItems.single().request.id)
        assertEquals(2, cache.readKeys.distinct().size)
        restored.activate("owner", "different-simple", true, CoordinationRegion.Alushta, startOffline = true)
        assertTrue(restored.state.value.records.isEmpty())
        assertTrue(restored.state.value.preparedItems.isEmpty())
        assertEquals("different-simple", restored.state.value.simpleOneUserId)
        restored.deactivate()
        assertEquals("", restored.state.value.ownerUserId)
        assertEquals("", restored.state.value.simpleOneUserId)
    }

    @Test fun `same account auth rotation retains prepared index while scoped disk reload waits`() = runTest {
        val cache = ArchiveCache()
        val session = ArchiveSession()
        val repository = CoordinationGroupClosedRequestsRepository(
            ArchiveApi { _, _, _ -> GroupClosedRequestsPage(listOf(record("cached")), 1, false) },
            session, cache, this, preparationDispatcher = StandardTestDispatcher(testScheduler),
        )
        repository.activate("owner", "simple", true, CoordinationRegion.Alushta)
        advanceUntilIdle()
        val prepared = repository.state.value.preparedItems
        val gate = CompletableDeferred<Unit>()
        cache.beforeRead = { gate.await() }
        session.auth = "rotated-secret"
        val rotation = launch { repository.activate("owner", "simple", true, CoordinationRegion.Alushta, startOffline = true) }
        runCurrent()
        assertFalse(rotation.isCompleted)
        assertTrue(repository.state.value.didLoadCache)
        assertSame(prepared, repository.state.value.preparedItems)
        gate.complete(Unit)
        advanceUntilIdle()
        assertSame(prepared, repository.state.value.preparedItems)
    }

    @Test fun `full pass keeps paging without total and checkpoints every page`() = runTest {
        val api = ArchiveApi { page, _, _ ->
            if (page == 1) GroupClosedRequestsPage(listOf(record("a")), hasMore = true)
            else GroupClosedRequestsPage(listOf(record("b")), hasMore = false)
        }
        val cache = ArchiveCache()
        val repository = CoordinationGroupClosedRequestsRepository(api, ArchiveSession(), cache, this, preparationDispatcher = StandardTestDispatcher(testScheduler)) { 123L }
        repository.activate("owner", "simple", authorized = true, region = CoordinationRegion.Alushta)
        advanceUntilIdle()
        assertEquals(listOf(1, 2), api.calls.map { it.first })
        assertEquals(setOf("a", "b"), repository.state.value.records.map { it.id }.toSet())
        assertEquals(2, cache.writes.size)
        assertEquals(2, cache.writes.first().nextPage)
        assertEquals(setOf("a"), cache.writes.first().refreshedIDs)
        assertNull(cache.writes.last().nextPage)
        assertEquals(123L, repository.state.value.updatedAtEpochMillis)
        assertEquals(1L, repository.state.value.completedRevision)
    }

    @Test fun `offline activation restores partial cache and next online instance resumes page`() = runTest {
        val cache = ArchiveCache()
        val session = ArchiveSession()
        val firstApi = ArchiveApi { page, _, _ ->
            if (page == 1) GroupClosedRequestsPage(listOf(record("a")), hasMore = true)
            else throw SimpleOneFailure.InvalidResponse
        }
        val first = CoordinationGroupClosedRequestsRepository(firstApi, session, cache, this, preparationDispatcher = StandardTestDispatcher(testScheduler))
        first.activate("owner", "simple", true, CoordinationRegion.Alushta)
        advanceUntilIdle()
        val resumedApi = ArchiveApi { _, _, _ -> GroupClosedRequestsPage(listOf(record("b")), hasMore = false) }
        val resumed = CoordinationGroupClosedRequestsRepository(resumedApi, session, cache, this, preparationDispatcher = StandardTestDispatcher(testScheduler))
        resumed.activate("owner", "simple", true, CoordinationRegion.Alushta, startOffline = true)
        advanceUntilIdle()
        assertTrue(resumedApi.calls.isEmpty())
        assertEquals("a", resumed.state.value.records.single().id)
        assertEquals(1, resumed.state.value.loadedCount)
        resumed.activate("owner", "simple", true, CoordinationRegion.Alushta)
        advanceUntilIdle()
        assertEquals(2, resumedApi.calls.single().first)
        assertEquals(setOf("a", "b"), resumed.state.value.records.map { it.id }.toSet())
    }

    @Test fun `cached list survives partial full refresh and error then deletions reconcile`() = runTest {
        val cache = ArchiveCache()
        var pass = 0
        val api = ArchiveApi { page, _, _ ->
            when (pass) {
                0 -> GroupClosedRequestsPage(listOf(record("old"), record("keep")), 2, false)
                1 -> if (page == 1) GroupClosedRequestsPage(listOf(record("keep")), 1, true)
                     else throw SimpleOneFailure.InvalidResponse
                else -> GroupClosedRequestsPage(emptyList(), 1, false)
            }
        }
        val repository = CoordinationGroupClosedRequestsRepository(api, ArchiveSession(), cache, this, preparationDispatcher = StandardTestDispatcher(testScheduler))
        repository.activate("owner", "simple", true, CoordinationRegion.Alushta)
        advanceUntilIdle()
        pass = 1
        repository.refresh(forceFull = true)
        advanceUntilIdle()
        assertEquals(2, repository.state.value.records.size)
        assertNotNull(repository.state.value.errorMessage)
        pass = 2
        repository.refresh()
        advanceUntilIdle()
        assertEquals("keep", repository.state.value.records.single().id)
        assertNull(repository.state.value.errorMessage)
    }

    @Test fun `incremental stops only when exact page and known matching archive total`() = runTest {
        var pass = 0
        val api = ArchiveApi { page, _, newest ->
            if (pass == 0) GroupClosedRequestsPage(listOf(record("a"), record("b")), 2, false)
            else {
                assertTrue(newest)
                if (page == 1) GroupClosedRequestsPage(listOf(record("a")), 2, true)
                else error("Unchanged page should stop")
            }
        }
        val repository = CoordinationGroupClosedRequestsRepository(api, ArchiveSession(), ArchiveCache(), this, preparationDispatcher = StandardTestDispatcher(testScheduler))
        repository.activate("owner", "simple", true, CoordinationRegion.Alushta)
        advanceUntilIdle()
        pass = 1
        repository.refresh()
        advanceUntilIdle()
        assertEquals(2, api.calls.size)
        assertEquals(2, repository.state.value.records.size)
        assertNull(repository.state.value.errorMessage)
    }

    @Test fun `unknown total prevents incremental early stop and full pass reconciles removals`() = runTest {
        var pass = 0
        val api = ArchiveApi { page, _, _ ->
            if (pass == 0) GroupClosedRequestsPage(listOf(record("a"), record("removed")), 2, false)
            else if (page == 1) GroupClosedRequestsPage(listOf(record("a")), hasMore = true)
            else GroupClosedRequestsPage(emptyList(), hasMore = false)
        }
        val repository = CoordinationGroupClosedRequestsRepository(api, ArchiveSession(), ArchiveCache(), this, preparationDispatcher = StandardTestDispatcher(testScheduler))
        repository.activate("owner", "simple", true, CoordinationRegion.Alushta)
        advanceUntilIdle()
        pass = 1
        repository.refresh()
        advanceUntilIdle()
        assertEquals(listOf(1, 1, 2), api.calls.map { it.first })
        assertEquals("a", repository.state.value.records.single().id)
    }

    @Test fun `refresh dedupes and survives screen caller cancellation`() = runTest {
        val response = CompletableDeferred<GroupClosedRequestsPage>()
        val api = ArchiveApi { _, _, _ -> response.await() }
        val repository = CoordinationGroupClosedRequestsRepository(api, ArchiveSession(), ArchiveCache(), this, preparationDispatcher = StandardTestDispatcher(testScheduler))
        val screen = launch { repository.activate("owner", "simple", true, CoordinationRegion.Alushta); repository.awaitRefresh() }
        runCurrent()
        repository.refresh()
        screen.cancel()
        response.complete(GroupClosedRequestsPage(listOf(record("a")), 1, false))
        advanceUntilIdle()
        assertEquals(1, api.calls.size)
        assertEquals("a", repository.state.value.records.single().id)
        assertFalse(repository.state.value.isLoading)
    }

    @Test fun `old response and old 401 cannot publish or clear replacement login`() = runTest {
        val response = CompletableDeferred<GroupClosedRequestsPage>()
        val session = ArchiveSession()
        val cache = ArchiveCache()
        val api = ArchiveApi { _, _, _ -> response.await() }
        val repository = CoordinationGroupClosedRequestsRepository(api, session, cache, this, preparationDispatcher = StandardTestDispatcher(testScheduler))
        repository.activate("owner", "simple", true, CoordinationRegion.Alushta)
        runCurrent()
        session.auth = "new-auth"
        response.completeExceptionally(SimpleOneFailure.Unauthorized)
        advanceUntilIdle()
        assertEquals("new-auth", session.read("owner")?.authKey)
        assertTrue(repository.state.value.records.isEmpty())
        assertNull(repository.state.value.errorMessage)
        assertTrue(cache.writes.isEmpty())
        assertEquals(0, session.clearCount)
        assertFalse(repository.state.value.requiresAuthorization)
    }

    @Test fun `only current session 401 requests shared authorization and activation resets flag`() = runTest {
        val session = ArchiveSession()
        val repository = CoordinationGroupClosedRequestsRepository(
            ArchiveApi { _, _, _ -> throw SimpleOneFailure.Unauthorized }, session, ArchiveCache(), this, preparationDispatcher = StandardTestDispatcher(testScheduler),
        )
        repository.activate("owner", "simple", true, CoordinationRegion.Alushta)
        advanceUntilIdle()
        assertTrue(repository.state.value.requiresAuthorization)
        assertEquals(0, session.clearCount)
        repository.activate("owner", "simple", true, CoordinationRegion.Alushta, startOffline = true)
        assertFalse(repository.state.value.requiresAuthorization)
    }

    @Test fun `cache scopes isolate owner and SimpleOne user while auth rotation keeps archive`() = runTest {
        val cache = ArchiveCache()
        val session = ArchiveSession()
        val api = ArchiveApi { _, _, _ -> GroupClosedRequestsPage(listOf(record("a")), 1, false) }
        val repository = CoordinationGroupClosedRequestsRepository(api, session, cache, this, preparationDispatcher = StandardTestDispatcher(testScheduler))
        repository.activate("owner", "simple", true, CoordinationRegion.Alushta)
        advanceUntilIdle()
        repository.activate("owner", "other-simple", true, CoordinationRegion.Alushta, true)
        assertTrue(repository.state.value.records.isEmpty())
        session.auth = "secret-new-key"
        repository.activate("owner", "simple", true, CoordinationRegion.Alushta, true)
        assertEquals("a", repository.state.value.records.single().id)
        repository.activate("other-owner", "simple", true, CoordinationRegion.Alushta, true)
        assertTrue(repository.state.value.records.isEmpty())
        assertEquals(6, cache.readKeys.distinct().size)
        assertTrue(cache.readKeys.all { it.matches(Regex("[0-9a-f]{64}")) })
    }

    @Test fun `old successful response is discarded after auth replacement`() = runTest {
        val response = CompletableDeferred<GroupClosedRequestsPage>()
        val session = ArchiveSession()
        val cache = ArchiveCache()
        val api = ArchiveApi { _, _, _ -> response.await() }
        val repository = CoordinationGroupClosedRequestsRepository(api, session, cache, this, preparationDispatcher = StandardTestDispatcher(testScheduler))
        repository.activate("owner", "simple", true, CoordinationRegion.Alushta)
        runCurrent()
        session.auth = "new-auth"
        response.complete(GroupClosedRequestsPage(listOf(record("stale")), 1, false))
        advanceUntilIdle()
        assertTrue(repository.state.value.records.isEmpty())
        assertTrue(cache.writes.isEmpty())
    }

    @Test fun `force full queued during incremental pass resets paging and reconciles deletions`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var pass = 0
        val api = ArchiveApi { _, _, newest ->
            when (pass) {
                0 -> GroupClosedRequestsPage(listOf(record("a"), record("removed")), 2, false)
                else -> {
                    if (newest) { gate.await(); pass = 2; GroupClosedRequestsPage(listOf(record("a")), 2, false) }
                    else GroupClosedRequestsPage(listOf(record("a")), 1, false)
                }
            }
        }
        val repository = CoordinationGroupClosedRequestsRepository(api, ArchiveSession(), ArchiveCache(), this, preparationDispatcher = StandardTestDispatcher(testScheduler))
        repository.activate("owner", "simple", true, CoordinationRegion.Alushta)
        advanceUntilIdle()
        pass = 1
        repository.refresh()
        runCurrent()
        repository.refresh(forceFull = true)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf(false, true, false), api.calls.map { it.third })
        assertEquals("a", repository.state.value.records.single().id)
        assertEquals(3L, repository.state.value.completedRevision)
    }

    @Test fun `new version on cached page prevents premature incremental stop`() = runTest {
        var changed = false
        val api = ArchiveApi { page, _, _ ->
            if (!changed) GroupClosedRequestsPage(listOf(record("a"), record("b")), 2, false)
            else if (page == 1) GroupClosedRequestsPage(listOf(record("a").copy(sysUpdatedAt = "new")), 2, true)
            else GroupClosedRequestsPage(listOf(record("b")), 2, false)
        }
        val repository = CoordinationGroupClosedRequestsRepository(api, ArchiveSession(), ArchiveCache(), this, preparationDispatcher = StandardTestDispatcher(testScheduler))
        repository.activate("owner", "simple", true, CoordinationRegion.Alushta)
        advanceUntilIdle()
        changed = true
        repository.refresh()
        advanceUntilIdle()
        assertEquals(listOf(1, 1, 2), api.calls.map { it.first })
        assertEquals("new", repository.state.value.records.first { it.id == "a" }.sysUpdatedAt)
    }
}

private fun record(id: String) = SimpleOneRequest(id, "REQ$id")

private class ArchiveCache : CoordinationGroupClosedRequestsCache {
    val values = mutableMapOf<String, GroupClosedRequestsSnapshot>()
    val writes = mutableListOf<GroupClosedRequestsSnapshot>()
    val readKeys = mutableListOf<String>()
    var beforeRead: suspend () -> Unit = {}
    override suspend fun read(scopeKey: String): GroupClosedRequestsSnapshot? {
        readKeys += scopeKey
        beforeRead()
        return values[scopeKey]
    }
    override suspend fun save(scopeKey: String, snapshot: GroupClosedRequestsSnapshot) {
        values[scopeKey] = snapshot
        writes += snapshot
    }
}

private class ArchiveSession : SimpleOneSessionStore {
    var auth = "secret-auth"
    var clearCount = 0
    override fun read(ownerUserId: String) = StoredSimpleOneSession(ownerUserId, auth, "engineer")
    override fun save(session: StoredSimpleOneSession) { auth = session.authKey }
    override fun clear(ownerUserId: String) { clearCount += 1; auth = "" }
    override fun readUsername(ownerUserId: String) = "engineer"
    override fun saveUsername(ownerUserId: String, username: String) = Unit
}

private class ArchiveApi(
    val fetch: suspend (Int, String, Boolean) -> GroupClosedRequestsPage,
) : SimpleOneApi {
    val calls = mutableListOf<Triple<Int, String, Boolean>>()
    val regions = mutableListOf<CoordinationRegion>()
    override suspend fun groupClosedRequestsPage(region: CoordinationRegion, page: Int, authKey: String, perPage: Int, newestFirst: Boolean): GroupClosedRequestsPage {
        calls += Triple(page, authKey, newestFirst)
        regions += region
        return fetch(page, authKey, newestFirst)
    }
    override suspend fun login(username: String, password: String) = "auth"
    override suspend fun currentUser(authKey: String) = SimpleOneUser("simple", "", "", "")
    override suspend fun activeRequests(userId: String, authKey: String) = emptyList<SimpleOneRequest>()
    override suspend fun closedRequests(userId: String, authKey: String) = emptyList<SimpleOneRequest>()
    override suspend fun exportClosedRequests(userId: String, authKey: String) = byteArrayOf()
    override suspend fun timeReportEntries(authKey: String) = emptyList<TimeReportEntry>()
    override suspend fun exportTimeReport(userId: String, authKey: String) = byteArrayOf()
    override suspend fun backpackItems(authKey: String) = emptyList<BackpackItem>()
    override suspend fun requestDetails(request: SimpleOneRequest, authKey: String) = request
    override suspend fun coordinationRequests(region: CoordinationRegion, authKey: String) = emptyList<SimpleOneRequest>()
    override suspend fun returnEquipmentRequests(userId: String, authKey: String) = emptyList<SimpleOneRequest>()
}
