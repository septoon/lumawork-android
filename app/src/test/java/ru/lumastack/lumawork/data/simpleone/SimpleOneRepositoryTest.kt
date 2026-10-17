package ru.lumastack.lumawork.data.simpleone

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import ru.lumastack.lumawork.ui.simpleone.SimpleOneViewModel
import ru.lumastack.lumawork.domain.requests.ClosedRequestsPeriod
import ru.lumastack.lumawork.domain.requests.closedEffectiveTime
import ru.lumastack.lumawork.domain.requests.parseClosedRequestsDate
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.lumastack.lumawork.domain.simpleone.ClosedArchiveSource
import ru.lumastack.lumawork.domain.coordination.CoordinationRegion
import ru.lumastack.lumawork.domain.simpleone.ClosedSimpleOneArchive
import ru.lumastack.lumawork.domain.simpleone.SimpleOneRequest
import ru.lumastack.lumawork.domain.simpleone.SimpleOneSnapshot
import ru.lumastack.lumawork.domain.simpleone.SimpleOneUser
import ru.lumastack.lumawork.domain.timereport.TimeReportEntry
import ru.lumastack.lumawork.domain.backpack.BackpackItem

class SimpleOneRepositoryTest {
    @Test
    fun `refresh keeps cache scoped to LumaWork user`() = runBlocking {
        val sessionStore = FakeSimpleOneSessionStore().apply {
            save(StoredSimpleOneSession("owner-1", "auth", "engineer"))
        }
        val cache = FakeSimpleOneCache()
        val api = FakeSimpleOneApi()
        val repository = SimpleOneRepository(api, sessionStore, cache) { 1234L }

        val snapshot = repository.refresh("owner-1", cached = null)

        assertEquals(1, snapshot.activeRequests.size)
        assertEquals(1234L, snapshot.updatedAtEpochMillis)
        assertEquals(snapshot, cache.values["owner-1"])
        assertNull(cache.values["owner-2"])
    }

    @Test
    fun `unauthorized refresh removes key but preserves cached snapshot`() = runBlocking {
        val sessionStore = FakeSimpleOneSessionStore().apply {
            save(StoredSimpleOneSession("owner-1", "expired", "engineer"))
        }
        val cached = SimpleOneSnapshot(activeRequests = listOf(request()))
        val cache = FakeSimpleOneCache().apply { values["owner-1"] = cached }
        val repository = SimpleOneRepository(
            FakeSimpleOneApi(error = SimpleOneFailure.Unauthorized),
            sessionStore,
            cache,
        )

        runCatching { repository.refresh("owner-1", cached) }

        assertNull(sessionStore.read("owner-1"))
        assertEquals(cached, cache.values["owner-1"])
    }

    @Test
    fun `successful sign in stores auth key and remembered username`() = runBlocking {
        val sessionStore = FakeSimpleOneSessionStore()
        val repository = SimpleOneRepository(
            FakeSimpleOneApi(),
            sessionStore,
            FakeSimpleOneCache(),
        )

        repository.signIn("owner-1", " engineer ", " password ", cached = null)

        assertEquals("new-auth", sessionStore.read("owner-1")?.authKey)
        assertEquals("engineer", sessionStore.readUsername("owner-1"))
    }

    @Test
    fun `fresh detail cache avoids SimpleOne request`() = runBlocking {
        val api = FakeSimpleOneApi()
        val supplemental = FakeSimpleOneSupplementalCache().apply {
            details["owner-1" to "request-1"] = SimpleOneDetailCacheEntry(
                request().copy(description = "Сохранённые детали"),
                cachedAtEpochMillis = 1_000L,
            )
        }
        val repository = SimpleOneRepository(
            api = api,
            sessionStore = FakeSimpleOneSessionStore(),
            cache = FakeSimpleOneCache(),
            supplementalCache = supplemental,
            nowEpochMillis = { 2_000L },
        )

        val result = repository.requestDetails("owner-1", request())

        assertEquals("Сохранённые детали", result.description)
        assertEquals(0, api.detailCalls)
    }

    @Test
    fun `stale detail cache is fallback for transient error`() = runBlocking {
        val sessionStore = FakeSimpleOneSessionStore().apply {
            save(StoredSimpleOneSession("owner-1", "auth", "engineer"))
        }
        val supplemental = FakeSimpleOneSupplementalCache().apply {
            details["owner-1" to "request-1"] = SimpleOneDetailCacheEntry(
                request().copy(description = "Старая копия"),
                cachedAtEpochMillis = 0L,
            )
        }
        val repository = SimpleOneRepository(
            api = FakeSimpleOneApi(error = SimpleOneFailure.Http(500, "Ошибка")),
            sessionStore = sessionStore,
            cache = FakeSimpleOneCache(),
            supplementalCache = supplemental,
            nowEpochMillis = { 86_400_001L },
        )

        val result = repository.requestDetails("owner-1", request())

        assertEquals("Старая копия", result.description)
    }

    @Test
    fun `closed archive is cached independently from active snapshot`() = runBlocking {
        val sessionStore = FakeSimpleOneSessionStore().apply {
            save(StoredSimpleOneSession("owner-1", "auth", "engineer"))
        }
        val supplemental = FakeSimpleOneSupplementalCache()
        val repository = SimpleOneRepository(
            api = FakeSimpleOneApi(closed = listOf(request().copy(number = "CLOSED1"))),
            sessionStore = sessionStore,
            cache = FakeSimpleOneCache(),
            supplementalCache = supplemental,
            nowEpochMillis = { 5_000L },
        )
        val active = SimpleOneSnapshot(currentUser = SimpleOneUser("simple-user", "engineer", "", ""))

        val archive = repository.refreshClosedArchive("owner-1", active)

        assertEquals("CLOSED1", archive.requests.single().number)
        assertEquals(5_000L, archive.updatedAtEpochMillis)
        assertEquals(archive, supplemental.archives["owner-1"])
        assertEquals(ClosedArchiveSource.ListApi, archive.source)
    }

    @Test
    fun `closed archive prefers full XLSX export`() = runBlocking {
        val sessionStore = FakeSimpleOneSessionStore().apply {
            save(StoredSimpleOneSession("owner-1", "auth", "engineer"))
        }
        val xlsx = makeSimpleOneXlsx(
            headers = listOf("Номер заявки", "Статус", "Комментарий инженера"),
            rows = listOf(listOf("XLSX-1", "Закрыта", "Полные данные")),
        )
        val repository = SimpleOneRepository(
            api = FakeSimpleOneApi(
                closed = listOf(request().copy(number = "LIST-1")),
                exportedXlsx = xlsx,
            ),
            sessionStore = sessionStore,
            cache = FakeSimpleOneCache(),
            supplementalCache = FakeSimpleOneSupplementalCache(),
        )
        val active = SimpleOneSnapshot(currentUser = SimpleOneUser("simple-user", "engineer", "", ""))

        val archive = repository.refreshClosedArchive("owner-1", active)

        assertEquals(ClosedArchiveSource.Xlsx, archive.source)
        assertEquals("XLSX-1", archive.requests.single().number)
        assertEquals("Полные данные", archive.requests.single().engineerComment)
    }
    @Test fun `late archive response after signout cannot repopulate cache`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val api = FakeSimpleOneApi(beforeExport = { started.complete(Unit); release.await() })
        val store = FakeSimpleOneSessionStore().apply { save(StoredSimpleOneSession("owner", "key", "engineer")) }
        val cache = FakeSimpleOneSupplementalCache()
        val repository = SimpleOneRepository(api, store, FakeSimpleOneCache(), cache)
        val pending = async { runCatching { repository.refreshClosedArchive("owner") } }
        started.await()
        repository.signOut("owner")
        release.complete(Unit)
        assertTrue(pending.await().exceptionOrNull() is kotlinx.coroutines.CancellationException)
        assertNull(cache.archives["owner"])
    }

    @Test fun `late login after signout cannot restore credentials`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val api = FakeSimpleOneApi(beforeLogin = { started.complete(Unit); release.await() })
        val store = FakeSimpleOneSessionStore()
        val repository = SimpleOneRepository(api, store, FakeSimpleOneCache())
        val pending = async { runCatching { repository.authenticate("owner", "engineer", "password") } }
        started.await()
        repository.signOut("owner")
        release.complete(Unit)
        assertTrue(pending.await().exceptionOrNull() is kotlinx.coroutines.CancellationException)
        assertNull(store.read("owner"))
    }

}

private class FakeSimpleOneApi(
    private val error: SimpleOneFailure? = null,
    private val closed: List<SimpleOneRequest> = emptyList(),
    private val exportedXlsx: ByteArray? = null,
    private val beforeExport: suspend () -> Unit = {},
    private val beforeLogin: suspend () -> Unit = {},
) : SimpleOneApi {
    var detailCalls = 0
    var currentUserCalls = 0
    var activeCalls = 0
    var archiveCalls = 0
    var exportCalls = 0

    override suspend fun login(username: String, password: String): String { beforeLogin(); return "new-auth" }

    override suspend fun currentUser(authKey: String): SimpleOneUser {
        currentUserCalls++
        error?.let { throw it }
        return SimpleOneUser("simple-user", "engineer", "Иван", "Инженеров")
    }

    override suspend fun activeRequests(userId: String, authKey: String): List<SimpleOneRequest> {
        activeCalls++
        error?.let { throw it }
        return listOf(request())
    }

    override suspend fun closedRequests(userId: String, authKey: String): List<SimpleOneRequest> {
        archiveCalls++
        error?.let { throw it }
        return closed
    }

    override suspend fun exportClosedRequests(userId: String, authKey: String): ByteArray {
        exportCalls++
        beforeExport()
        error?.let { throw it }
        return exportedXlsx ?: throw SimpleOneFailure.Http(501, "XLSX недоступен")
    }

    override suspend fun timeReportEntries(authKey: String): List<TimeReportEntry> = emptyList()

    override suspend fun exportTimeReport(userId: String, authKey: String): ByteArray =
        throw SimpleOneFailure.Http(501, "XLSX недоступен")

    override suspend fun backpackItems(authKey: String): List<BackpackItem> = emptyList()

    override suspend fun requestDetails(request: SimpleOneRequest, authKey: String): SimpleOneRequest {
        detailCalls += 1
        error?.let { throw it }
        return request
    }

    override suspend fun coordinationRequests(
        region: CoordinationRegion,
        authKey: String,
    ): List<SimpleOneRequest> = emptyList()

    override suspend fun returnEquipmentRequests(
        userId: String,
        authKey: String,
    ): List<SimpleOneRequest> = emptyList()
}

private class FakeSimpleOneSessionStore : SimpleOneSessionStore {
    private var value: StoredSimpleOneSession? = null
    private val usernames = mutableMapOf<String, String>()

    override fun read(ownerUserId: String): StoredSimpleOneSession? = value?.takeIf { it.ownerUserId == ownerUserId }

    override fun save(session: StoredSimpleOneSession) {
        value = session
        saveUsername(session.ownerUserId, session.username)
    }

    override fun clear(ownerUserId: String) {
        if (value?.ownerUserId == ownerUserId) value = null
    }

    override fun readUsername(ownerUserId: String): String = usernames[ownerUserId].orEmpty()

    override fun saveUsername(ownerUserId: String, username: String) {
        usernames[ownerUserId] = username
    }
}

private class FakeSimpleOneCache : SimpleOneCache {
    val values = mutableMapOf<String, SimpleOneSnapshot>()

    override suspend fun read(ownerUserId: String): SimpleOneSnapshot? = values[ownerUserId]

    override suspend fun save(ownerUserId: String, snapshot: SimpleOneSnapshot) {
        values[ownerUserId] = snapshot
    }
}

private class FakeSimpleOneSupplementalCache : SimpleOneSupplementalCache {
    val archives = mutableMapOf<String, ClosedSimpleOneArchive>()
    val details = mutableMapOf<Pair<String, String>, SimpleOneDetailCacheEntry>()

    override suspend fun readClosedArchive(ownerUserId: String): ClosedSimpleOneArchive? = archives[ownerUserId]

    override suspend fun saveClosedArchive(ownerUserId: String, archive: ClosedSimpleOneArchive) {
        archives[ownerUserId] = archive
    }

    override suspend fun readDetail(ownerUserId: String, requestId: String): SimpleOneDetailCacheEntry? =
        details[ownerUserId to requestId]

    override suspend fun saveDetail(ownerUserId: String, entry: SimpleOneDetailCacheEntry) {
        details[ownerUserId to entry.request.id] = entry
    }
}

private fun request() = SimpleOneRequest(
    sysId = "request-1",
    number = "REQ1",
    deadline = "2026-08-12 15:30:00",
)


@OptIn(ExperimentalCoroutinesApi::class)
class RequestsBootstrapTest {
    private suspend fun settled(model: SimpleOneViewModel) = withContext(Dispatchers.Default) {
        withTimeout(5_000) { model.state.first { !it.isInitializing && !it.isLoading && !it.isClosedLoading } }
    }
    private fun session() = FakeSimpleOneSessionStore().apply { save(StoredSimpleOneSession("owner", "key", "engineer")) }

    @Test fun `first entry with remembered session only reads local cache`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val api = FakeSimpleOneApi()
            val model = SimpleOneViewModel(SimpleOneRepository(api, session(), FakeSimpleOneCache()), "owner", false)
            settled(model)
            assertFalse(model.state.value.isInitializing)
            assertFalse(model.state.value.initialArchiveReady)
            assertEquals(0, api.currentUserCalls)
            assertEquals(0, api.activeCalls)
            assertEquals(0, api.archiveCalls)
            assertEquals(0, api.exportCalls)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun `only explicit click downloads archive then unlocks and refreshes active`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val api = FakeSimpleOneApi(closed = listOf(request()))
            val model = SimpleOneViewModel(SimpleOneRepository(api, session(), FakeSimpleOneCache(), FakeSimpleOneSupplementalCache()), "owner", false)
            settled(model)
            model.initialLoad(); model.initialLoad()
            assertTrue(model.state.value.isClosedLoading)
            settled(model)
            assertEquals(1, api.exportCalls)
            assertEquals(1, api.archiveCalls)
            assertEquals(1, api.activeCalls)
            assertTrue(model.state.value.initialArchiveReady)
            assertEquals(1, model.state.value.closedArchive.requests.size)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun `login starts only after button and archive failure keeps the gate`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val api = FakeSimpleOneApi(error = SimpleOneFailure.Http(500, "Unavailable"))
            val model = SimpleOneViewModel(SimpleOneRepository(api, FakeSimpleOneSessionStore(), FakeSimpleOneCache()), "owner", false)
            settled(model)
            assertFalse(model.state.value.showConnection)
            model.initialLoad()
            assertTrue(model.state.value.showConnection)
            model.updateUsername("engineer"); model.updatePassword("password"); model.signIn()
            settled(model)
            assertFalse(model.state.value.initialArchiveReady)
            assertFalse(model.state.value.isLoading)
            assertFalse(model.state.value.isClosedLoading)
            assertEquals(0, api.activeCalls)
            assertTrue(model.state.value.errorMessage != null)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun `trusted complete empty archive unlocks repeat entry offline without network`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val api = FakeSimpleOneApi()
            val cache = FakeSimpleOneSupplementalCache().apply { archives["owner"] = ClosedSimpleOneArchive(isComplete = true, queryRevision = 2) }
            val model = SimpleOneViewModel(SimpleOneRepository(api, session(), FakeSimpleOneCache(), cache), "owner", true)
            settled(model)
            assertTrue(model.state.value.initialArchiveReady)
            model.initialLoad(); model.refreshClosedRequests()
            settled(model)
            assertEquals(0, api.activeCalls + api.exportCalls + api.currentUserCalls)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun `old capped archive is not accepted as a complete baseline`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val api = FakeSimpleOneApi()
            val cache = FakeSimpleOneSupplementalCache().apply { archives["owner"] = ClosedSimpleOneArchive(listOf(request()), isComplete = true) }
            val model = SimpleOneViewModel(SimpleOneRepository(api, session(), FakeSimpleOneCache(), cache), "owner", false)
            settled(model)
            assertFalse(model.state.value.initialArchiveReady)
            assertEquals(0, api.activeCalls + api.exportCalls + api.currentUserCalls)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun `local import export and inclusive cleanup preserve undated requests and never call API`() = runBlocking {
        val api = FakeSimpleOneApi()
        val repo = SimpleOneRepository(api, FakeSimpleOneSessionStore(), FakeSimpleOneCache(), FakeSimpleOneSupplementalCache())
        val records = listOf(request().copy(number = "OLD", resolvedAt = "12.10.2026 12:00"),
            request().copy(sysId = "2", number = "KEEP", resolvedAt = "17.10.2026 12:00"),
            request().copy(sysId = "3", number = "UNDATED", resolvedAt = ""))
        val imported = repo.importClosedArchive("owner", ClosedRequestsXlsxExport.create(records))
        assertTrue(imported.isReady)
        val roundtrip = SimpleOneXlsxParser.parse(repo.exportClosedArchive("owner"))
        assertEquals(records.map { it.number }.toSet(), roundtrip.map { it.number }.toSet())
        val period = ClosedRequestsPeriod(LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 12))
        val cleaned = repo.cleanupClosedArchive("owner") { parseClosedRequestsDate(it.closedEffectiveTime())?.toLocalDate()?.let { day -> day in period } == true }
        assertEquals(listOf("KEEP", "UNDATED"), cleaned.requests.map { it.number })
        assertTrue(repo.cleanupClosedArchive("owner") { true }.isReady)
        assertEquals(0, api.activeCalls + api.exportCalls + api.currentUserCalls + api.archiveCalls)
        assertNull(repo.readClosedArchive("other-owner"))
    }
}
