package ru.lumastack.lumawork.ui.fuel

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import ru.lumastack.lumawork.data.fuel.*
import ru.lumastack.lumawork.data.route.*
import ru.lumastack.lumawork.domain.fuel.*
import ru.lumastack.lumawork.domain.model.*
import ru.lumastack.lumawork.domain.route.*

@OptIn(ExperimentalCoroutinesApi::class)
class FuelStateTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { Dispatchers.resetMain() }
    @Test fun `offline refresh save and deletion never send requests`() = runTest(dispatcher) {
        val f = Fixture(true); advanceUntilIdle()
        f.vm.refresh(); f.vm.addFuel(); f.vm.updateDraft(draft); f.vm.save(); advanceUntilIdle()
        f.vm.requestDelete(record); f.vm.confirmDelete(); advanceUntilIdle()
        assertEquals(0, f.api.reads + f.api.writes + f.api.deletes)
    }
    @Test fun `double submit creates a single fuel record`() = runTest(dispatcher) {
        val f = Fixture(); advanceUntilIdle()
        f.vm.addFuel(); f.vm.updateDraft(draft); f.vm.save(); f.vm.save(); advanceUntilIdle()
        assertEquals(1, f.api.writes)
    }
    @Test fun `legacy record without id cannot be duplicated by edit`() = runTest(dispatcher) {
        val f = Fixture(); advanceUntilIdle()
        f.vm.edit(record.copy(id = null)); f.vm.save(); advanceUntilIdle()
        assertEquals(0, f.api.writes)
        assertNotNull(f.vm.state.value.editorErrorMessage)
    }
    @Test fun `cached years are selected offline and archive excluded from main summary`() = runTest(dispatcher) {
        val f = Fixture(true); advanceUntilIdle()
        assertEquals("2026", f.vm.state.value.selectedYear)
        assertEquals(100.0, f.vm.state.value.summary.totals.totalMileage, 0.0)
    }
    @Test fun `local monthly marker does not suppress retry after failed remote sync`() = runTest(dispatcher) {
        val api = FakeFuelApi().apply { failNextWrite = true }
        val cache = FakeFuelCache().apply { value = FuelSnapshot(listOf(record.copy(comment = "route_monthly_mileage|2026-10-01|100"))) }
        val repo = FuelRepository(api, cache)
        assertTrue(runCatching { repo.syncMonthlyMileage(session, "2026-10", "2026-10-17", 200, true) }.isFailure)
        repo.syncMonthlyMileage(session, "2026-10", "2026-10-17", 200, true)
        assertEquals(2, api.writes)
    }
    @Test fun `refresh cannot discard fuel saved while request was running`() = runTest(dispatcher) {
        val api = FakeFuelApi().apply { readGate = CompletableDeferred() }
        val cache = FakeFuelCache()
        val repo = FuelRepository(api, cache)
        val refresh = async { repo.refresh(session) }; runCurrent()
        repo.save(session, null, draft)
        api.readGate!!.complete(Unit); advanceUntilIdle(); refresh.await()
        assertTrue(cache.value!!.records.any { it.id == "new" })
    }
    @Test fun `pending route mileage from an earlier month retries on reopening`() = runTest(dispatcher) {
        val f = Fixture()
        f.cache.value = FuelSnapshot(records = listOf(FuelRecord("old", date = "2025-08-31", mileage = 100.0,
            comment = "route_monthly_mileage|2025-08-31|100")), pendingMileageMonths = setOf("2025-08"))
        advanceUntilIdle()
        assertEquals(1, f.api.writes)
        assertTrue(f.cache.value!!.pendingMileageMonths.isEmpty())
    }
    @Test fun `refresh preserves server order for same date debt carryovers`() = runTest(dispatcher) {
        val api = FakeFuelApi().apply { recordsResult = listOf(
            FuelRecord("a-new", FuelRecordType.Adjustment, FuelAdjustmentKind.DebtDeduction, "2026-08", carryoverDebtRub = 400.0, date = "2026-08-01"),
            FuelRecord("z-old", FuelRecordType.Adjustment, FuelAdjustmentKind.DebtDeduction, "2026-08", carryoverDebtRub = 100.0, date = "2026-08-01"),
        ) }
        val snapshot = FuelRepository(api, FakeFuelCache()).refresh(session)
        assertEquals(listOf("a-new", "z-old"), snapshot.records.map { it.id })
        assertEquals(100.0, FuelSummaryCalculator.build(snapshot.records).monthly.single().monthCarryoverDebtRub, 0.0)
    }
    @Test fun `simultaneous saves retain both records in cache`() = runTest(dispatcher) {
        val api = FakeFuelApi()
        val cache = FakeFuelCache().apply { yieldOnSave = true }
        val repo = FuelRepository(api, cache)
        awaitAll(async { repo.save(session, null, draft) }, async { repo.save(session, null, draft) })
        assertEquals(2, cache.value!!.records.size)
    }
}
private val session = AppSession("token", AppUser("owner", "test@example.test", "user"))
private val draft = FuelDraft(date = "2026-10-17", mileage = "120")
private val record = FuelRecord("r1", date = "2026-10-01", mileage = 100.0)
private class Fixture(offline: Boolean = false) {
    val api = FakeFuelApi()
    val cache = FakeFuelCache().apply { value = FuelSnapshot(listOf(record, FuelRecord("archived", date = "2025-01-01", mileage = 999.0))) }
    val vm = FuelViewModel(FuelRepository(api, cache), RouteRepository(OkHttpRouteApi("https://route.test"), object : RouteCache {
        override suspend fun read(userId: String, date: String, workType: RouteWorkType): RouteDayRecord? = null
        override suspend fun save(userId: String, record: RouteDayRecord) {}
    }), session, offline)
}
private class FakeFuelCache : FuelCache {
    var value: FuelSnapshot? = null
    var yieldOnSave = false
    override suspend fun read(ownerUserId: String) = value
    override suspend fun save(ownerUserId: String, snapshot: FuelSnapshot) { if (yieldOnSave) yield(); value = snapshot }
}
private class FakeFuelApi : FuelApi {
    var recordsResult = emptyList<FuelRecord>()
    var reads = 0; var writes = 0; var deletes = 0
    var failNextWrite = false
    var readGate: CompletableDeferred<Unit>? = null
    override suspend fun records(token: String): List<FuelRecord> { reads++; readGate?.await(); return recordsResult }
    override suspend fun create(token: String, draft: FuelDraft): FuelRecord { writes++; if (failNextWrite) { failNextWrite = false; error("offline") }; return FuelRecord(if (writes == 1) "new" else "new-$writes", date = draft.date, mileage = draft.mileageValue, comment = draft.comment) }
    override suspend fun update(token: String, recordId: String, draft: FuelDraft) = create(token, draft).copy(id = recordId)
    override suspend fun delete(token: String, recordId: String) { deletes++ }
}
