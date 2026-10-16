package ru.lumastack.lumawork.data.auto

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.lumastack.lumawork.domain.auto.*
import ru.lumastack.lumawork.domain.model.AppSession

class AutoRepository(
    private val api: AutoApi,
    private val cache: AutoCache,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) {
    private val cacheMutex = Mutex()
    private val mutationRevisions = mutableMapOf<String, Long>()
    private val documentRevisions = mutableMapOf<Pair<String, String>, Long>()
    private val mutableChanges = MutableSharedFlow<Pair<String, AutoSnapshot>>(replay = 1, extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val changes = mutableChanges.asSharedFlow()

    suspend fun read(ownerUserId: String): AutoSnapshot? = cache.read(ownerUserId)

    suspend fun refresh(session: AppSession): AutoSnapshot = coroutineScope {
        val owner = session.user.id
        val revision = cacheMutex.withLock { mutationRevisions[owner] ?: 0L }
        val vehiclesRequest = async { api.vehicles(session.token) }
        val maintenanceRequest = async { api.maintenance(session.token) }
        val vehicles = synchronizeLegacyVehicle(session, vehiclesRequest.await())
        val records = maintenanceRequest.await()
        update(owner) { previous ->
            if ((mutationRevisions[owner] ?: 0L) != revision) previous
            else {
                val selected = previous.selectedVehicleId?.takeIf { id -> vehicles.any { it.id == id } }
                    ?: vehicles.firstOrNull(Vehicle::isPrimary)?.id ?: vehicles.firstOrNull()?.id
                previous.copy(vehicles = vehicles.sortedVehicles(), maintenance = records.sortedByDescending(MaintenanceRecord::date),
                    selectedVehicleId = selected, updatedAtEpochMillis = nowEpochMillis())
            }
        }
    }

    suspend fun select(ownerUserId: String, vehicleId: String): AutoSnapshot = update(ownerUserId) { previous ->
        if (previous.vehicles.any { it.id == vehicleId }) previous.copy(selectedVehicleId = vehicleId) else previous
    }

    suspend fun createVehicle(session: AppSession, draft: VehicleDraft): AutoSnapshot {
        beginMutation(session.user.id)
        val created = api.createVehicle(session.token, draft)
        return update(session.user.id, mutation = true) { previous ->
            val vehicles = (previous.vehicles.filterNot { it.id == created.id } + created).map { vehicle ->
                if (created.isPrimary) vehicle.copy(isPrimary = vehicle.id == created.id) else vehicle
            }.sortedVehicles()
            previous.copy(vehicles = vehicles, selectedVehicleId = created.id, updatedAtEpochMillis = nowEpochMillis())
        }
    }

    suspend fun updateVehicle(session: AppSession, vehicleId: String, draft: VehicleDraft): AutoSnapshot {
        beginMutation(session.user.id)
        val documentKey = session.user.id to vehicleId
        val documentRevision = cacheMutex.withLock { documentRevisions[documentKey] ?: 0L }
        val updated = api.updateVehicle(session.token, vehicleId, draft)
        return update(session.user.id, mutation = true) { previous ->
            previous.copy(vehicles = previous.vehicles.map { vehicle -> when {
                vehicle.id == updated.id -> if ((documentRevisions[documentKey] ?: 0L) != documentRevision) updated.copy(documents = vehicle.documents) else updated
                updated.isPrimary -> vehicle.copy(isPrimary = false)
                else -> vehicle
            } }.sortedVehicles(), selectedVehicleId = updated.id, updatedAtEpochMillis = nowEpochMillis())
        }
    }

    suspend fun saveMaintenance(session: AppSession, editingRecordId: String?, draft: MaintenanceDraft): AutoSnapshot {
        beginMutation(session.user.id)
        val saved = if (editingRecordId == null) api.createMaintenance(session.token, draft) else api.updateMaintenance(session.token, editingRecordId, draft)
        return update(session.user.id, mutation = true) { previous ->
            val records = previous.maintenance.filterNot { saved.id != null && it.id == saved.id } + saved
            previous.copy(maintenance = records.sortedByDescending(MaintenanceRecord::date), updatedAtEpochMillis = nowEpochMillis())
        }
    }

    suspend fun uploadDocument(session: AppSession, vehicleId: String, input: VehicleDocumentInput, onProgress: (Double) -> Unit): AutoSnapshot {
        beginMutation(session.user.id)
        val document = api.uploadDocument(session.token, vehicleId, input, onProgress)
        return update(session.user.id, mutation = true) { previous ->
            val key = session.user.id to vehicleId
            documentRevisions[key] = (documentRevisions[key] ?: 0L) + 1
            previous.copy(vehicles = previous.vehicles.map { vehicle ->
                if (vehicle.id == vehicleId) vehicle.copy(documents = vehicle.documents.orEmpty().filterNot { it.id == document.id } + document) else vehicle
            })
        }
    }

    suspend fun downloadDocument(session: AppSession, vehicleId: String, documentId: String): ByteArray =
        api.downloadDocument(session.token, vehicleId, documentId)

    suspend fun deleteDocument(session: AppSession, vehicleId: String, documentId: String): AutoSnapshot {
        beginMutation(session.user.id)
        api.deleteDocument(session.token, vehicleId, documentId)
        return update(session.user.id, mutation = true) { previous ->
            val key = session.user.id to vehicleId
            documentRevisions[key] = (documentRevisions[key] ?: 0L) + 1
            previous.copy(vehicles = previous.vehicles.map { vehicle ->
                if (vehicle.id == vehicleId) vehicle.copy(documents = vehicle.documents.orEmpty().filterNot { it.id == documentId }) else vehicle
            })
        }
    }

    private suspend fun beginMutation(owner: String) = cacheMutex.withLock {
        currentCoroutineContext().ensureActive()
        mutationRevisions[owner] = (mutationRevisions[owner] ?: 0L) + 1
    }

    private suspend fun update(owner: String, mutation: Boolean = false, transform: (AutoSnapshot) -> AutoSnapshot): AutoSnapshot = cacheMutex.withLock {
        currentCoroutineContext().ensureActive()
        val previous = read(owner) ?: AutoSnapshot()
        if (mutation) mutationRevisions[owner] = (mutationRevisions[owner] ?: 0L) + 1
        transform(previous).also { snapshot ->
            if (snapshot != previous) cache.save(owner, snapshot)
            currentCoroutineContext().ensureActive()
            mutableChanges.tryEmit(owner to snapshot)
        }
    }

    private suspend fun synchronizeLegacyVehicle(session: AppSession, vehicles: List<Vehicle>): List<Vehicle> {
        val index = vehicles.indexOfFirst(Vehicle::isPrimary).takeIf { it >= 0 } ?: vehicles.indices.firstOrNull() ?: return vehicles
        val current = vehicles[index]
        val merged = current.fillMissing(session.user.profile)
        if (VehicleDraft(current) == VehicleDraft(merged)) return vehicles
        val synced = try { api.updateVehicle(session.token, current.id, VehicleDraft(merged)) }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { merged }
        return vehicles.toMutableList().also { it[index] = synced }
    }

    private fun List<Vehicle>.sortedVehicles(): List<Vehicle> = sortedWith(
        compareByDescending<Vehicle>(Vehicle::isPrimary).thenBy(String.CASE_INSENSITIVE_ORDER, Vehicle::displayName),
    )
}
