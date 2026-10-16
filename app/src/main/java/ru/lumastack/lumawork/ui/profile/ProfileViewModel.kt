package ru.lumastack.lumawork.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
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

    fun open(session: AppSession, offline: Boolean) {
        if (state.value.isOpen) return
        mutable.value = ProfileUiState(isOpen = true, draft = session.user.profile ?: UserProfile())
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
                vehicle = vehicle?.let(::VehicleDraft)?.copy(isPrimary = true) ?: VehicleDraft(isPrimary = true),
                vehicleId = vehicle?.id, vehicleReady = true, vehicleError = null,
            )
        }
    }

    fun edit(profile: UserProfile) { if (!state.value.busy) mutable.update { it.copy(draft = profile, error = null) } }
    fun editVehicle(draft: VehicleDraft) { if (!state.value.busy) mutable.update { it.copy(vehicle = draft.copy(isPrimary = true), vehicleEdited = true, error = null) } }
    fun showError(message: String) = mutable.update { it.copy(error = message) }
    fun close() { loadJob?.cancel(); mutable.value = ProfileUiState() }

    fun save() {
        val current = state.value
        if (current.busy) return
        profileValidationError(current.draft)?.let { showError(it); return }
        if (current.vehicleEdited) {
            val draft = current.vehicle
            val error = VehicleInputValidation.plateError(draft.licensePlate)
                ?: VehicleInputValidation.vinError(draft.vin)
                ?: if (draft.make.isBlank() || draft.model.isBlank()) "Укажите марку и модель автомобиля." else null
            if (error != null) { showError(error); return }
        }
        mutate {
            val profile = current.draft.copy(
                personalPhone = current.draft.personalPhone?.trim(),
                workEmail = current.draft.workEmail?.trim(),
            ).let { if (current.vehicleEdited) it.withVehicle(current.vehicle) else it }
            val session = auth.updateProfile(profile)
            if (current.vehicleEdited) {
                if (current.vehicleId == null) auto.createVehicle(session, current.vehicle)
                else auto.updateVehicle(session, current.vehicleId, current.vehicle)
            }
            mutable.update { it.copy(saved = true) }
        }
    }

    fun uploadAvatar(readImage: suspend () -> ByteArray) = mutate { auth.uploadAvatar(readImage()) }
    fun deleteAvatar() = mutate { auth.deleteAvatar() }

    private fun mutate(action: suspend () -> Unit) {
        if (state.value.busy) return
        mutable.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try { action() }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { showError(if (error is AuthFailure) error.message.orEmpty() else error.message ?: "Не удалось сохранить профиль.") }
            finally { mutable.update { it.copy(busy = false) } }
        }
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
