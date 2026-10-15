package ru.lumastack.lumawork.data.timereport

import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.lumastack.lumawork.data.simpleone.SimpleOneApi
import ru.lumastack.lumawork.data.simpleone.SimpleOneFailure
import ru.lumastack.lumawork.data.simpleone.SimpleOneSessionStore
import ru.lumastack.lumawork.data.simpleone.StoredSimpleOneSession
import ru.lumastack.lumawork.domain.coordination.CoordinationRegion
import ru.lumastack.lumawork.domain.simpleone.SimpleOneRequest
import ru.lumastack.lumawork.domain.simpleone.SimpleOneUser
import ru.lumastack.lumawork.domain.timereport.TimeReportEntry
import ru.lumastack.lumawork.domain.timereport.TimeReportSnapshot
import ru.lumastack.lumawork.domain.timereport.TimeReportSource
import ru.lumastack.lumawork.domain.backpack.BackpackItem

class TimeReportRepositoryTest {
    @Test
    fun `refresh prefers xlsx merges archive and scopes cache to SimpleOne user`() = runBlocking {
        val xlsx = ru.lumastack.lumawork.data.simpleone.makeSimpleOneXlsx(
            headers = listOf("Задача", "Когда создано", "Время работ"),
            rows = listOf(listOf("NEW", "12.08.2026 10:00", "60")),
        )
        val cache = FakeTimeReportCache().apply {
            value = TimeReportSnapshot(
                simpleOneUserId = "simple-user",
                entries = listOf(entry("OLD", "2026-08-11 09:00:00")),
            )
        }
        val repository = TimeReportRepository(
            api = FakeTimeReportApi(exported = xlsx),
            sessionStore = authorizedSessionStore(),
            cache = cache,
            nowEpochMillis = { 1234L },
        )

        val result = repository.refresh("owner", "simple-user")

        assertEquals(listOf("NEW", "OLD"), result.entries.map(TimeReportEntry::activity))
        assertEquals(TimeReportSource.Xlsx, result.source)
        assertEquals(1234L, result.updatedAtEpochMillis)
        assertEquals(result, repository.read("owner", "simple-user"))
        assertNull(repository.read("owner", "other-user"))
    }

    @Test
    fun `refresh falls back to list API when export is unavailable`() = runBlocking {
        val repository = TimeReportRepository(
            api = FakeTimeReportApi(listEntries = listOf(entry("LIST", "2026-08-12 10:00:00"))),
            sessionStore = authorizedSessionStore(),
            cache = FakeTimeReportCache(),
        )

        val result = repository.refresh("owner", "simple-user")

        assertEquals("LIST", result.entries.single().activity)
        assertEquals(TimeReportSource.ListApi, result.source)
    }

    @Test
    fun `range cleanup is inclusive and never touches SimpleOne`() = runBlocking {
        val cache = FakeTimeReportCache().apply {
            value = TimeReportSnapshot(
                simpleOneUserId = "simple-user",
                entries = listOf(
                    entry("A", "2026-08-10 10:00:00"),
                    entry("B", "2026-08-11 10:00:00"),
                    entry("C", "2026-08-12 10:00:00"),
                ),
            )
        }
        val api = FakeTimeReportApi()
        val repository = TimeReportRepository(api, authorizedSessionStore(), cache)

        val deleted = repository.deleteRange(
            "owner",
            "simple-user",
            LocalDate.of(2026, 8, 11),
            LocalDate.of(2026, 8, 12),
        )

        assertEquals(2, deleted)
        assertEquals(listOf("A"), cache.value?.entries?.map(TimeReportEntry::activity))
        assertEquals(0, api.networkCalls)
    }
}

private class FakeTimeReportApi(
    private val exported: ByteArray? = null,
    private val listEntries: List<TimeReportEntry> = emptyList(),
) : SimpleOneApi {
    var networkCalls = 0

    override suspend fun login(username: String, password: String): String = "auth"
    override suspend fun currentUser(authKey: String): SimpleOneUser = SimpleOneUser("simple-user", "user", "", "")
    override suspend fun activeRequests(userId: String, authKey: String): List<SimpleOneRequest> = emptyList()
    override suspend fun closedRequests(userId: String, authKey: String): List<SimpleOneRequest> = emptyList()
    override suspend fun exportClosedRequests(userId: String, authKey: String): ByteArray = byteArrayOf()
    override suspend fun requestDetails(request: SimpleOneRequest, authKey: String): SimpleOneRequest = request
    override suspend fun coordinationRequests(
        region: CoordinationRegion,
        authKey: String,
    ): List<SimpleOneRequest> = emptyList()
    override suspend fun returnEquipmentRequests(
        userId: String,
        authKey: String,
    ): List<SimpleOneRequest> = emptyList()

    override suspend fun timeReportEntries(authKey: String): List<TimeReportEntry> {
        networkCalls += 1
        return listEntries
    }

    override suspend fun exportTimeReport(userId: String, authKey: String): ByteArray {
        networkCalls += 1
        return exported ?: throw SimpleOneFailure.Http(501, "XLSX недоступен")
    }

    override suspend fun backpackItems(authKey: String): List<BackpackItem> = emptyList()
}

private class FakeTimeReportCache : TimeReportCache {
    var value: TimeReportSnapshot? = null
    override suspend fun read(ownerUserId: String): TimeReportSnapshot? = value
    override suspend fun save(ownerUserId: String, snapshot: TimeReportSnapshot) {
        value = snapshot
    }
    override suspend fun delete(ownerUserId: String) {
        value = null
    }
}

private fun authorizedSessionStore(): SimpleOneSessionStore = object : SimpleOneSessionStore {
    private var session: StoredSimpleOneSession? = StoredSimpleOneSession("owner", "auth", "engineer")
    override fun read(ownerUserId: String): StoredSimpleOneSession? = session?.takeIf { it.ownerUserId == ownerUserId }
    override fun save(session: StoredSimpleOneSession) {
        this.session = session
    }
    override fun clear(ownerUserId: String) {
        if (session?.ownerUserId == ownerUserId) session = null
    }
    override fun readUsername(ownerUserId: String): String = "engineer"
    override fun saveUsername(ownerUserId: String, username: String) = Unit
}

private fun entry(activity: String, createdAt: String) = TimeReportEntry(
    activity = activity,
    createdAt = createdAt,
    executor = "Инженер",
)
