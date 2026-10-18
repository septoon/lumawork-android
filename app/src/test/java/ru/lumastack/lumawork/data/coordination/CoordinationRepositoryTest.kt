package ru.lumastack.lumawork.data.coordination

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import androidx.lifecycle.viewModelScope
import ru.lumastack.lumawork.ui.coordination.CoordinationViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.lumastack.lumawork.data.simpleone.SimpleOneApi
import ru.lumastack.lumawork.data.simpleone.SimpleOneFailure
import ru.lumastack.lumawork.data.simpleone.SimpleOneSessionStore
import ru.lumastack.lumawork.data.simpleone.StoredSimpleOneSession
import ru.lumastack.lumawork.domain.coordination.CoordinationRegion
import ru.lumastack.lumawork.domain.coordination.CoordinationSnapshot
import ru.lumastack.lumawork.domain.simpleone.SimpleOneRequest
import ru.lumastack.lumawork.domain.simpleone.SimpleOneUser
import ru.lumastack.lumawork.domain.timereport.TimeReportEntry
import ru.lumastack.lumawork.domain.backpack.BackpackItem

class CoordinationRepositoryTest {
    @Test
    fun `region refresh is scoped by LumaWork and SimpleOne users`() = runBlocking {
        val session = FakeSessionStore().apply {
            save(StoredSimpleOneSession("owner-1", "auth", "engineer"))
        }
        val cache = FakeCoordinationCache()
        val repository = CoordinationRepository(FakeCoordinationApi(), session, cache) { 1234L }

        val snapshot = repository.refreshRegion("owner-1", "simple-1", CoordinationRegion.Alushta)

        assertEquals("simple-1", snapshot.simpleOneUserId)
        assertEquals("REQ1", snapshot.regions[CoordinationRegion.Alushta.name]?.requests?.single()?.number)
        assertEquals(1234L, snapshot.regions[CoordinationRegion.Alushta.name]?.updatedAtEpochMillis)
        assertNull(repository.read("owner-1", "other-simple-user"))
        assertNull(cache.values["owner-2"])
    }

    @Test
    fun `refresh preserves cached detail fields missing from list payload`() = runBlocking {
        val session = FakeSessionStore().apply {
            save(StoredSimpleOneSession("owner-1", "auth", "engineer"))
        }
        val cache = FakeCoordinationCache().apply {
            values["owner-1"] = CoordinationSnapshot(
                simpleOneUserId = "simple-1",
                sessionFingerprint = coordinationSessionFingerprint("auth", "simple-1"),
                regions = mapOf(
                    CoordinationRegion.Alushta.name to ru.lumastack.lumawork.domain.coordination.CoordinationRequestGroup(
                        requests = listOf(request().copy(waitingReason = "Ждём клиента", resolution = "Сохранено")),
                    ),
                ),
            )
        }
        val repository = CoordinationRepository(FakeCoordinationApi(), session, cache)

        val snapshot = repository.refreshRegion("owner-1", "simple-1", CoordinationRegion.Alushta)

        val refreshed = snapshot.regions[CoordinationRegion.Alushta.name]!!.requests.single()
        assertEquals("Ждём клиента", refreshed.waitingReason)
        assertEquals("Сохранено", refreshed.resolution)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `offline coordination shows local region and details without starting network`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val sessions = FakeSessionStore().apply { save(StoredSimpleOneSession("owner", "auth", "user")) }
        var listed = 0
        val api = FakeCoordinationApi(onRegion = { listed++; listOf(request()) })
        val snapshot = CoordinationSnapshot("user", regions = mapOf("Yalta" to ru.lumastack.lumawork.domain.coordination.CoordinationRequestGroup(listOf(request()), 10L)),
            selectedRegion = CoordinationRegion.Yalta, sessionFingerprint = coordinationSessionFingerprint("auth", "user"), revision = 7)
        val cache = FakeCoordinationCache().apply { values["owner"] = snapshot }
        val simple = ru.lumastack.lumawork.data.simpleone.SimpleOneRepository(api, sessions, object : ru.lumastack.lumawork.data.simpleone.SimpleOneCache {
            override suspend fun read(ownerUserId: String): ru.lumastack.lumawork.domain.simpleone.SimpleOneSnapshot? = null
            override suspend fun save(ownerUserId: String, snapshot: ru.lumastack.lumawork.domain.simpleone.SimpleOneSnapshot) = Unit
        })
        val model = CoordinationViewModel(CoordinationRepository(api, sessions, cache), simple, "owner", true)
        try {
            model.activate("user", true)
            withTimeout(10_000) { model.state.first { it.snapshot.revision == 7L } }
            assertEquals(CoordinationRegion.Yalta, model.state.value.region)
            model.openRequest(request()); model.refresh()
            assertEquals(request(), model.state.value.selectedRequest)
            assertEquals(false, model.state.value.isDetailLoading)
            assertEquals(0, listed); assertEquals(0, api.detailCalls)
        } finally { model.viewModelScope.cancel(); Dispatchers.resetMain() }
    }
    @Test fun `new list version drops stale detail fields and assignments`() = runBlocking {
        val sessions = FakeSessionStore().apply { save(StoredSimpleOneSession("owner", "auth", "user")) }
        var fresh = request().copy(tableFields = mapOf("Оборудование POS" to "OLD-SERIAL"))
        val repo = CoordinationRepository(FakeCoordinationApi(onRegion = { listOf(fresh) }), sessions, FakeCoordinationCache())
        repo.refreshRegion("owner", "user", CoordinationRegion.Alushta)
        fresh = request().copy(sysUpdatedAt = "v2", assignedUser = "", assignedUserId = "")
        val updated = repo.refreshRegion("owner", "user", CoordinationRegion.Alushta).regions["Alushta"]!!.requests.single()
        assertEquals("", updated.assignedUser)
        assertEquals(emptyMap<String,String>(), updated.tableFields)
    }
    @Test fun `late detailed response cannot replace a newer list version`() = runBlocking {
        val sessions = FakeSessionStore().apply { save(StoredSimpleOneSession("owner", "auth", "user")) }
        val repo = CoordinationRepository(FakeCoordinationApi(onRegion = { listOf(request().copy(sysUpdatedAt = "v2")) }), sessions, FakeCoordinationCache())
        repo.refreshRegion("owner", "user", CoordinationRegion.Alushta)
        repo.replaceRequest("owner", "user", request().copy(sysUpdatedAt = "v1", resolution = "stale"), expectedVersion = "v1")
        val current = repo.read("owner", "user")!!.regions["Alushta"]!!.requests.single()
        assertEquals("v2", current.sysUpdatedAt)
        assertEquals("", current.resolution)
    }
    @Test fun `parallel region refreshes merge latest cache instead of losing a sibling region`() = runBlocking {
        val sessions = FakeSessionStore().apply { save(StoredSimpleOneSession("owner", "auth", "user")) }
        val first = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val api = FakeCoordinationApi(onRegion = { region ->
            if (region == CoordinationRegion.Alushta) { first.complete(Unit); release.await() }
            listOf(request().copy(sysId = region.name))
        })
        val repository = CoordinationRepository(api, sessions, FakeCoordinationCache())
        val pending = async { repository.refreshRegion("owner", "user", CoordinationRegion.Alushta) }
        first.await()
        repository.refreshRegion("owner", "user", CoordinationRegion.Yalta)
        release.complete(Unit); pending.await()
        assertEquals(setOf("Alushta", "Yalta"), repository.read("owner", "user")!!.regions.keys)
    }
    @Test fun `late unauthorized response never clears a newer login`() = runBlocking {
        val sessions = FakeSessionStore().apply { save(StoredSimpleOneSession("owner", "old", "user")) }
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val api = FakeCoordinationApi(onRegion = { entered.complete(Unit); release.await(); throw SimpleOneFailure.Unauthorized })
        val repository = CoordinationRepository(api, sessions, FakeCoordinationCache())
        val pending = async { runCatching { repository.refreshRegion("owner", "user", CoordinationRegion.Alushta) } }
        entered.await(); sessions.save(StoredSimpleOneSession("owner", "new", "user")); release.complete(Unit); pending.await()
        assertEquals("new", sessions.read("owner")?.authKey)
    }
    @Test
    fun `unauthorized refresh clears only current SimpleOne key`() = runBlocking {
        val session = FakeSessionStore().apply {
            save(StoredSimpleOneSession("owner-1", "expired", "engineer"))
        }
        val repository = CoordinationRepository(
            FakeCoordinationApi(error = SimpleOneFailure.Unauthorized),
            session,
            FakeCoordinationCache(),
        )

        runCatching { repository.refreshRegion("owner-1", "simple-1", CoordinationRegion.Alushta) }

        assertNull(session.read("owner-1"))
    }
}

private class FakeCoordinationApi(
    private val error: SimpleOneFailure? = null,
    private val onRegion: (suspend (CoordinationRegion) -> List<SimpleOneRequest>)? = null,
) : SimpleOneApi {
    var detailCalls = 0
    override suspend fun login(username: String, password: String): String = "auth"
    override suspend fun currentUser(authKey: String): SimpleOneUser = SimpleOneUser("simple-1", "", "", "")
    override suspend fun activeRequests(userId: String, authKey: String): List<SimpleOneRequest> = emptyList()
    override suspend fun closedRequests(userId: String, authKey: String): List<SimpleOneRequest> = emptyList()
    override suspend fun exportClosedRequests(userId: String, authKey: String): ByteArray = byteArrayOf()
    override suspend fun timeReportEntries(authKey: String): List<TimeReportEntry> = emptyList()
    override suspend fun exportTimeReport(userId: String, authKey: String): ByteArray = byteArrayOf()
    override suspend fun backpackItems(authKey: String): List<BackpackItem> = emptyList()
    override suspend fun requestDetails(request: SimpleOneRequest, authKey: String): SimpleOneRequest { detailCalls++; return request }
    override suspend fun coordinationRequests(
        region: CoordinationRegion,
        authKey: String,
    ): List<SimpleOneRequest> {
        error?.let { throw it }
        return onRegion?.invoke(region) ?: listOf(request())
    }
    override suspend fun returnEquipmentRequests(
        userId: String,
        authKey: String,
    ): List<SimpleOneRequest> = emptyList()
}

private class FakeSessionStore : SimpleOneSessionStore {
    private var session: StoredSimpleOneSession? = null
    override fun read(ownerUserId: String): StoredSimpleOneSession? = session?.takeIf { it.ownerUserId == ownerUserId }
    override fun save(session: StoredSimpleOneSession) { this.session = session }
    override fun clear(ownerUserId: String) { if (session?.ownerUserId == ownerUserId) session = null }
    override fun readUsername(ownerUserId: String): String = session?.username.orEmpty()
    override fun saveUsername(ownerUserId: String, username: String) = Unit
}

private class FakeCoordinationCache : CoordinationCache {
    val values = mutableMapOf<String, CoordinationSnapshot>()
    override suspend fun read(ownerUserId: String): CoordinationSnapshot? = values[ownerUserId]
    override suspend fun save(ownerUserId: String, snapshot: CoordinationSnapshot) { values[ownerUserId] = snapshot }
}

private fun request() = SimpleOneRequest(sysId = "1", number = "REQ1", assignedUser = "Иван Инженеров", sysUpdatedAt = "v1")
