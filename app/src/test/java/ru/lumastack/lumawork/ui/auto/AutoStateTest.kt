package ru.lumastack.lumawork.ui.auto

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import ru.lumastack.lumawork.data.auto.*
import ru.lumastack.lumawork.domain.auto.*
import ru.lumastack.lumawork.domain.model.*

@OptIn(ExperimentalCoroutinesApi::class)
class AutoStateTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { Dispatchers.resetMain() }

    @Test fun `offline refresh and save never send requests`() = runTest(dispatcher) {
        val api = TestAutoApi()
        val model = AutoViewModel(AutoRepository(api, TestAutoCache()), session, true)
        advanceUntilIdle()
        model.refresh(); model.addVehicle(); model.updateVehicleDraft(validVehicle); model.saveVehicle()
        advanceUntilIdle()
        assertEquals(0, api.readCalls)
        assertEquals(0, api.vehicleSaves)
        assertNotNull(model.state.value.editorErrorMessage)
    }
    @Test fun `repeated vehicle save submits one request`() = runTest(dispatcher) {
        val api = TestAutoApi()
        val model = AutoViewModel(AutoRepository(api, TestAutoCache()), session, false)
        advanceUntilIdle()
        model.addVehicle(); model.updateVehicleDraft(validVehicle)
        model.saveVehicle(); model.saveVehicle()
        advanceUntilIdle()
        assertEquals(1, api.vehicleSaves)
        assertFalse(model.state.value.isSaving)
    }
    @Test fun `legacy record without server id cannot be recreated by editing`() = runTest(dispatcher) {
        val api = TestAutoApi()
        val model = AutoViewModel(AutoRepository(api, TestAutoCache()), session, false)
        advanceUntilIdle()
        model.editMaintenance(MaintenanceRecord(date = "2026-10-17", procedure = "Замена масла", mileage = 100))
        model.saveMaintenance()
        advanceUntilIdle()
        assertEquals(0, api.maintenanceSaves)
        assertNotNull(model.state.value.editorErrorMessage)
    }
    @Test fun `negative mileage and non finite work cost are rejected`() = runTest(dispatcher) {
        val api = TestAutoApi()
        val model = AutoViewModel(AutoRepository(api, TestAutoCache()), session, false)
        advanceUntilIdle()
        model.addMaintenance()
        model.updateMaintenanceDraft(MaintenanceDraft(vehicleId = "v1", date = "2026-10-17", procedure = "ТО", mileage = "-1"))
        model.saveMaintenance(); advanceUntilIdle()
        assertEquals(0, api.maintenanceSaves)
        model.updateMaintenanceDraft(MaintenanceDraft(vehicleId = "v1", date = "2026-10-17", procedure = "ТО", mileage = "1", workCost = "NaN"))
        model.saveMaintenance(); advanceUntilIdle()
        assertEquals(0, api.maintenanceSaves)
    }
    @Test fun `empty parts rows are ignored like iOS`() = runTest(dispatcher) {
        val api = TestAutoApi()
        val model = AutoViewModel(AutoRepository(api, TestAutoCache()), session, false)
        advanceUntilIdle()
        model.addMaintenance()
        model.updateMaintenanceDraft(MaintenanceDraft(vehicleId = "v1", date = "2026-10-17", procedure = "ТО", mileage = "1", parts = listOf(MaintenancePartDraft())))
        model.saveMaintenance(); advanceUntilIdle()
        assertEquals(1, api.maintenanceSaves)
    }
    @Test fun `refresh started before a create cannot discard the created car`() = runTest(dispatcher) {
        val api = TestAutoApi().apply { maintenanceGate = CompletableDeferred() }
        val cache = TestAutoCache()
        val repo = AutoRepository(api, cache)
        val refresh = async { repo.refresh(session) }
        runCurrent()
        repo.createVehicle(session, validVehicle)
        api.maintenanceGate!!.complete(Unit)
        advanceUntilIdle(); refresh.await()
        assertTrue(cache.value.vehicles.any { it.id == "new" })
        assertEquals("new", cache.value.selectedVehicleId)
    }
    @Test fun `concurrent selection and create persist both changes`() = runTest(dispatcher) {
        val cache = TestAutoCache().apply { yieldOnSave = true }
        val repo = AutoRepository(TestAutoApi(), cache)
        val create = launch { repo.createVehicle(session, validVehicle) }
        val select = launch { repo.select("owner", "v2") }
        create.join(); select.join()
        assertTrue(cache.value.vehicles.any { it.id == "new" })
        assertEquals("v2", cache.value.selectedVehicleId)
    }
    @Test fun `refresh started during create cannot discard the completed mutation`() = runTest(dispatcher) {
        val api = TestAutoApi().apply { createGate = CompletableDeferred(); maintenanceGate = CompletableDeferred() }
        val cache = TestAutoCache()
        val repo = AutoRepository(api, cache)
        val create = async { repo.createVehicle(session, validVehicle) }
        runCurrent()
        val refresh = async { repo.refresh(session) }
        runCurrent()
        api.createGate!!.complete(Unit); runCurrent(); create.await()
        api.maintenanceGate!!.complete(Unit); advanceUntilIdle(); refresh.await()
        assertTrue(cache.value.vehicles.any { it.id == "new" })
    }
    @Test fun `late vehicle save keeps documents uploaded during its request`() = runTest(dispatcher) {
        val api = TestAutoApi().apply { updateGate = CompletableDeferred() }
        val cache = TestAutoCache()
        val repo = AutoRepository(api, cache)
        val edit = async { repo.updateVehicle(session, "v1", validVehicle) }
        runCurrent()
        repo.uploadDocument(session, "v1", VehicleDocumentInput(byteArrayOf(1), "test.pdf", "application/pdf", VehicleDocumentKind.Other)) {}
        api.updateGate!!.complete(Unit); advanceUntilIdle(); edit.await()
        assertEquals("doc", cache.value.vehicles.first { it.id == "v1" }.documents.orEmpty().single().id)
    }
    @Test fun `late vehicle save cannot resurrect deleted document metadata`() = runTest(dispatcher) {
        val doc = VehicleDocument("doc", VehicleDocumentKind.Other, "test.pdf", "application/pdf", 1, "2026-10-17")
        val api = TestAutoApi().apply { updateGate = CompletableDeferred(); updatedResult = Vehicle("v1", documents = listOf(doc)) }
        val cache = TestAutoCache().apply { value = value.copy(vehicles = listOf(Vehicle("v1", documents = listOf(doc)))) }
        val repo = AutoRepository(api, cache)
        val edit = async { repo.updateVehicle(session, "v1", validVehicle) }
        runCurrent()
        repo.deleteDocument(session, "v1", "doc")
        api.updateGate!!.complete(Unit); advanceUntilIdle(); edit.await()
        assertTrue(cache.value.vehicles.single().documents.orEmpty().isEmpty())
    }

}
private val session = AppSession("token", AppUser("owner", "test@example.test", "user"))
private val validVehicle = VehicleDraft(licensePlate = "А123ВС 82", make = "Лада", model = "Веста")
private class TestAutoCache : AutoCache {
    var value = AutoSnapshot(vehicles = listOf(Vehicle("v1"), Vehicle("v2")), selectedVehicleId = "v1")
    var yieldOnSave = false
    override suspend fun read(ownerUserId: String) = value
    override suspend fun save(ownerUserId: String, snapshot: AutoSnapshot) { if (yieldOnSave) yield(); value = snapshot }
}
private class TestAutoApi : AutoApi by OkHttpAutoApi("https://auto.test", Json { ignoreUnknownKeys = true }) {
    var readCalls = 0
    var vehicleSaves = 0
    var maintenanceSaves = 0
    var maintenanceGate: CompletableDeferred<Unit>? = null
    var createGate: CompletableDeferred<Unit>? = null
    var updateGate: CompletableDeferred<Unit>? = null
    var updatedResult = Vehicle("v1")
    override suspend fun vehicles(token: String): List<Vehicle> { readCalls++; return listOf(Vehicle("v1"), Vehicle("v2")) }
    override suspend fun maintenance(token: String): List<MaintenanceRecord> { readCalls++; maintenanceGate?.await(); return emptyList() }
    override suspend fun createVehicle(token: String, draft: VehicleDraft): Vehicle { vehicleSaves++; createGate?.await(); return Vehicle("new") }
    override suspend fun updateVehicle(token: String, vehicleId: String, draft: VehicleDraft): Vehicle { vehicleSaves++; updateGate?.await(); return updatedResult.copy(id = vehicleId) }
    override suspend fun uploadDocument(token: String, vehicleId: String, input: VehicleDocumentInput, onProgress: (Double) -> Unit) = VehicleDocument("doc", input.kind, input.fileName, input.mimeType, input.data.size, "2026-10-17")
    override suspend fun deleteDocument(token: String, vehicleId: String, documentId: String) = Unit
    override suspend fun createMaintenance(token: String, draft: MaintenanceDraft): MaintenanceRecord { maintenanceSaves++; return MaintenanceRecord("record", draft.vehicleId, draft.date, draft.procedure, draft.mileage.toInt()) }
    override suspend fun updateMaintenance(token: String, recordId: String, draft: MaintenanceDraft) = createMaintenance(token, draft).copy(id = recordId)
}
