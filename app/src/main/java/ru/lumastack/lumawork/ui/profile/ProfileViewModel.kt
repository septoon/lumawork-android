package ru.lumastack.lumawork.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.lumastack.lumawork.data.auth.AuthFailure
import ru.lumastack.lumawork.data.auth.AuthRepository
import ru.lumastack.lumawork.data.auto.AutoRepository
import ru.lumastack.lumawork.domain.auto.AutoSnapshot
import ru.lumastack.lumawork.domain.auto.VehicleDraft
import ru.lumastack.lumawork.domain.auto.VehicleInputValidation
import ru.lumastack.lumawork.domain.model.AppSession
import ru.lumastack.lumawork.domain.model.UserProfile

data class ProfileUiState(
    val isOpen: Boolean = false,
    val draft: UserProfile = UserProfile(),
    val vehicle: VehicleDraft = VehicleDraft(isPrimary = true),
    val vehicleId: String? = null,
    val vehicleReady: Boolean = false,
    val vehicleEdited: Boolean = false,
    val vehicleError: String? = null,
    val busy: Boolean = false,
    val error: String? = null,
    val saved: Boolean = false,
)

class ProfileViewModel(private val auth: AuthRepository, private val auto: AutoRepository) : ViewModel() {
    private val mutable = MutableStateFlow(ProfileUiState())
    val state = mutable.asStateFlow()
    private var loadJob: Job? = null
    private var mutationJob: Job? = null
    private var changesJob: Job? = null
    private var session: AppSession? = null
    private var offline = false
    private var generation = 0L

    fun open(session: AppSession, offline: Boolean) {
        if (state.value.isOpen && this.session?.token == session.token && this.session?.user?.id == session.user.id) {
            val wasOffline = this.offline
            this.session = session
            this.offline = offline
            if (wasOffline && !offline) loadVehicles(session, offline)
            return
        }
        close()
        this.session = session
        this.offline = offline
        val profile = session.user.profile ?: UserProfile()
        mutable.value = ProfileUiState(isOpen = true, draft = profile, vehicle = profile.vehicleDraft())
        changesJob = viewModelScope.launch {
            auto.changes.collect { (owner, snapshot) -> if (owner == session.user.id) applyVehicle(snapshot) }
        }
        loadVehicles(session, offline)
    }

    fun retryVehicles() { session?.let { if (!offline && !state.value.busy) loadVehicles(it, false) } }

    private fun loadVehicles(session: AppSession, offline: Boolean) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            try {
                val cached = auto.read(session.user.id)
                if (cached != null) applyVehicle(cached)
                if (!offline) applyVehicle(auto.refresh(session))
                else if (cached == null) mutable.update { it.copy(vehicleError = "Подключитесь к интернету, чтобы загрузить автомобили.") }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                mutable.update { it.copy(vehicleError = "Не удалось обновить автомобиль. Сохранённые данные доступны в разделе «Авто».") }
            }
        }
    }

    private fun applyVehicle(snapshot: AutoSnapshot) {
        val vehicle = snapshot.vehicles.firstOrNull { it.isPrimary }
            ?: snapshot.vehicles.firstOrNull { it.id == snapshot.selectedVehicleId }
            ?: snapshot.vehicles.firstOrNull()
        mutable.update {
            if (it.vehicleEdited) it else it.copy(
                vehicle = vehicle?.let(::VehicleDraft)?.copy(isPrimary = true) ?: it.draft.vehicleDraft(),
                vehicleId = vehicle?.id, vehicleReady = true, vehicleError = null,
            )
        }
    }

    fun edit(profile: UserProfile) { if (!state.value.busy) mutable.update { it.copy(draft = profile, error = null) } }
    fun editVehicle(draft: VehicleDraft) { if (!state.value.busy && state.value.vehicleReady) mutable.update { it.copy(vehicle = draft.copy(isPrimary = true), vehicleEdited = true, error = null) } }
    fun showError(message: String) = mutable.update { it.copy(error = message) }
    fun close() {
        generation++
        loadJob?.cancel(); mutationJob?.cancel(); changesJob?.cancel()
        session = null
        mutable.value = ProfileUiState()
    }

    fun save() {
        val current = state.value
        if (current.busy || !current.isOpen) return
        if (offline) { showError("Подключитесь к интернету, чтобы сохранить профиль."); return }
        profileValidationError(current.draft)?.let { showError(it); return }
        if (current.vehicleEdited) {
            val draft = current.vehicle
            val error = draft.licensePlate.takeIf(String::isNotBlank)?.let(VehicleInputValidation::plateError)
                ?: VehicleInputValidation.vinError(draft.vin)
            if (error != null) { showError(error); return }
        }
        mutate { session ->
            val profile = current.draft.copy(
                personalPhone = current.draft.personalPhone?.trim(),
                workEmail = current.draft.workEmail?.trim(),
            ).let { if (current.vehicleEdited) it.withVehicle(current.vehicle) else it }
            auth.updateProfile(profile)
            currentCoroutineContext().ensureActive()
            if (current.vehicleEdited && (current.vehicleId != null || current.vehicle.hasData())) {
                if (current.vehicleId == null) auto.createVehicle(session, current.vehicle)
                else auto.updateVehicle(session, current.vehicleId, current.vehicle)
            }
            mutable.update { it.copy(saved = true) }
        }
    }

    fun uploadAvatar(readImage: suspend () -> ByteArray) = mutate {
        val bytes = readImage()
        currentCoroutineContext().ensureActive()
        checkSession(it)
        auth.uploadAvatar(bytes)
    }
    fun deleteAvatar() = mutate { auth.deleteAvatar() }

    private fun mutate(action: suspend (AppSession) -> Unit) {
        if (state.value.busy || !state.value.isOpen) return
        if (offline) { showError("Подключитесь к интернету, чтобы сохранить профиль."); return }
        val session = session ?: return
        val activeGeneration = generation
        mutable.update { it.copy(busy = true, error = null) }
        mutationJob = viewModelScope.launch {
            try { checkSession(session); action(session) }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { if (generation == activeGeneration) showError(if (error is AuthFailure) error.message.orEmpty() else error.message ?: "Не удалось сохранить профиль.") }
            finally { if (generation == activeGeneration) mutable.update { it.copy(busy = false) } }
        }
    }

    private fun checkSession(expected: AppSession) {
        check(auth.session?.token == expected.token && auth.session?.user?.id == expected.user.id) { "Сессия изменилась. Войдите заново." }
    }

    companion object {
        fun factory(auth: AuthRepository, auto: AutoRepository) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = ProfileViewModel(auth, auto) as T
        }
    }
}

fun profileValidationError(profile: UserProfile): String? = profile.workEmail?.trim()
    ?.takeIf { it.isNotEmpty() && !Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$").matches(it) }
    ?.let { "Проверьте формат почты" }

private fun UserProfile.withVehicle(vehicle: VehicleDraft): UserProfile = copy(
    vehicleModel = vehicle.customName.trim().ifBlank { listOf(vehicle.make, vehicle.model, vehicle.generation).map(String::trim).filter(String::isNotBlank).joinToString(" ") },
    vehiclePlate = vehicle.licensePlate.trim(), vehicleVin = vehicle.vin.trim(), vehicleSts = vehicle.sts.trim(),
    vehiclePts = vehicle.pts.trim(), vehicleColor = vehicle.colorName.trim(), engineVolumeCm3 = vehicle.engineVolumeCm3.trim(),
    enginePowerHp = vehicle.enginePowerHp.trim(), initialMileageKm = vehicle.currentMileageKm.trim(),
)

private fun UserProfile.vehicleDraft() = VehicleDraft(
    licensePlate = vehiclePlate.orEmpty().trim(), vin = vehicleVin.orEmpty().trim(),
    sts = vehicleSts.orEmpty().trim(), pts = vehiclePts.orEmpty().trim(),
    model = vehicleModel.orEmpty().trim(), customName = vehicleModel.orEmpty().trim(),
    colorName = vehicleColor.orEmpty().trim(), currentMileageKm = initialMileageKm.orEmpty().filter(Char::isDigit),
    engineVolumeCm3 = engineVolumeCm3.orEmpty().filter(Char::isDigit),
    enginePowerHp = enginePowerHp.orEmpty().filter(Char::isDigit), isPrimary = true,
)

private fun VehicleDraft.hasData() = listOf(licensePlate, vin, sts, pts, make, model, generation, year, bodyType,
    colorName, currentMileageKm, engineVolumeCm3, enginePowerHp, customName).any(String::isNotBlank)
