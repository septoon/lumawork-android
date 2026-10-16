package ru.lumastack.lumawork.data.auto

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import ru.lumastack.lumawork.domain.auto.AutoSnapshot
import ru.lumastack.lumawork.domain.auto.MaintenanceDraft
import ru.lumastack.lumawork.domain.auto.MaintenanceRecord
import ru.lumastack.lumawork.domain.auto.Vehicle
import ru.lumastack.lumawork.domain.auto.VehicleDraft
import ru.lumastack.lumawork.domain.model.AppSession

class AutoRepository(
    private val api: AutoApi,
    private val cache: AutoCache,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) {
    private val mutableChanges = MutableSharedFlow<Pair<String, AutoSnapshot>>(extraBufferCapacity = 1)
    val changes = mutableChanges.asSharedFlow()

    suspend fun read(ownerUserId: String): AutoSnapshot? = cache.read(ownerUserId)

    suspend fun refresh(session: AppSession): AutoSnapshot = coroutineScope {
        val vehiclesRequest = async { api.vehicles(session.token) }
        val maintenanceRequest = async { api.maintenance(session.token) }
        val fetchedVehicles = vehiclesRequest.await()
        val records = maintenanceRequest.await()
        val synchronizedVehicles = synchronizeLegacyVehicle(session, fetchedVehicles)
        val previous = read(session.user.id)
        val selectedId = previous?.selectedVehicleId?.takeIf { id -> synchronizedVehicles.any { it.id == id } }
            ?: synchronizedVehicles.firstOrNull(Vehicle::isPrimary)?.id
            ?: synchronizedVehicles.firstOrNull()?.id
        return@coroutineScope AutoSnapshot(
            vehicles = synchronizedVehicles.sortedVehicles(),
            maintenance = records.sortedByDescending(MaintenanceRecord::date),
            selectedVehicleId = selectedId,
            updatedAtEpochMillis = nowEpochMillis(),
        ).also { persist(session.user.id, it) }
    }

    suspend fun select(ownerUserId: String, vehicleId: String): AutoSnapshot {
        val previous = read(ownerUserId) ?: AutoSnapshot()
        return previous.copy(selectedVehicleId = vehicleId).also { persist(ownerUserId, it) }
    }

    suspend fun createVehicle(session: AppSession, draft: VehicleDraft): AutoSnapshot {
        val created = api.createVehicle(session.token, draft)
        val previous = read(session.user.id) ?: AutoSnapshot()
        val vehicles = (previous.vehicles + created).map { vehicle ->
            if (created.isPrimary) vehicle.copy(isPrimary = vehicle.id == created.id) else vehicle
        }.sortedVehicles()
        return previous.copy(
            vehicles = vehicles,
            selectedVehicleId = created.id,
            updatedAtEpochMillis = nowEpochMillis(),
        ).also { persist(session.user.id, it) }
    }

    suspend fun updateVehicle(session: AppSession, vehicleId: String, draft: VehicleDraft): AutoSnapshot {
        val updated = api.updateVehicle(session.token, vehicleId, draft)
        val previous = read(session.user.id) ?: AutoSnapshot()
        val vehicles = previous.vehicles.map { vehicle ->
            when {
                vehicle.id == updated.id -> updated
                updated.isPrimary -> vehicle.copy(isPrimary = false)
                else -> vehicle
            }
        }.sortedVehicles()
        return previous.copy(
            vehicles = vehicles,
            selectedVehicleId = updated.id,
            updatedAtEpochMillis = nowEpochMillis(),
        ).also { persist(session.user.id, it) }
    }

    suspend fun saveMaintenance(
        session: AppSession,
        editingRecordId: String?,
        draft: MaintenanceDraft,
    ): AutoSnapshot {
        val saved = if (editingRecordId == null) {
            api.createMaintenance(session.token, draft)
        } else {
            api.updateMaintenance(session.token, editingRecordId, draft)
        }
        val previous = read(session.user.id) ?: AutoSnapshot()
        val records = if (editingRecordId == null) {
            previous.maintenance + saved
        } else {
            previous.maintenance.map { if (it.id == editingRecordId) saved else it }
        }
        return previous.copy(
            maintenance = records.sortedByDescending(MaintenanceRecord::date),
            updatedAtEpochMillis = nowEpochMillis(),
        ).also { persist(session.user.id, it) }
    }

    private suspend fun persist(ownerUserId: String, snapshot: AutoSnapshot) {
        cache.save(ownerUserId, snapshot)
        mutableChanges.emit(ownerUserId to snapshot)
    }

    private suspend fun synchronizeLegacyVehicle(session: AppSession, vehicles: List<Vehicle>): List<Vehicle> {
        val index = vehicles.indexOfFirst(Vehicle::isPrimary).takeIf { it >= 0 } ?: vehicles.indices.firstOrNull()
            ?: return vehicles
        val current = vehicles[index]
        val merged = current.fillMissing(session.user.profile)
        if (VehicleDraft(current) == VehicleDraft(merged)) return vehicles
        val synced = runCatching { api.updateVehicle(session.token, current.id, VehicleDraft(merged)) }
            .getOrDefault(merged)
        return vehicles.toMutableList().also { it[index] = synced }
    }

    private fun List<Vehicle>.sortedVehicles(): List<Vehicle> = sortedWith(
        compareByDescending<Vehicle>(Vehicle::isPrimary).thenBy(String.CASE_INSENSITIVE_ORDER, Vehicle::displayName),
    )
}
