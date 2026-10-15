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
    val pendingDelete: FuelRecord? = null,
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val errorMessage: String? = null,
    val editorErrorMessage: String? = null,
    val notice: String? = null,
) {
    val summary: FuelSummary get() = FuelSummaryCalculator.build(snapshot.records)
    val years: List<String> get() = FuelSummaryCalculator.years(summary)
    val visibleMonths get() = FuelSummaryCalculator.months(summary, selectedYear)
    val currentRecords: List<FuelRecord> get() = snapshot.records.filter { it.date.trim() >= CURRENT_RECORDS_START_DATE }
    val archivedCount: Int get() = snapshot.records.count { it.date.trim() < CURRENT_RECORDS_START_DATE }

    companion object { const val CURRENT_RECORDS_START_DATE = "2026-05-13" }
}

class FuelViewModel(
    private val repository: FuelRepository,
    private val routeRepository: RouteRepository,
    private val session: AppSession,
    private val startOffline: Boolean,
) : ViewModel() {
    private val mutableState = MutableStateFlow(FuelUiState())
    val state: StateFlow<FuelUiState> = mutableState.asStateFlow()
    private var loadJob: Job? = null
    private var hasLoaded = false

    init {
        loadJob = viewModelScope.launch {
            val cached = runCatching { repository.read(session.user.id) }.getOrNull()
            mutableState.update { it.copy(snapshot = cached ?: FuelSnapshot()) }
            syncRouteMileage(allowRemote = false)
            if (!startOffline) refreshCurrent(showError = cached == null)
        }
    }

    fun refresh() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch { refreshCurrent(showError = true) }
    }

    fun selectYear(year: String) { mutableState.update { it.copy(selectedYear = year) } }
    fun toggleMonth(monthKey: String) {
        mutableState.update { it.copy(expandedMonthKey = if (it.expandedMonthKey == monthKey) null else monthKey) }
    }

    fun addFuel() { mutableState.update { it.copy(editorDraft = FuelDraft(), editingRecordId = null, editorErrorMessage = null) } }
    fun addAdjustment(kind: FuelAdjustmentKind) {
        mutableState.update {
            it.copy(
                editorDraft = FuelDraft(recordType = FuelRecordType.Adjustment, adjustmentKind = kind),
                editingRecordId = null,
                editorErrorMessage = null,
            )
        }
    }
    fun edit(record: FuelRecord) {
        mutableState.update { it.copy(editorDraft = FuelDraft(record), editingRecordId = record.id, editorErrorMessage = null) }
    }
    fun updateDraft(draft: FuelDraft) { mutableState.update { it.copy(editorDraft = draft, editorErrorMessage = null) } }
    fun closeEditor() { mutableState.update { it.copy(editorDraft = null, editingRecordId = null, editorErrorMessage = null) } }

    fun save() {
        val draft = mutableState.value.editorDraft ?: return
        validateFuelDraft(draft)?.let { message ->
            mutableState.update { it.copy(editorErrorMessage = message) }
            return
        }
        viewModelScope.launch {
            mutableState.update { it.copy(isSaving = true, editorErrorMessage = null, errorMessage = null) }
            try {
                val editingId = mutableState.value.editingRecordId
                val snapshot = repository.save(session, editingId, draft)
                mutableState.update {
                    it.copy(
                        snapshot = snapshot,
                        editorDraft = null,
                        editingRecordId = null,
                        isSaving = false,
                        notice = if (draft.recordType == FuelRecordType.Adjustment) "Корректировка сохранена." else "Запись топлива сохранена.",
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableState.update { it.copy(isSaving = false, editorErrorMessage = error.userMessage()) }
            }
        }
    }

    fun requestDelete(record: FuelRecord) { mutableState.update { it.copy(pendingDelete = record) } }
    fun cancelDelete() { mutableState.update { it.copy(pendingDelete = null) } }
    fun confirmDelete() {
        val record = mutableState.value.pendingDelete ?: return
        viewModelScope.launch {
            mutableState.update { it.copy(isSaving = true, errorMessage = null) }
            try {
                val snapshot = repository.delete(session, record)
                mutableState.update { it.copy(snapshot = snapshot, pendingDelete = null, isSaving = false, notice = "Запись удалена.") }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableState.update { it.copy(isSaving = false, pendingDelete = null, errorMessage = error.userMessage()) }
            }
        }
    }

    private suspend fun refreshCurrent(showError: Boolean) {
        mutableState.update { it.copy(isLoading = true, errorMessage = null) }
        try {
            val snapshot = repository.refresh(session)
            hasLoaded = true
            mutableState.update { state ->
                val years = FuelSummaryCalculator.years(FuelSummaryCalculator.build(snapshot.records))
                state.copy(snapshot = snapshot, selectedYear = state.selectedYear ?: years.firstOrNull(), isLoading = false)
            }
            syncRouteMileage(allowRemote = true)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (mutableState.value.snapshot.records.isNotEmpty()) hasLoaded = true
            mutableState.update { it.copy(isLoading = false, errorMessage = if (showError) error.userMessage() else null) }
        }
    }

    private suspend fun syncRouteMileage(allowRemote: Boolean) {
        val monthKey = YearMonth.now().toString()
        val mileage = runCatching { routeRepository.monthlyMileage(session, monthKey) }.getOrNull() ?: return
        val snapshot = runCatching {
            repository.syncMonthlyMileage(
                session,
                mileage.monthKey,
                mileage.latestDate,
                mileage.totalKm,
                allowRemotePersistence = allowRemote && hasLoaded,
            )
        }.getOrNull() ?: return
        mutableState.update { it.copy(snapshot = snapshot) }
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
