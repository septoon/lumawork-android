package ru.lumastack.lumawork.data.gsm

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import ru.lumastack.lumawork.data.auto.*
import ru.lumastack.lumawork.domain.auto.*
import ru.lumastack.lumawork.domain.model.AppSession
import ru.lumastack.lumawork.domain.model.AppUser

class GsmProfileWithVehicleTest {
    @Test fun `save creates and selects vehicle from accepted profile and persists GSM link`() = runBlocking {
        val h = Harness()
        val saved = h.gsm.saveWithVehicle(h.session, h.profile, h.auto)
        val vehicle = h.auto.read("owner")!!.vehicles.single()
        assertEquals(vehicle.id, saved.profile.vehicleId)
        assertEquals(vehicle.id, h.auto.read("owner")!!.selectedVehicleId)
        assertEquals("Тест Авто IV", vehicle.model)
        assertNull(vehicle.make)
        assertEquals("А123ВС 82", vehicle.licensePlate)
        assertEquals(10330, vehicle.currentMileageKm)
        assertTrue(vehicle.isPrimary)
        assertEquals(10330, vehicle.gsmSettings!!.defaultStartOdometer)
        assertEquals(7.5, vehicle.gsmSettings!!.fuelNorm, 0.0)
        assertEquals("2026-10", vehicle.gsmSettings!!.reportStartMonth)
        assertEquals(2, h.profileApi.saves)
        assertEquals(1, h.autoApi.creates)
        assertNull(h.auto.read("another-owner"))
    }

    @Test fun `existing car with equivalent Latin plate is selected without duplicate or overwritten details`() = runBlocking {
        val existing = Vehicle("existing", make = "Тест", model = "Авто IV", licensePlate = "A123BC82", vin = "PRESERVE", currentMileageKm = 20000)
        val h = Harness(listOf(existing))
        val saved = h.gsm.saveWithVehicle(h.session, h.profile, h.auto)
        assertEquals("existing", saved.profile.vehicleId)
        assertEquals(0, h.autoApi.creates)
        assertEquals(1, h.profileApi.saves)
        assertEquals("PRESERVE", h.auto.read("owner")!!.vehicles.single().vin)
        assertEquals(20000, h.auto.read("owner")!!.vehicles.single().currentMileageKm)
        assertEquals("existing", h.auto.read("owner")!!.selectedVehicleId)
    }

    @Test fun `rejected profile never creates a vehicle`() = runBlocking {
        val h = Harness()
        h.profileApi.failOnSave = 1
        try { h.gsm.saveWithVehicle(h.session, h.profile, h.auto); fail("expected failure") }
        catch (_: GsmProfileFailure.Http) { }
        assertEquals(0, h.autoApi.creates)
        assertTrue(h.autoApi.vehicles.isEmpty())
    }

    @Test fun `matching does not truncate different plate identities`() = runBlocking {
        val h = Harness(listOf(Vehicle("existing", licensePlate = "А123ВС827")))
        val saved = h.gsm.saveWithVehicle(h.session, h.profile.copy(licensePlate = "А123ВС8277"), h.auto)
        assertEquals("created", saved.profile.vehicleId)
        assertEquals(1, h.autoApi.creates)
        assertEquals(2, h.auto.read("owner")!!.vehicles.size)
    }

    @Test fun `retry after failed profile link reuses created vehicle`() = runBlocking {
        val h = Harness()
        h.profileApi.failOnSave = 2
        try { h.gsm.saveWithVehicle(h.session, h.profile, h.auto); fail("expected failure") }
        catch (_: GsmProfileFailure.VehicleSync) { }
        assertEquals(1, h.autoApi.creates)
        assertNull(h.gsm.read("owner")!!.profile.vehicleId)
        h.profileApi.failOnSave = null
        val saved = h.gsm.saveWithVehicle(h.session, h.profile, h.auto)
        assertEquals(h.autoApi.vehicles.single().id, saved.profile.vehicleId)
        assertEquals(1, h.autoApi.creates)
    }

    @Test fun `concurrent duplicate creation is resolved through fresh vehicle list`() = runBlocking {
        val h = Harness()
        h.autoApi.conflictOnCreate = true
        val saved = h.gsm.saveWithVehicle(h.session, h.profile, h.auto)
        assertEquals(h.autoApi.vehicles.single().id, saved.profile.vehicleId)
        assertEquals(1, h.autoApi.creates)
    }

    @Test fun `ambiguous matching cars require explicit choice before any save`() = runBlocking {
        val h = Harness(listOf(Vehicle("one", licensePlate = "А123ВС82"), Vehicle("two", licensePlate = "A123BC82")))
        try { h.gsm.saveWithVehicle(h.session, h.profile, h.auto); fail("expected ambiguity") }
        catch (_: GsmProfileFailure.Validation) { }
        assertEquals(0, h.profileApi.saves)
        assertEquals(0, h.autoApi.creates)
        val saved = h.gsm.saveWithVehicle(h.session, h.profile.copy(vehicleId = "two"), h.auto)
        assertEquals("two", saved.profile.vehicleId)
    }

    private class Harness(vehicles: List<Vehicle> = emptyList()) {
        val session = AppSession("token", AppUser("owner", "owner@example.test", "user"))
        val profile = GsmProfile(employeePhone = "+7(900)000-00-00", carModel = "Тест Авто IV", licensePlate = "А123ВС 82",
            defaultStartOdometer = 10330, fuelNorm = 7.5, fuelType = "АИ-95", fuelTypes = listOf("АИ-95"), reportStartMonth = "2026-10")
        val profileApi = ProfileApi()
        val autoApi = VehiclesApi(vehicles)
        val auto = AutoRepository(autoApi, object : AutoCache {
            val values = mutableMapOf<String, AutoSnapshot>()
            override suspend fun read(ownerUserId: String) = values[ownerUserId]
            override suspend fun save(ownerUserId: String, snapshot: AutoSnapshot) { values[ownerUserId] = snapshot }
        })
        val gsm = GsmProfileRepository(profileApi, object : GsmProfileCache {
            val values = mutableMapOf<String, GsmProfileSnapshot>()
            override suspend fun read(owner: String) = values[owner]
            override suspend fun save(owner: String, snapshot: GsmProfileSnapshot) { values[owner] = snapshot }
        })
    }

    private class ProfileApi : GsmProfileApi {
        var saves = 0
        var failOnSave: Int? = null
        override suspend fun fetch(token: String) = GsmProfileLoadResult(GsmProfile())
        override suspend fun projects(token: String) = emptyList<GsmProjectOption>()
        override suspend fun save(token: String, profile: GsmProfile): GsmProfile {
            saves++
            if (saves == failOnSave) throw GsmProfileFailure.Http(400)
            return profile
        }
    }

    private class VehiclesApi(initial: List<Vehicle>) : AutoApi {
        var vehicles = initial
        var creates = 0
        var conflictOnCreate = false
        override suspend fun vehicles(token: String) = vehicles
        override suspend fun maintenance(token: String) = emptyList<MaintenanceRecord>()
        override suspend fun createVehicle(token: String, draft: VehicleDraft): Vehicle {
            creates++
            val vehicle = Vehicle("created", model = draft.model, customName = draft.customName, licensePlate = draft.licensePlate,
                currentMileageKm = draft.currentMileageKm.toIntOrNull(), isPrimary = draft.isPrimary)
            vehicles = vehicles + vehicle
            if (conflictOnCreate) { conflictOnCreate = false; throw AutoFailure.Http(409) }
            return vehicle
        }
        override suspend fun updateVehicle(token: String, vehicleId: String, draft: VehicleDraft): Vehicle = error("must preserve existing vehicle")
        override suspend fun createMaintenance(token: String, draft: MaintenanceDraft): MaintenanceRecord = error("must not write history")
        override suspend fun updateMaintenance(token: String, recordId: String, draft: MaintenanceDraft): MaintenanceRecord = error("must not write history")
        override suspend fun uploadDocument(token: String, vehicleId: String, input: VehicleDocumentInput, onProgress: (Double) -> Unit): VehicleDocument = error("unused")
        override suspend fun downloadDocument(token: String, vehicleId: String, documentId: String): ByteArray = error("unused")
        override suspend fun deleteDocument(token: String, vehicleId: String, documentId: String) = error("unused")
    }
}
