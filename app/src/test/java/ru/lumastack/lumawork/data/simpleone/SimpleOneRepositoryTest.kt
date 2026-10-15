package ru.lumastack.lumawork.data.simpleone

import kotlinx.coroutines.runBlocking
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
}

private class FakeSimpleOneApi(
    private val error: SimpleOneFailure? = null,
    private val closed: List<SimpleOneRequest> = emptyList(),
    private val exportedXlsx: ByteArray? = null,
) : SimpleOneApi {
    var detailCalls = 0

    override suspend fun login(username: String, password: String): String = "new-auth"

    override suspend fun currentUser(authKey: String): SimpleOneUser {
        error?.let { throw it }
        return SimpleOneUser("simple-user", "engineer", "Иван", "Инженеров")
    }

    override suspend fun activeRequests(userId: String, authKey: String): List<SimpleOneRequest> {
        error?.let { throw it }
        return listOf(request())
    }

    override suspend fun closedRequests(userId: String, authKey: String): List<SimpleOneRequest> {
        error?.let { throw it }
        return closed
    }

    override suspend fun exportClosedRequests(userId: String, authKey: String): ByteArray {
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
