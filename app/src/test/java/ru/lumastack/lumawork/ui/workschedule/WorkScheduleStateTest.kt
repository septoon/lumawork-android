package ru.lumastack.lumawork.ui.workschedule

import java.time.LocalDate
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import ru.lumastack.lumawork.data.simpleone.*
import ru.lumastack.lumawork.data.workschedule.*
import ru.lumastack.lumawork.domain.workschedule.*

@OptIn(ExperimentalCoroutinesApi::class)
class WorkScheduleStateTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { Dispatchers.resetMain() }

    @Test fun `offline filters and refresh reuse only matching user cache and never send requests`() = runTest(dispatcher) {
        val api = ScheduleApi()
        val cache = ScheduleCache()
        val model = model(api, cache, offline = true)
        model.activate(true); advanceUntilIdle()
        assertNotNull(model.state.value.schedule)
        model.selectCity("Ялта"); model.refresh(); advanceUntilIdle()
        assertEquals("Ялта", model.state.value.selection.cityName)
        assertEquals(0, api.calls)
        assertFalse(model.state.value.isLoading)
        assertNull(WorkScheduleRepository(api, session(), cache, dispatcher).read("owner", "another-user"))
    }
    @Test fun `persisted graph appears before journal refresh completes`() = runTest(dispatcher) {
        val api = ScheduleApi().apply { journalGate = CompletableDeferred() }
        val model = model(api)
        model.activate(true); runCurrent()
        assertNotNull(model.state.value.schedule)
        assertTrue(model.state.value.isLoading)
        api.journalGate!!.complete(Unit); advanceUntilIdle()
        assertFalse(model.state.value.isLoading)
    }
    @Test fun `late previous period response cannot replace selected graph`() = runTest(dispatcher) {
        val api = ScheduleApi()
        val model = model(api)
        model.activate(true); advanceUntilIdle()
        api.scheduleGate = CompletableDeferred()
        model.refresh(); runCurrent()
        model.selectCity("Ялта"); runCurrent()
        api.scheduleGate!!.complete(Unit); advanceUntilIdle()
        assertEquals("yalta", model.state.value.schedule?.journal?.sysId)
        assertFalse(model.state.value.isLoading)
    }
    @Test fun `network refresh failure keeps cached graph visible`() = runTest(dispatcher) {
        val api = ScheduleApi().apply { journalError = SimpleOneFailure.Network(java.io.IOException()) }
        val model = model(api)
        model.activate(true); advanceUntilIdle()
        assertNotNull(model.state.value.schedule)
        assertNotNull(model.state.value.errorMessage)
        assertFalse(model.state.value.isLoading)
    }
    @Test fun `unauthorized clears failed session and preserves offline graph`() = runTest(dispatcher) {
        var clearCalls = 0
        val sessions = session { clearCalls++ }
        val api = ScheduleApi().apply { journalError = SimpleOneFailure.Unauthorized }
        val model = WorkScheduleViewModel(WorkScheduleRepository(api, sessions, ScheduleCache(), dispatcher), "owner", "user", false, "Алушта", { LocalDate.of(2026,10,17) })
        model.activate(true); advanceUntilIdle()
        assertEquals(1, clearCalls)
        assertFalse(model.state.value.isAuthorized)
        assertNotNull(model.state.value.schedule)
        assertFalse(model.state.value.isLoading)
    }
    @Test fun `concurrent journal and schedule writes preserve both results`() = runTest(dispatcher) {
        val cache = ScheduleCache()
        val api = ScheduleApi()
        val repository = WorkScheduleRepository(api, session(), cache, dispatcher)
        val first = launch { repository.refreshJournals("owner", "user") }
        val second = launch { repository.loadSchedule("owner", "user", yal, true) }
        first.join(); second.join()
        assertEquals(2, cache.value.journals.size)
        assertTrue(cache.value.schedulesByJournalId.keys.containsAll(listOf("alushta", "yalta")))
    }
    @Test fun `same user login after unauthorized reactivates cached view model`() = runTest(dispatcher) {
        val api = ScheduleApi().apply { journalError = SimpleOneFailure.Unauthorized }
        val model = model(api)
        model.activate(true); advanceUntilIdle()
        assertFalse(model.state.value.isAuthorized)
        val failedCalls = api.calls
        api.journalError = null
        model.activate(true); advanceUntilIdle()
        assertTrue(model.state.value.isAuthorized)
        assertTrue(api.calls > failedCalls)
        assertNull(model.state.value.errorMessage)
    }
    @Test fun `authorized activation after credential refresh requests fresh journals`() = runTest(dispatcher) {
        val api = ScheduleApi()
        val model = model(api)
        model.activate(true); advanceUntilIdle()
        val previousCalls = api.calls
        model.activate(true); advanceUntilIdle()
        assertTrue(api.calls > previousCalls)
    }
    @Test fun `successful graph retry clears prior network error`() = runTest(dispatcher) {
        val api = ScheduleApi()
        val model = model(api)
        model.activate(true); advanceUntilIdle()
        api.scheduleError = SimpleOneFailure.Network(java.io.IOException())
        model.refresh(); advanceUntilIdle()
        assertNotNull(model.state.value.errorMessage)
        api.scheduleError = null
        model.refresh(); advanceUntilIdle()
        assertNull(model.state.value.errorMessage)
        assertFalse(model.state.value.isLoading)
        assertNotNull(model.state.value.schedule)
    }
    @Test fun `cancelled uncancellable graph response cannot enter synchronous cache`() = runTest(dispatcher) {
        val api = ScheduleApi().apply { scheduleGate = CompletableDeferred() }
        val cache = ScheduleCache().apply { yieldBeforeSave = false }
        val before = cache.value
        val repository = WorkScheduleRepository(api, session(), cache, dispatcher)
        val request = launch { repository.loadSchedule("owner", "user", alu, true) }
        runCurrent()
        request.cancel()
        api.scheduleGate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(0, cache.saveCalls)
        assertEquals(before, cache.value)
    }
    @Test fun `reopening graph preserves selected city instead of resetting to profile city`() = runTest(dispatcher) {
        val model = model(ScheduleApi())
        model.activate(true); advanceUntilIdle()
        model.selectCity("Ялта"); advanceUntilIdle()
        model.activate(true); advanceUntilIdle()
        assertEquals("Ялта", model.state.value.selection.cityName)
        assertEquals("yalta", model.state.value.schedule?.journal?.sysId)
    }

    private fun model(api: ScheduleApi, cache: ScheduleCache = ScheduleCache(), offline: Boolean = false) = WorkScheduleViewModel(
        WorkScheduleRepository(api, session(), cache, dispatcher), "owner", "user", offline, "Алушта", { LocalDate.of(2026,10,17) },
    )
}

private val alu = WorkScheduleJournal("alushta", "1", "Алушта", 2026, 10)
private val yal = WorkScheduleJournal("yalta", "2", "Ялта", 2026, 10)
private fun schedule(journal: WorkScheduleJournal) = WorkSchedule(journal, 31, emptyList(), emptyList(), WorkScheduleAuditInfo(createdBy = "Автор"))
private class ScheduleCache : WorkScheduleCache {
    var value = WorkScheduleSnapshot("user", listOf(alu, yal), mapOf(alu.sysId to schedule(alu)))
    var yieldBeforeSave = true
    var saveCalls = 0
    override suspend fun read(ownerUserId: String) = value
    override suspend fun save(ownerUserId: String, snapshot: WorkScheduleSnapshot) { if (yieldBeforeSave) yield(); saveCalls++; value = snapshot }
}
private class ScheduleApi : SimpleOneApi by OkHttpSimpleOneApi("https://sd.test/v1") {
    var calls = 0
    var journalGate: CompletableDeferred<Unit>? = null
    var scheduleGate: CompletableDeferred<Unit>? = null
    var journalError: Exception? = null
    var scheduleError: Exception? = null
    override suspend fun workScheduleJournals(authKey: String): List<WorkScheduleJournal> {
        calls++
        journalGate?.await()
        journalError?.let { throw it }
        return listOf(alu, yal)
    }
    override suspend fun workSchedule(journal: WorkScheduleJournal, authKey: String): WorkSchedule {
        calls++
        if (journal == alu) scheduleGate?.let { withContext(NonCancellable) { it.await() } }
        scheduleError?.let { throw it }
        return schedule(journal)
    }
}
private fun session(onClear: () -> Unit = {}) = object : SimpleOneSessionStore {
    override fun read(ownerUserId: String) = StoredSimpleOneSession(ownerUserId, "key", "engineer")
    override fun clear(ownerUserId: String) = onClear()
    override fun save(session: StoredSimpleOneSession) = Unit
    override fun readUsername(ownerUserId: String) = "engineer"
    override fun saveUsername(ownerUserId: String, username: String) = Unit
}
