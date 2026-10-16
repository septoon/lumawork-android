package ru.lumastack.lumawork.ui.auto

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.lumastack.lumawork.data.auto.AutoFailure
import ru.lumastack.lumawork.data.auto.AutoRepository
import ru.lumastack.lumawork.domain.auto.AutoSnapshot
import ru.lumastack.lumawork.domain.auto.MaintenanceDraft
import ru.lumastack.lumawork.domain.auto.MaintenanceRecord
import ru.lumastack.lumawork.domain.auto.Vehicle
import ru.lumastack.lumawork.domain.auto.VehicleDraft
import ru.lumastack.lumawork.domain.model.AppSession

data class AutoUiState(
    val snapshot: AutoSnapshot = AutoSnapshot(),
    val vehicleEditor: VehicleDraft? = null,
    val editingVehicleId: String? = null,
    val maintenanceEditor: MaintenanceDraft? = null,
    val editingMaintenanceId: String? = null,
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val errorMessage: String? = null,
    val editorErrorMessage: String? = null,
    val notice: String? = null,
) {
    val selectedVehicle: Vehicle?
        get() = snapshot.vehicles.firstOrNull { it.id == snapshot.selectedVehicleId }
    val selectedMaintenance: List<MaintenanceRecord>
        get() = selectedVehicle?.let { selected ->
            snapshot.maintenance.filter { record ->
                record.vehicleId == selected.id || (record.vehicleId == null && snapshot.vehicles.size == 1)
            }
        }.orEmpty()
}

class AutoViewModel(
    private val repository: AutoRepository,
    private var session: AppSession,
    private val startOffline: Boolean,
) : ViewModel() {
    private val mutableState = MutableStateFlow(AutoUiState())
    val state: StateFlow<AutoUiState> = mutableState.asStateFlow()
    private var loadJob: Job? = null

    init {
        viewModelScope.launch {
            repository.changes.collect { (owner, snapshot) ->
                if (owner == session.user.id) mutableState.update { it.copy(snapshot = snapshot) }
            }
        }
        loadJob = viewModelScope.launch {
            val cached = runCatching { repository.read(session.user.id) }.getOrNull()
            mutableState.update { it.copy(snapshot = cached ?: AutoSnapshot()) }
            if (!startOffline) refreshCurrent(showError = cached == null)
        }
    }

    fun updateSession(value: AppSession) {
        if (value.user.id == session.user.id) session = value
    }

    fun refresh() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch { refreshCurrent(showError = true) }
    }

    fun selectVehicle(vehicleId: String) {
        mutableState.update { it.copy(snapshot = it.snapshot.copy(selectedVehicleId = vehicleId)) }
        viewModelScope.launch {
            val snapshot = repository.select(session.user.id, vehicleId)
            mutableState.update { it.copy(snapshot = snapshot) }
        }
    }

    fun addVehicle() {
        mutableState.update {
            it.copy(
                vehicleEditor = VehicleDraft(isPrimary = it.snapshot.vehicles.isEmpty()),
                editingVehicleId = null,
                editorErrorMessage = null,
            )
        }
    }

    fun editVehicle(vehicle: Vehicle) {
        mutableState.update {
            it.copy(vehicleEditor = VehicleDraft(vehicle), editingVehicleId = vehicle.id, editorErrorMessage = null)
        }
    }

    fun updateVehicleDraft(draft: VehicleDraft) {
        mutableState.update { it.copy(vehicleEditor = draft, editorErrorMessage = null) }
    }

    fun closeVehicleEditor() {
        mutableState.update { it.copy(vehicleEditor = null, editingVehicleId = null, editorErrorMessage = null) }
    }

    fun saveVehicle() {
        val draft = mutableState.value.vehicleEditor ?: return
        val error = vehicleDraftError(draft)
        if (error != null) {
            mutableState.update { it.copy(editorErrorMessage = error) }
            return
        }
        viewModelScope.launch {
            mutableState.update { it.copy(isSaving = true, editorErrorMessage = null, errorMessage = null) }
            try {
                val snapshot = mutableState.value.editingVehicleId?.let { id ->
                    repository.updateVehicle(session, id, draft)
                } ?: repository.createVehicle(session, draft)
                mutableState.update {
                    it.copy(
                        snapshot = snapshot,
                        vehicleEditor = null,
                        editingVehicleId = null,
                        isSaving = false,
                        notice = if (mutableState.value.editingVehicleId == null) "Автомобиль добавлен." else "Автомобиль обновлён.",
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableState.update { it.copy(isSaving = false, editorErrorMessage = error.userMessage()) }
            }
        }
    }

    fun addMaintenance() {
        val vehicle = mutableState.value.selectedVehicle ?: return
        mutableState.update {
            it.copy(
                maintenanceEditor = MaintenanceDraft(
                    vehicleId = vehicle.id,
                    date = LocalDate.now().toString(),
                    mileage = vehicle.currentMileageKm?.toString().orEmpty(),
                ),
                editingMaintenanceId = null,
                editorErrorMessage = null,
            )
        }
    }

    fun editMaintenance(record: MaintenanceRecord) {
        mutableState.update {
            it.copy(
                maintenanceEditor = MaintenanceDraft(record, it.snapshot.selectedVehicleId),
                editingMaintenanceId = record.id,
                editorErrorMessage = null,
            )
        }
    }

    fun updateMaintenanceDraft(draft: MaintenanceDraft) {
        mutableState.update { it.copy(maintenanceEditor = draft, editorErrorMessage = null) }
    }

    fun closeMaintenanceEditor() {
        mutableState.update { it.copy(maintenanceEditor = null, editingMaintenanceId = null, editorErrorMessage = null) }
    }

    fun saveMaintenance() {
        val draft = mutableState.value.maintenanceEditor ?: return
        val error = maintenanceDraftError(draft)
        if (error != null) {
            mutableState.update { it.copy(editorErrorMessage = error) }
            return
        }
        viewModelScope.launch {
            mutableState.update { it.copy(isSaving = true, editorErrorMessage = null, errorMessage = null) }
            try {
                val editingId = mutableState.value.editingMaintenanceId
                val snapshot = repository.saveMaintenance(session, editingId, draft)
                mutableState.update {
                    it.copy(
                        snapshot = snapshot,
                        maintenanceEditor = null,
                        editingMaintenanceId = null,
                        isSaving = false,
                        notice = if (editingId == null) "Запись обслуживания добавлена." else "Запись обслуживания обновлена.",
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableState.update { it.copy(isSaving = false, editorErrorMessage = error.userMessage()) }
            }
        }
    }

    private suspend fun refreshCurrent(showError: Boolean) {
        mutableState.update { it.copy(isLoading = true, errorMessage = null) }
        try {
            val snapshot = repository.refresh(session)
            mutableState.update { it.copy(snapshot = snapshot, isLoading = false) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            mutableState.update {
                it.copy(isLoading = false, errorMessage = if (showError) error.userMessage() else null)
            }
        }
    }

    private fun vehicleDraftError(draft: VehicleDraft): String? =
        ru.lumastack.lumawork.domain.auto.VehicleInputValidation.plateError(draft.licensePlate)
            ?: ru.lumastack.lumawork.domain.auto.VehicleInputValidation.vinError(draft.vin)
            ?: if (draft.make.isBlank() || draft.model.isBlank()) "Укажите марку и модель." else null

    private fun maintenanceDraftError(draft: MaintenanceDraft): String? = when {
        draft.vehicleId.isNullOrBlank() -> "Выберите автомобиль."
        runCatching { LocalDate.parse(draft.date) }.isFailure -> "Введите дату в формате ГГГГ-ММ-ДД."
        draft.procedure.isBlank() -> "Укажите выполненную работу."
        draft.mileage.toIntOrNull() == null -> "Укажите пробег целым числом."
        draft.parts.any { it.name.isBlank() || it.cost.replace(',', '.').toDoubleOrNull()?.let { cost -> cost >= 0 } != true } ->
            "Проверьте названия и стоимость запчастей."
        draft.workCost.isNotBlank() && draft.workCost.replace(',', '.').toDoubleOrNull()?.let { it >= 0 } != true ->
            "Проверьте стоимость работы."
        else -> null
    }

    private fun Throwable.userMessage(): String = when (this) {
        is AutoFailure -> message ?: "Не удалось выполнить запрос."
        else -> "Не удалось сохранить данные раздела «Авто»."
    }

    companion object {
        fun factory(
            repository: AutoRepository,
            session: AppSession,
            startOffline: Boolean,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                AutoViewModel(repository, session, startOffline) as T
        }
    }
}
