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
    val editingMaintenanceRecord: MaintenanceRecord? = null,
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
    private var saveJob: Job? = null
    private var selectionJob: Job? = null
    private var generation = 0L
    private var pendingSelection: String? = null

    init {
        viewModelScope.launch {
            repository.changes.collect { (owner, snapshot) ->
                if (owner == session.user.id) publish(snapshot)
            }
        }
        loadJob = viewModelScope.launch {
            val cached = runCatching { repository.read(session.user.id) }.getOrNull()
            mutableState.update { it.copy(snapshot = cached ?: AutoSnapshot()) }
            if (!startOffline) refreshCurrent(showError = cached == null)
        }
    }

    fun updateSession(value: AppSession) {
        if (value.user.id != session.user.id) return
        val changed = session.token != value.token
        session = value
        if (changed) {
            ++generation
            loadJob?.cancel(); saveJob?.cancel(); selectionJob?.cancel()
            mutableState.update { it.copy(isSaving = false, isLoading = false) }
            if (!startOffline) refresh()
        }
    }

    fun refresh() {
        if (startOffline || state.value.isSaving) return
        loadJob?.cancel()
        loadJob = viewModelScope.launch { refreshCurrent(showError = true) }
    }

    fun selectVehicle(vehicleId: String) {
        if (state.value.snapshot.vehicles.none { it.id == vehicleId }) return
        selectionJob?.cancel()
        pendingSelection = vehicleId
        mutableState.update { it.copy(snapshot = it.snapshot.copy(selectedVehicleId = vehicleId)) }
        selectionJob = viewModelScope.launch {
            try {
                val snapshot = repository.select(session.user.id, vehicleId)
                if (pendingSelection == vehicleId) publish(snapshot)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { mutableState.update { it.copy(errorMessage = error.userMessage()) } }
            finally { if (pendingSelection == vehicleId) pendingSelection = null }
        }
    }

    fun addVehicle() {
        mutableState.update {
            it.copy(
                vehicleEditor = VehicleDraft(),
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
        if (state.value.isSaving) return
        mutableState.update { it.copy(vehicleEditor = null, editingVehicleId = null, editorErrorMessage = null) }
    }

    fun saveVehicle() {
        if (state.value.isSaving) return
        if (startOffline) { mutableState.update { it.copy(editorErrorMessage = "Нет подключения. Сохранить автомобиль можно после входа онлайн.") }; return }
        val draft = mutableState.value.vehicleEditor ?: return
        val error = vehicleDraftError(draft)
        if (error != null) {
            mutableState.update { it.copy(editorErrorMessage = error) }
            return
        }
        val editingId = state.value.editingVehicleId
        val revision = generation
        val activeSession = session
        mutableState.update { it.copy(isSaving = true, editorErrorMessage = null, errorMessage = null) }
        saveJob = viewModelScope.launch {
            try {
                val snapshot = editingId?.let { id ->
                    repository.updateVehicle(activeSession, id, draft)
                } ?: repository.createVehicle(activeSession, draft)
                if (generation != revision) return@launch
                mutableState.update {
                    it.copy(
                        snapshot = snapshot,
                        vehicleEditor = null,
                        editingVehicleId = null,
                        isSaving = false,
                        notice = if (editingId == null) "Автомобиль добавлен." else "Автомобиль обновлён.",
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
                editingMaintenanceRecord = null,
                editorErrorMessage = null,
            )
        }
    }

    fun editMaintenance(record: MaintenanceRecord) {
        mutableState.update {
            it.copy(
                maintenanceEditor = MaintenanceDraft(record, it.snapshot.selectedVehicleId),
                editingMaintenanceId = record.id,
                editingMaintenanceRecord = record,
                editorErrorMessage = null,
            )
        }
    }

    fun updateMaintenanceDraft(draft: MaintenanceDraft) {
        mutableState.update { it.copy(maintenanceEditor = draft, editorErrorMessage = null) }
    }

    fun closeMaintenanceEditor() {
        if (state.value.isSaving) return
        mutableState.update { it.copy(maintenanceEditor = null, editingMaintenanceId = null, editingMaintenanceRecord = null, editorErrorMessage = null) }
    }

    fun saveMaintenance() {
        if (state.value.isSaving) return
        if (startOffline) { mutableState.update { it.copy(editorErrorMessage = "Нет подключения. Сохранить обслуживание можно после входа онлайн.") }; return }
        if (state.value.editingMaintenanceRecord != null && state.value.editingMaintenanceId.isNullOrBlank()) {
            mutableState.update { it.copy(editorErrorMessage = "Нельзя обновить запись без id.") }; return
        }
        val draft = mutableState.value.maintenanceEditor ?: return
        val error = maintenanceDraftError(draft)
        if (error != null) {
            mutableState.update { it.copy(editorErrorMessage = error) }
            return
        }
        val editingId = state.value.editingMaintenanceId
        val activeSession = session
        val revision = generation
        mutableState.update { it.copy(isSaving = true, editorErrorMessage = null, errorMessage = null) }
        saveJob = viewModelScope.launch {
            try {
                val snapshot = repository.saveMaintenance(activeSession, editingId, draft)
                if (generation != revision) return@launch
                mutableState.update {
                    it.copy(
                        snapshot = snapshot,
                        maintenanceEditor = null,
                        editingMaintenanceId = null,
                        editingMaintenanceRecord = null,
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
            val revision = generation
            val snapshot = repository.refresh(session)
            if (generation == revision) {
                publish(snapshot)
                mutableState.update { it.copy(isLoading = false) }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            mutableState.update {
                it.copy(isLoading = false, errorMessage = if (showError) error.userMessage() else null)
            }
        }
    }

    private fun publish(snapshot: AutoSnapshot) {
        val selected = pendingSelection?.takeIf { id -> snapshot.vehicles.any { it.id == id } } ?: snapshot.selectedVehicleId
        mutableState.update { it.copy(snapshot = snapshot.copy(selectedVehicleId = selected)) }
    }

    private fun vehicleDraftError(draft: VehicleDraft): String? = ru.lumastack.lumawork.domain.auto.VehicleDraftValidation.error(draft)

    private fun maintenanceDraftError(draft: MaintenanceDraft): String? = when {
        state.value.snapshot.vehicles.none { it.id == draft.vehicleId } -> "Выберите автомобиль."
        runCatching { LocalDate.parse(draft.date) }.isFailure -> "Введите дату в формате ГГГГ-ММ-ДД."
        draft.procedure.isBlank() -> "Укажите выполненную работу."
        draft.mileage.toIntOrNull()?.let { it >= 0 } != true -> "Пробег должен быть неотрицательным числом."
        draft.parts.any { part ->
            !(part.name.isBlank() && part.cost.isBlank()) && (part.name.isBlank() || part.cost.replace(',', '.').toDoubleOrNull()?.let { it.isFinite() && it >= 0 } != true)
        } -> "Для каждой запчасти укажите название и неотрицательную стоимость."
        draft.workCost.isNotBlank() && draft.workCost.replace(',', '.').toDoubleOrNull()?.let { it.isFinite() && it >= 0 } != true -> "Проверьте стоимость работы."
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
