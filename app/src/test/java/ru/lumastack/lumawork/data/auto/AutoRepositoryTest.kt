package ru.lumastack.lumawork.data.auto

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.lumastack.lumawork.domain.auto.AutoSnapshot
import ru.lumastack.lumawork.domain.auto.MaintenanceDraft
import ru.lumastack.lumawork.domain.auto.MaintenanceRecord
import ru.lumastack.lumawork.domain.auto.Vehicle
import ru.lumastack.lumawork.domain.auto.VehicleDraft
import ru.lumastack.lumawork.domain.model.AppSession
import ru.lumastack.lumawork.domain.model.AppUser
import ru.lumastack.lumawork.domain.model.UserProfile

class AutoRepositoryTest {
    @Test
    fun `refresh fills missing typed vehicle fields through existing update endpoint`() = runBlocking {
        val api = FakeAutoApi(
            vehicles = listOf(Vehicle(id = "v1", make = "Lada", model = "Vesta", isPrimary = true)),
        )
        val cache = FakeAutoCache()
        val repository = AutoRepository(api, cache) { 123L }
        val session = session(UserProfile(vehicleSts = "99 84 824631", initialMileageKm = "42 000"))

        val snapshot = repository.refresh(session)

        assertEquals("99 84 824631", snapshot.vehicles.single().sts)
        assertEquals(42_000, snapshot.vehicles.single().currentMileageKm)
        assertEquals("v1", api.updatedVehicleId)
        assertEquals(123L, snapshot.updatedAtEpochMillis)
    }

    @Test
    fun `selecting carousel vehicle changes only local selection`() = runBlocking {
        val api = FakeAutoApi()
        val cache = FakeAutoCache().apply {
            value = AutoSnapshot(vehicles = listOf(Vehicle("v1"), Vehicle("v2")), selectedVehicleId = "v1")
        }
        val repository = AutoRepository(api, cache)

        val snapshot = repository.select("owner", "v2")

        assertEquals("v2", snapshot.selectedVehicleId)
        assertEquals(0, api.updateCalls)
        assertEquals(0, api.createCalls)
    }

    @Test
    fun `legacy maintenance stays visible only for single vehicle without guessing multiple`() = runBlocking {
        val legacy = MaintenanceRecord(date = "2026-08-12", procedure = "ТО", mileage = 1)
        val single = AutoSnapshot(vehicles = listOf(Vehicle("v1")), maintenance = listOf(legacy), selectedVehicleId = "v1")
        val multiple = single.copy(vehicles = listOf(Vehicle("v1"), Vehicle("v2")))

        fun selected(snapshot: AutoSnapshot) = snapshot.maintenance.filter {
            it.vehicleId == snapshot.selectedVehicleId || (it.vehicleId == null && snapshot.vehicles.size == 1)
        }

        assertEquals(1, selected(single).size)
        assertEquals(0, selected(multiple).size)
        assertNull(legacy.vehicleId)
    }

    private fun session(profile: UserProfile? = null) = AppSession(
        token = "token",
        user = AppUser("owner", "owner@example.com", "user", profile = profile),
    )
}

private class FakeAutoApi(private val vehicles: List<Vehicle> = emptyList()) : AutoApi {
    var updatedVehicleId: String? = null
    var updateCalls = 0
    var createCalls = 0

    override suspend fun vehicles(token: String): List<Vehicle> = vehicles
    override suspend fun createVehicle(token: String, draft: VehicleDraft): Vehicle {
        createCalls += 1
        return Vehicle("new")
    }
    override suspend fun updateVehicle(token: String, vehicleId: String, draft: VehicleDraft): Vehicle {
        updateCalls += 1
        updatedVehicleId = vehicleId
        val original = vehicles.first { it.id == vehicleId }
        return original.copy(
            sts = draft.sts.ifBlank { null },
            currentMileageKm = draft.currentMileageKm.toIntOrNull(),
        )
    }
    override suspend fun maintenance(token: String): List<MaintenanceRecord> = emptyList()
    override suspend fun createMaintenance(token: String, draft: MaintenanceDraft): MaintenanceRecord = error("unused")
    override suspend fun updateMaintenance(token: String, recordId: String, draft: MaintenanceDraft): MaintenanceRecord = error("unused")
}

private class FakeAutoCache : AutoCache {
    var value: AutoSnapshot? = null
    override suspend fun read(ownerUserId: String): AutoSnapshot? = value
    override suspend fun save(ownerUserId: String, snapshot: AutoSnapshot) { value = snapshot }
}
