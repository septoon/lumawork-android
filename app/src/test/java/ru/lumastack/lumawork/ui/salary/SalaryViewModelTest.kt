package ru.lumastack.lumawork.ui.salary

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import ru.lumastack.lumawork.data.salary.*
import ru.lumastack.lumawork.domain.model.*

@OptIn(ExperimentalCoroutinesApi::class)
class SalaryViewModelTest {
    private val session = AppSession("token", AppUser("owner", "user@example.test", "user"))
    private val old = SalaryEntry("old", "2026-08-20", amount = 1000.0, kind = SalaryKind.Salary)

    @Test fun `late refresh cannot replace saved payment and repeated save submits only once`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val pending = CompletableDeferred<List<SalaryEntry>>()
        val started = CompletableDeferred<Unit>()
        val api = MemoryApi { started.complete(Unit); pending.await() }
        val cache = MemoryCache(SalarySnapshot(listOf(old)))
        val model = SalaryViewModel(SalaryRepository(api, cache), session, false)
        try {
            started.await()
            assertEquals(listOf(old), model.state.value.snapshot.records)
            model.add()
            model.draft(SalaryDraft(date = "2026-10-20", periodMonth = "2026-10", amount = "500"))
            model.save(); model.save()
            model.state.first { !it.saving && it.snapshot.records.size == 2 }
            assertEquals(1, api.creates)
            pending.complete(listOf(old))
            model.state.first { !it.loading }
            assertEquals(2, model.state.value.snapshot.records.size)
            assertEquals(2, cache.value!!.records.size)
        } finally { model.viewModelScope.cancel(); Dispatchers.resetMain() }
    }

    @Test fun `offline cache selects newest year and prevents writes while display filters persist`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val api = MemoryApi { error("No network offline") }
        var persisted: SalaryDisplayOptions? = null
        val model = SalaryViewModel(SalaryRepository(api, MemoryCache(SalarySnapshot(listOf(old)))), session, true,
            SalaryDisplayOptions(hidden = true), { persisted = it })
        try {
            model.state.first { it.months.isNotEmpty() }
            assertEquals("2026", model.state.value.selectedYear)
            model.hidden(); model.toggleKind(SalaryKind.Gsm)
            assertFalse(persisted!!.hidden)
            assertTrue(SalaryKind.Gsm in persisted!!.excludedKinds)
            model.add(); model.save(); model.deleteAsk(old); model.deleteConfirm(); model.refresh()
            assertNull(model.state.value.draft)
            assertEquals(0, api.creates + api.deletes)
        } finally { model.viewModelScope.cancel(); Dispatchers.resetMain() }
    }

    @Test fun `initial hydration cannot start refresh after a save has already begun`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val reading = CompletableDeferred<Unit>()
        val releaseRead = CompletableDeferred<Unit>()
        val creating = CompletableDeferred<Unit>()
        val releaseCreate = CompletableDeferred<Unit>()
        var snapshot = SalarySnapshot(listOf(old))
        var firstRead = true
        var loads = 0
        val cache = object : SalaryCache {
            override suspend fun read(owner: String): SalarySnapshot {
                if (firstRead) { firstRead = false; reading.complete(Unit); releaseRead.await() }
                return snapshot
            }
            override suspend fun save(owner: String, value: SalarySnapshot) { snapshot = value }
        }
        val api = MemoryApi(creating, releaseCreate) { loads++; listOf(old) }
        val model = SalaryViewModel(SalaryRepository(api, cache), session, false)
        try {
            reading.await()
            model.add(); model.draft(SalaryDraft(amount = "500")); model.save()
            releaseRead.complete(Unit)
            creating.await()
            model.state.first { !it.initializing }
            assertEquals(0, loads)
            releaseCreate.complete(Unit)
            model.state.first { !it.saving && it.snapshot.records.size == 2 }
            assertEquals(2, snapshot.records.size)
        } finally { model.viewModelScope.cancel(); Dispatchers.resetMain() }
    }

    private class MemoryCache(var value: SalarySnapshot?) : SalaryCache {
        override suspend fun read(owner: String) = value
        override suspend fun save(owner: String, snapshot: SalarySnapshot) { value = snapshot }
    }
    private class MemoryApi(private val creating: CompletableDeferred<Unit>? = null, private val releaseCreate: CompletableDeferred<Unit>? = null, val load: suspend () -> List<SalaryEntry>) : SalaryApi {
        var creates = 0
        var deletes = 0
        override suspend fun records(token: String) = load()
        override suspend fun create(token: String, draft: SalaryDraft): SalaryEntry {
            creates++
            creating?.complete(Unit)
            releaseCreate?.await()
            return SalaryEntry("new", draft.date, amount = draft.amount.toDouble(), periodMonth = draft.periodMonth, kind = draft.kind)
        }
        override suspend fun update(token: String, id: String, draft: SalaryDraft) = error("Unexpected update")
        override suspend fun delete(token: String, id: String) { deletes++ }
    }
}
