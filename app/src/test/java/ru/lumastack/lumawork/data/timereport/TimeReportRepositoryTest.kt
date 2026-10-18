package ru.lumastack.lumawork.data.timereport

import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.advanceUntilIdle
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
    fun `refresh prefers list API merges archive and scopes cache to SimpleOne user`() = runBlocking {
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
            api = FakeTimeReportApi(exported = xlsx, listEntries = listOf(entry("NEW", "2026-08-12 10:00:00"))),
            sessionStore = authorizedSessionStore(),
            cache = cache,
            nowEpochMillis = { 1234L },
        )

        val result = repository.refresh("owner", "simple-user")

        assertEquals(listOf("NEW", "OLD"), result.entries.map(TimeReportEntry::activity))
        assertEquals(TimeReportSource.ListApi, result.source)
        assertEquals(1234L, result.updatedAtEpochMillis)
        assertEquals(result, repository.read("owner", "simple-user"))
        assertNull(repository.read("owner", "other-user"))
    }

    @Test
    fun `refresh reads list without waiting for XLSX`() = runBlocking {
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
    fun `refresh keeps every row beyond ten thousand`() = runBlocking {
        val entries = (1..10_050).map { entry("ROW-$it", "2026-08-12 10:00:00") }
        val repository = TimeReportRepository(FakeTimeReportApi(listEntries = entries), authorizedSessionStore(), FakeTimeReportCache())
        assertEquals(10_050, repository.refresh("owner", "simple-user").entries.size)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `entering screen restores cache without making network requests`() = kotlinx.coroutines.test.runTest {
        kotlinx.coroutines.Dispatchers.setMain(kotlinx.coroutines.test.StandardTestDispatcher(testScheduler))
        try {
            val api = FakeTimeReportApi(listEntries = listOf(entry("NEW", "2026-08-12 10:00:00")))
            val cached = TimeReportSnapshot(simpleOneUserId = "simple-user", entries = listOf(entry("OLD", "2026-08-11 09:00:00")))
            val cache = FakeTimeReportCache().apply { value = cached }
            val model = ru.lumastack.lumawork.ui.timereport.TimeReportViewModel(
                TimeReportRepository(api, authorizedSessionStore(), cache), "owner", false)
            model.activate("simple-user", true)
            model.state.first { !it.isInitializing }
            assertEquals(0, api.networkCalls)
            assertEquals(cached, model.state.value.snapshot)
        } finally { kotlinx.coroutines.Dispatchers.resetMain() }
    }

    @Test fun `list failure uses XLSX fallback and exported archive preserves work date and record id`() = runBlocking {
        val xlsx = ru.lumastack.lumawork.data.simpleone.makeSimpleOneXlsx(
            listOf("ID", "Активность", "Когда создано", "Дата проведения работ", "Время работ (м)", "Рабочие заметки"),
            listOf(listOf("record-1", "=SUTS001", "2026-08-12 10:00:00", "2026-07-31 09:00:00", "75", "<Заметка> & текст")))
        val repository = TimeReportRepository(FakeTimeReportApi(exported = xlsx, listError = SimpleOneFailure.Http(500, "Ошибка списка")),
            authorizedSessionStore(), FakeTimeReportCache())
        val loaded = repository.refresh("owner", "simple-user")
        assertEquals(TimeReportSource.Xlsx, loaded.source)
        val roundTrip = TimeReportParser.parseXlsx(repository.exportXlsx(loaded.entries))
        assertEquals(loaded.entries, roundTrip)
        assertEquals("record-1", roundTrip.single().simpleOneRecordId)
        assertEquals(LocalDate.of(2026, 7, 31), roundTrip.single().effectiveWorkDateTime()?.toLocalDate())
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun `first entry makes no requests and explicit load preserves cache on failure`() = kotlinx.coroutines.test.runTest {
        kotlinx.coroutines.Dispatchers.setMain(kotlinx.coroutines.test.StandardTestDispatcher(testScheduler))
        try {
            val api = FakeTimeReportApi(listEntries = listOf(entry("NEW", "2026-08-12 10:00:00")))
            val cache = FakeTimeReportCache()
            val model = ru.lumastack.lumawork.ui.timereport.TimeReportViewModel(TimeReportRepository(api, authorizedSessionStore(), cache), "owner", false)
            model.activate("simple-user", true)
            model.state.first { !it.isInitializing }
            assertNull(model.state.value.snapshot)
            assertEquals(0, api.networkCalls)
            model.refresh(); model.refresh()
            model.state.first { it.snapshot != null && !it.isLoading }
            assertEquals(1, api.networkCalls)
            val saved = model.state.value.snapshot
            assertEquals("NEW", saved?.entries?.single()?.activity)
            api.listError = SimpleOneFailure.Http(500, "Ошибка списка")
            model.refresh()
            model.state.first { it.errorMessage != null && !it.isLoading }
            assertEquals(saved, model.state.value.snapshot)
            assertEquals(saved, cache.value)
            org.junit.Assert.assertFalse(model.state.value.isLoading)
            org.junit.Assert.assertNotNull(model.state.value.errorMessage)
        } finally { kotlinx.coroutines.Dispatchers.resetMain() }
    }

    @Test fun `unknown identity accepts only cache from current authorization`() = runBlocking {
        val sessions = authorizedSessionStore()
        val cache = FakeTimeReportCache().apply { value = TimeReportSnapshot(
            simpleOneUserId = "previous-user", entries = listOf(entry("PRIVATE", "2026-08-12 10:00:00")),
            sessionFingerprint = ru.lumastack.lumawork.data.simpleone.simpleOneSessionFingerprint(
                StoredSimpleOneSession("owner", "old-auth", "previous"))) }
        val api = FakeTimeReportApi()
        val repository = TimeReportRepository(api, sessions, cache)
        assertNull(repository.read("owner"))
        cache.value = cache.value!!.copy(sessionFingerprint = ru.lumastack.lumawork.data.simpleone.simpleOneSessionFingerprint(sessions.read("owner")!!))
        assertEquals(cache.value, repository.read("owner"))
        assertEquals(0, api.networkCalls)
    }

    @Test fun `empty list uses technical XLSX columns instead of reporting false success`() = runBlocking {
        val xlsx = ru.lumastack.lumawork.data.simpleone.makeSimpleOneXlsx(
            listOf("sys_id", "acticvity", "sys_created_at", "date_of_work", "time_of_work_hours"),
            listOf(listOf("record-1", "SUTS001", "2026-08-12 10:00:00", "2026-07-31 09:00:00", "2")))
        val repository = TimeReportRepository(FakeTimeReportApi(exported = xlsx), authorizedSessionStore(), FakeTimeReportCache())
        val snapshot = repository.refresh("owner", "simple-user")
        assertEquals(TimeReportSource.Xlsx, snapshot.source)
        assertEquals("record-1", snapshot.entries.single().simpleOneRecordId)
        assertEquals(120, snapshot.entries.single().workMinutes)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun `login load waits for hydration and is executed without a second click`() = kotlinx.coroutines.test.runTest {
        kotlinx.coroutines.Dispatchers.setMain(kotlinx.coroutines.test.StandardTestDispatcher(testScheduler))
        try {
            val api = FakeTimeReportApi(listEntries = listOf(entry("NEW", "2026-08-12 10:00:00")))
            val model = ru.lumastack.lumawork.ui.timereport.TimeReportViewModel(
                TimeReportRepository(api, authorizedSessionStore(), FakeTimeReportCache()), "owner", false)
            model.activate("simple-user", true, "new-session")
            org.junit.Assert.assertTrue(model.loadAfterActivation())
            model.state.first { it.snapshot != null && !it.isLoading }
            assertEquals(1, api.networkCalls)
        } finally { kotlinx.coroutines.Dispatchers.resetMain() }
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
    var listError: Exception? = null,
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
        listError?.let { throw it }
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
