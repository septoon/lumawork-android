package ru.lumastack.lumawork.ui.fuel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import java.time.YearMonth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.lumastack.lumawork.data.fuel.FuelFailure
import ru.lumastack.lumawork.data.fuel.FuelRepository
import ru.lumastack.lumawork.data.route.RouteRepository
import ru.lumastack.lumawork.domain.fuel.FuelArchivePolicy
import ru.lumastack.lumawork.domain.fuel.FuelAdjustmentKind
import ru.lumastack.lumawork.domain.fuel.FuelDraft
import ru.lumastack.lumawork.domain.fuel.FuelRecord
import ru.lumastack.lumawork.domain.fuel.FuelRecordType
import ru.lumastack.lumawork.domain.fuel.FuelSnapshot
import ru.lumastack.lumawork.domain.fuel.FuelSummary
import ru.lumastack.lumawork.domain.fuel.FuelSummaryCalculator
import ru.lumastack.lumawork.domain.fuel.validateFuelDraft
import ru.lumastack.lumawork.domain.model.AppSession

data class FuelUiState(
    val snapshot: FuelSnapshot = FuelSnapshot(),
    val selectedYear: String? = null,
    val expandedMonthKey: String? = null,
    val editorDraft: FuelDraft? = null,
    val editingRecordId: String? = null,
    val editingRecord: FuelRecord? = null,
    val isOffline: Boolean = false,
    val pendingDelete: FuelRecord? = null,
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val errorMessage: String? = null,
    val editorErrorMessage: String? = null,
    val notice: String? = null,
) {
    val summary: FuelSummary get() = FuelSummaryCalculator.build(currentRecords)
    val years: List<String> get() = FuelSummaryCalculator.years(summary)
    val visibleMonths get() = FuelSummaryCalculator.months(summary, selectedYear)
    val currentRecords: List<FuelRecord> get() = snapshot.records.filter(FuelArchivePolicy::isCurrent)
    val archivedCount: Int get() = snapshot.records.count(FuelArchivePolicy::isArchived)

    companion object { const val CURRENT_RECORDS_START_DATE = "2026-05-13" }
}

class FuelViewModel(
    private val repository: FuelRepository,
    private val routeRepository: RouteRepository,
    private val session: AppSession,
    private val startOffline: Boolean,
) : ViewModel() {
    private val mutableState = MutableStateFlow(FuelUiState(isOffline = startOffline))
    val state: StateFlow<FuelUiState> = mutableState.asStateFlow()
    private var loadJob: Job? = null
    private var hasLoaded = false

    init {
        viewModelScope.launch { repository.changes.collect { (owner, snapshot) -> if (owner == session.user.id) applySnapshot(snapshot) } }
        loadJob = viewModelScope.launch {
            val cached = try { repository.read(session.user.id) } catch (error: CancellationException) { throw error } catch (_: Exception) { null }
            applySnapshot(cached ?: FuelSnapshot())
            syncRouteMileage(allowRemote = false)
            if (!startOffline) refreshCurrent(showError = cached == null)
        }
    }

    fun reloadCached() {
        viewModelScope.launch {
            val cached = repository.read(session.user.id) ?: return@launch
            if ((cached.updatedAtEpochMillis ?: 0) >= (state.value.snapshot.updatedAtEpochMillis ?: 0)) applySnapshot(cached)
        }
    }

    fun refresh() {
        if (startOffline || state.value.isSaving) return
        loadJob?.cancel()
        loadJob = viewModelScope.launch { refreshCurrent(showError = true) }
    }

    fun selectYear(year: String) { mutableState.update { it.copy(selectedYear = year) } }
    fun toggleMonth(monthKey: String) {
        mutableState.update { it.copy(expandedMonthKey = if (it.expandedMonthKey == monthKey) null else monthKey) }
    }

    fun addFuel() { if (!state.value.isSaving) mutableState.update { it.copy(editorDraft = FuelDraft(fuelType = it.snapshot.fuelTypes.firstOrNull().orEmpty()), editingRecord = null, editingRecordId = null, editorErrorMessage = null) } }
    fun addAdjustment(kind: FuelAdjustmentKind) {
        if (state.value.isSaving) return
        mutableState.update {
            it.copy(
                editorDraft = FuelDraft(recordType = FuelRecordType.Adjustment, adjustmentKind = kind),
                editingRecordId = null,
                editingRecord = null,
                editorErrorMessage = null,
            )
        }
    }
    fun edit(record: FuelRecord) {
        if (state.value.isSaving) return
        mutableState.update { it.copy(editorDraft = FuelDraft(record), editingRecordId = record.id, editingRecord = record, editorErrorMessage = null) }
    }
    fun updateDraft(draft: FuelDraft) { if (state.value.isSaving) return; mutableState.update { it.copy(editorDraft = draft, editorErrorMessage = null) } }
    fun closeEditor() { if (state.value.isSaving) return; mutableState.update { it.copy(editorDraft = null, editingRecord = null, editingRecordId = null, editorErrorMessage = null) } }

    fun save() {
        val current = state.value
        val draft = current.editorDraft ?: return
        if (current.isSaving) return
        val error = when {
            startOffline -> "Для сохранения требуется подключение к интернету."
            current.editingRecord != null && current.editingRecordId.isNullOrBlank() -> "Нельзя обновить запись без id."
            draft.recordType == FuelRecordType.Fuel && current.editingRecord == null && draft.mileage.isBlank() -> "Укажите пробег."
            else -> validateFuelDraft(draft)
        }
        error?.let { message ->
            mutableState.update { it.copy(editorErrorMessage = message) }
            return
        }
        mutableState.update { it.copy(isSaving = true, editorErrorMessage = null, errorMessage = null) }
        viewModelScope.launch {
            try {
                val editingId = current.editingRecordId
                val snapshot = repository.save(session, editingId, draft)
                applySnapshot(snapshot)
                mutableState.update {
                    val year = (if (draft.recordType == FuelRecordType.Adjustment) draft.monthKey else draft.date).take(4)
                    it.copy(
                        selectedYear = year.takeIf { value -> value in it.years } ?: it.selectedYear,
                        editorDraft = null,
                        editingRecordId = null,
                        editingRecord = null,
                        isSaving = false,
                        notice = if (draft.recordType == FuelRecordType.Adjustment) "Корректировка сохранена." else "Запись топлива сохранена.",
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableState.update { it.copy(editorErrorMessage = error.userMessage()) }
            } finally { mutableState.update { it.copy(isSaving = false) } }
        }
    }

    fun requestDelete(record: FuelRecord) { if (state.value.isSaving) return; mutableState.update { it.copy(pendingDelete = record) } }
    fun cancelDelete() { if (state.value.isSaving) return; mutableState.update { it.copy(pendingDelete = null) } }
    fun confirmDelete() {
        val record = mutableState.value.pendingDelete ?: return
        if (state.value.isSaving) return
        if (startOffline || record.id.isNullOrBlank()) {
            mutableState.update { it.copy(pendingDelete = null, errorMessage = if (startOffline) "Для удаления требуется подключение к интернету." else "Нельзя удалить запись без id.") }
            return
        }
        mutableState.update { it.copy(isSaving = true, errorMessage = null) }
        viewModelScope.launch {
            try {
                val snapshot = repository.delete(session, record)
                applySnapshot(snapshot)
                mutableState.update { it.copy(pendingDelete = null, isSaving = false, notice = "Запись удалена.") }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableState.update { it.copy(isSaving = false, pendingDelete = null, errorMessage = error.userMessage()) }
            } finally { mutableState.update { it.copy(isSaving = false) } }
        }
    }

    fun importsCommitted(message: String) { mutableState.update { it.copy(notice = message) }; refresh() }

    fun dismissMessages() { mutableState.update { it.copy(notice = null, errorMessage = null) } }

    private fun applySnapshot(snapshot: FuelSnapshot) {
        mutableState.update { current ->
            val summary = FuelSummaryCalculator.build(snapshot.records.filter(FuelArchivePolicy::isCurrent))
            val years = FuelSummaryCalculator.years(summary)
            current.copy(snapshot = snapshot, selectedYear = current.selectedYear?.takeIf { it in years } ?: years.firstOrNull(),
                expandedMonthKey = current.expandedMonthKey?.takeIf { key -> summary.monthly.any { it.key == key } })
        }
    }

    private suspend fun refreshCurrent(showError: Boolean) {
        mutableState.update { it.copy(isLoading = true, errorMessage = null) }
        try {
            val snapshot = repository.refresh(session)
            hasLoaded = true
            applySnapshot(snapshot)
            mutableState.update { it.copy(isLoading = false) }
            try { repository.fuelTypes(session) } catch (error: CancellationException) { throw error } catch (_: Exception) { }
            applySnapshot(repository.retryPendingMileage(session))
            syncRouteMileage(allowRemote = true)
        } catch (error: CancellationException) {
            mutableState.update { it.copy(isLoading = false) }; throw error
        } catch (error: Exception) {
            if (mutableState.value.snapshot.records.isNotEmpty()) hasLoaded = true
            mutableState.update { it.copy(isLoading = false, errorMessage = if (showError) error.userMessage() else null) }
        }
    }

    private suspend fun syncRouteMileage(allowRemote: Boolean) {
        val monthKey = YearMonth.now().toString()
        val mileage = try { routeRepository.monthlyMileage(session, monthKey) } catch (error: CancellationException) { throw error } catch (_: Exception) { null } ?: return
        val snapshot = try {
            repository.syncMonthlyMileage(
                session,
                mileage.monthKey,
                mileage.latestDate,
                mileage.totalKm,
                allowRemotePersistence = allowRemote && hasLoaded,
            )
        } catch (error: CancellationException) { throw error } catch (_: Exception) { null } ?: return
        applySnapshot(snapshot)
    }

    private fun Throwable.userMessage(): String = (this as? FuelFailure)?.message ?: "Не удалось сохранить данные топлива."

    companion object {
        fun factory(
            repository: FuelRepository,
            routeRepository: RouteRepository,
            session: AppSession,
            startOffline: Boolean,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                FuelViewModel(repository, routeRepository, session, startOffline) as T
        }
    }
}
