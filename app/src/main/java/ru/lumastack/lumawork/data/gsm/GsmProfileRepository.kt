package ru.lumastack.lumawork.data.gsm

import android.content.Context
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import ru.lumastack.lumawork.data.auto.AutoFailure
import ru.lumastack.lumawork.data.auto.AutoRepository
import ru.lumastack.lumawork.domain.auto.VehicleDraft
import ru.lumastack.lumawork.domain.auto.VehicleGsmSettings
import ru.lumastack.lumawork.domain.model.AppSession

interface GsmProfileCache {
    suspend fun read(owner: String): GsmProfileSnapshot?
    suspend fun save(owner: String, snapshot: GsmProfileSnapshot)
}

class FileGsmProfileCache(context: Context, private val json: Json) : GsmProfileCache {
    private val directory = File(context.noBackupFilesDir, "gsm-profile")
    override suspend fun read(owner: String): GsmProfileSnapshot? = withContext(Dispatchers.IO) {
        val source = file(owner)
        if (!source.isFile) null else runCatching { json.decodeFromString<GsmProfileSnapshot>(source.readText()) }.getOrNull()
    }

    override suspend fun save(owner: String, snapshot: GsmProfileSnapshot) = withContext(Dispatchers.IO) {
        val target = file(owner)
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, "${target.name}.tmp")
        temporary.writeText(json.encodeToString(snapshot))
        if (!temporary.renameTo(target)) { target.writeText(temporary.readText()); temporary.delete() }
    }

    private fun file(owner: String) = File(directory, MessageDigest.getInstance("SHA-256").digest(owner.toByteArray()).joinToString("") { "%02x".format(it) } + ".json")
}

class GsmProfileRepository(
    private val api: GsmProfileApi,
    private val cache: GsmProfileCache,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private val revisions = mutableMapOf<String, Long>()
    suspend fun read(owner: String): GsmProfileSnapshot? = cache.read(owner)?.let { it.copy(profile = it.profile.copy(employeePhone = GsmPhoneFormatter.format(it.profile.employeePhone))) }

    suspend fun refresh(owner: String, token: String): GsmProfileSnapshot {
        val revision = mutex.withLock { revisions[owner] ?: 0L }
        val loaded = api.fetch(token)
        val projects = try { api.projects(token) } catch (error: CancellationException) { throw error } catch (_: Exception) { null }
        return mutex.withLock {
            currentCoroutineContext().ensureActive()
            val previous = read(owner) ?: GsmProfileSnapshot()
            if ((revisions[owner] ?: 0L) != revision) previous
            else GsmProfileSnapshot(loaded.profile, projects ?: previous.projects, loaded.availableFuelTypes, now()).also { cache.save(owner, it) }
        }
    }

    suspend fun save(owner: String, token: String, profile: GsmProfile): GsmProfileSnapshot {
        val normalized = profile.normalizedForSave()
        mutex.withLock { revisions[owner] = (revisions[owner] ?: 0L) + 1L }
        val saved = api.save(token, normalized)
        return mutex.withLock {
            currentCoroutineContext().ensureActive()
            revisions[owner] = (revisions[owner] ?: 0L) + 1L
            val previous = read(owner) ?: GsmProfileSnapshot()
            previous.copy(profile = saved, updatedAtEpochMillis = now()).also { cache.save(owner, it) }
        }
    }

    /** Called only by the explicit Save action; opening/importing a draft never creates a vehicle. */
    suspend fun saveWithVehicle(session: AppSession, profile: GsmProfile, auto: AutoRepository): GsmProfileSnapshot {
        val normalized = profile.normalizedForSave()
        if (normalized.carModel.isBlank() || normalized.licensePlate.isBlank())
            throw GsmProfileFailure.Validation("Заполните модель и госномер автомобиля.")
        // Do not migrate unrelated AppUser legacy fields while resolving a GSM vehicle.
        val readSession = session.copy(user = session.user.copy(profile = null))
        val vehicles = auto.refresh(readSession).vehicles
        val candidates = normalized.matchingVehicles(vehicles)
        if (candidates.size > 1) throw GsmProfileFailure.Validation("Найдено несколько автомобилей с этим госномером. Выберите автомобиль вручную.")
        if (normalized.vehicleId != null && candidates.isEmpty())
            throw GsmProfileFailure.Validation("Выбранный автомобиль не найден. Выберите автомобиль заново.")
        val existing = candidates.singleOrNull()
        // Validate/persist the complete profile before creating any new Auto record.
        val accepted = save(session.user.id, session.token, normalized.copy(vehicleId = existing?.id))
        try {
            val vehicle = existing ?: try {
                val created = auto.createVehicle(session, VehicleDraft(model = accepted.profile.carModel,
                    customName = accepted.profile.carModel, licensePlate = accepted.profile.licensePlate,
                    currentMileageKm = accepted.profile.defaultStartOdometer.toString(), isPrimary = vehicles.isEmpty()))
                created.vehicles.single { it.id == created.selectedVehicleId }
            } catch (error: AutoFailure.Http) {
                if (error.status != 409) throw error
                // A previous timed-out POST or concurrent save may already have created this plate.
                accepted.profile.matchingVehicles(auto.refresh(readSession).vehicles).singleOrNull() ?: throw error
            }
            val linked = if (existing != null) accepted else save(session.user.id, session.token, accepted.profile.copy(vehicleId = vehicle.id))
            auto.applyGsmSettings(session.user.id, vehicle.id, VehicleGsmSettings(linked.profile.fuelNorm, linked.profile.fuelType,
                linked.profile.defaultStartOdometer, linked.profile.reportStartMonth))
            return linked
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) { throw GsmProfileFailure.VehicleSync(error) }
    }
}
