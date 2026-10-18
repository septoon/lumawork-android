package ru.lumastack.lumawork.ui.salary

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.lumastack.lumawork.data.salary.*
import ru.lumastack.lumawork.domain.model.AppSession

data class SalaryUiState(
    val snapshot: SalarySnapshot = SalarySnapshot(),
    val projection: SalaryProjection = SalaryProjection(),
    val selectedYear: String? = null,
    val selectedMonth: String? = null,
    val showAllTime: Boolean = false,
    val options: SalaryDisplayOptions = SalaryDisplayOptions(),
    val draft: SalaryDraft? = null,
    val editingId: String? = null,
    val pendingDelete: SalaryEntry? = null,
    val initializing: Boolean = true,
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val saving: Boolean = false,
    val error: String? = null,
    val editorError: String? = null,
    val notice: String? = null,
) {
    val hidden get() = options.hidden
    val months get() = projection.months
    val years get() = projection.years
    val visibleMonths get() = months.filter { selectedYear == null || it.key.startsWith(selectedYear) }
}

class SalaryViewModel(
    private val repository: SalaryRepository,
    private val session: AppSession,
    private val offline: Boolean,
    options: SalaryDisplayOptions = SalaryDisplayOptions(),
    private val persistOptions: (SalaryDisplayOptions) -> Unit = {},
) : ViewModel() {
    private val mutable = MutableStateFlow(SalaryUiState(options = options))
    val state = mutable.asStateFlow()
    private var refreshJob: Job? = null
    private var mutationRevision = 0L

    init {
        refreshJob = viewModelScope.launch {
            try {
                val revision = mutationRevision
                val cached = repository.read(session.user.id)
                if (cached != null) publish(cached, revision)
                if (!offline && mutationRevision == revision && !state.value.saving) refreshCurrent(show = cached?.records.isNullOrEmpty())
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { mutable.update { it.copy(loading = false, refreshing = false, error = error.userMessage()) } }
            finally { mutable.update { it.copy(initializing = false) } }
        }
    }

    fun refresh() {
        if (offline || state.value.saving || refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch { refreshCurrent(show = true) }
    }
    fun year(value: String?) = mutable.update { it.copy(selectedYear = value) }
    fun month(value: String?) = mutable.update { it.copy(selectedMonth = value) }
    fun summaryPeriod() = mutable.update { it.copy(showAllTime = !it.showAllTime) }
    fun hidden() = setOptions(state.value.options.copy(hidden = !state.value.hidden))
    fun toggleKind(kind: SalaryKind) {
        val old = state.value.options
        setOptions(old.copy(excludedKinds = if (kind in old.excludedKinds) old.excludedKinds - kind else old.excludedKinds + kind))
    }
    private fun setOptions(value: SalaryDisplayOptions) { persistOptions(value); mutable.update { it.copy(options = value) } }
    fun clearMessage() = mutable.update { it.copy(error = null, notice = null) }
    fun message(value: String) = mutable.update { it.copy(notice = value) }
    fun add() { if (!offline && !state.value.saving) mutable.update { it.copy(draft = SalaryDraft(), editingId = null, editorError = null) } }
    fun edit(entry: SalaryEntry) {
        if (offline || state.value.saving) return
        if (entry.id.isNullOrBlank()) { mutable.update { it.copy(error = "У записи нет ID. Обновите зарплату перед редактированием.") }; return }
        mutable.update { it.copy(draft = SalaryDraft(entry), editingId = entry.id, selectedMonth = null, editorError = null) }
    }
    fun draft(value: SalaryDraft) { if (!state.value.saving) mutable.update { it.copy(draft = value, editorError = null) } }
    fun close() { if (!state.value.saving) mutable.update { it.copy(draft = null, editingId = null, editorError = null) } }
    fun deleteAsk(entry: SalaryEntry) {
        if (!offline && !state.value.saving && !entry.id.isNullOrBlank()) mutable.update { it.copy(pendingDelete = entry, selectedMonth = null) }
    }
    fun deleteCancel() { if (!state.value.saving) mutable.update { it.copy(pendingDelete = null) } }
    fun save() {
        val current = state.value
        val draft = current.draft ?: return
        if (offline || current.saving) return
        validateSalaryDraft(draft)?.let { error -> mutable.update { it.copy(editorError = error) }; return }
        mutationRevision++
        mutable.update { it.copy(saving = true, editorError = null) }
        viewModelScope.launch {
            try {
                val saved = repository.save(session.user.id, session.token, current.editingId, draft)
                publish(saved)
                mutable.update { it.copy(saving = false, draft = null, editingId = null,
                    selectedYear = (if (draft.date >= SalaryEntry.NET_START) draft.periodMonth else draft.date).take(4),
                    notice = if (current.editingId == null) "Запись зарплаты добавлена." else "Запись зарплаты обновлена.") }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { mutable.update { it.copy(saving = false, editorError = error.userMessage()) } }
        }
    }
    fun deleteConfirm() {
        val current = state.value
        val entry = current.pendingDelete ?: return
        if (offline || current.saving) return
        mutationRevision++
        mutable.update { it.copy(saving = true) }
        viewModelScope.launch {
            try {
                publish(repository.delete(session.user.id, session.token, entry))
                mutable.update { it.copy(saving = false, pendingDelete = null, notice = "Запись зарплаты удалена.") }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { mutable.update { it.copy(saving = false, pendingDelete = null, error = error.userMessage()) } }
        }
    }
    private suspend fun refreshCurrent(show: Boolean) {
        val revision = mutationRevision
        mutable.update { it.copy(loading = true, refreshing = show, error = null) }
        try {
            val snapshot = repository.refresh(session.user.id, session.token)
            if (mutationRevision == revision) publish(snapshot, revision)
            mutable.update { it.copy(loading = false, refreshing = false) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { mutable.update { it.copy(loading = false, refreshing = false, error = if (show) error.userMessage() else null) } }
    }
    private suspend fun publish(snapshot: SalarySnapshot, expectedRevision: Long? = null) {
        val projection = withContext(Dispatchers.Default) { SalaryProjection(salaryMonths(snapshot.records)) }
        if (expectedRevision != null && expectedRevision != mutationRevision) return
        mutable.update { current -> current.copy(snapshot = snapshot, projection = projection,
            selectedYear = current.selectedYear?.takeIf { it in projection.years } ?: projection.years.firstOrNull(),
            selectedMonth = current.selectedMonth?.takeIf { selected -> projection.months.any { it.key == selected } }) }
    }
    private fun Throwable.userMessage(): String = (this as? SalaryFailure)?.message ?: "Не удалось загрузить зарплату. Попробуйте ещё раз."
    companion object {
        fun factory(repository: SalaryRepository, session: AppSession, offline: Boolean, options: SalaryDisplayOptions,
            persist: (SalaryDisplayOptions) -> Unit) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T =
                SalaryViewModel(repository, session, offline, options, persist) as T
        }
    }
}
