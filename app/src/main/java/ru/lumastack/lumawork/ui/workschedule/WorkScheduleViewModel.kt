package ru.lumastack.lumawork.ui.workschedule

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.lumastack.lumawork.data.simpleone.SimpleOneFailure
import ru.lumastack.lumawork.data.workschedule.WorkScheduleRepository
import ru.lumastack.lumawork.domain.workschedule.*

data class WorkScheduleUiState(
    val snapshot: WorkScheduleSnapshot = WorkScheduleSnapshot(),
    val selection: WorkScheduleSelectionState = WorkScheduleSelectionState(),
    val isAuthorized: Boolean = false,
    val isLoading: Boolean = false,
    val isOffline: Boolean = false,
    val errorMessage: String? = null,
) {
    val journal: WorkScheduleJournal? get() = WorkScheduleSelection.journal(snapshot.journals, selection)
    val schedule: WorkSchedule? get() = journal?.sysId?.let(snapshot.schedulesByJournalId::get)
}

class WorkScheduleViewModel(
    private val repository: WorkScheduleRepository,
    private val ownerUserId: String,
    private val simpleOneUserId: String,
    private val startOffline: Boolean,
    private val profileCity: String?,
    private val now: () -> LocalDate = LocalDate::now,
) : ViewModel() {
    private val mutableState = MutableStateFlow(WorkScheduleUiState(isOffline = startOffline))
    val state = mutableState.asStateFlow()
    private var loadJob: Job? = null
    private var initialized = false
    private var generation = 0

    fun activate(isAuthorized: Boolean) {
        generation += 1
        loadJob?.cancel()
        mutableState.update { it.copy(isAuthorized = isAuthorized, isLoading = false) }
        val requestGeneration = generation
        loadJob = viewModelScope.launch {
            if (!initialized) {
                val cached = repository.read(ownerUserId, simpleOneUserId) ?: WorkScheduleSnapshot(simpleOneUserId)
                if (requestGeneration != generation) return@launch
                mutableState.update { it.copy(snapshot = cached, selection = WorkScheduleSelection.initial(cached.journals, profileCity, now())) }
                initialized = true
            }
            if (isAuthorized && !startOffline) refreshJournals(requestGeneration, resetSelection = state.value.selection.cityName.isBlank())
        }
    }

    fun selectCity(city: String) = select { WorkScheduleSelection.selectCity(it.snapshot.journals, it.selection, city) }
    fun selectPeriod(year: Int, month: Int) = select { it.selection.copy(year = year, month = month) }
    private fun select(selection: (WorkScheduleUiState) -> WorkScheduleSelectionState) {
        generation += 1
        loadJob?.cancel()
        mutableState.update { it.copy(selection = selection(it), errorMessage = null, isLoading = false) }
        if (!mutableState.value.isAuthorized || startOffline) return
        val requestGeneration = generation
        loadJob = viewModelScope.launch { loadSchedule(requestGeneration, false) }
    }

    fun refresh() {
        if (!mutableState.value.isAuthorized || startOffline) return
        generation += 1
        loadJob?.cancel()
        val requestGeneration = generation
        loadJob = viewModelScope.launch {
            if (mutableState.value.journal == null) refreshJournals(requestGeneration, resetSelection = true)
            else loadSchedule(requestGeneration, true)
        }
    }

    private suspend fun refreshJournals(requestGeneration: Int, resetSelection: Boolean) {
        var journalsFailed = false
        mutableState.update { it.copy(isLoading = true, errorMessage = null) }
        try {
            val snapshot = repository.refreshJournals(ownerUserId, simpleOneUserId)
            if (requestGeneration != generation) return
            mutableState.update {
                it.copy(snapshot = snapshot, selection = if (resetSelection || WorkScheduleSelection.cities(snapshot.journals).none { city -> city.equals(it.selection.cityName, ignoreCase = true) }) WorkScheduleSelection.initial(snapshot.journals, profileCity, now()) else it.selection)
            }
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            journalsFailed = true
            failure(error, "Не удалось загрузить список графиков.", requestGeneration)
            if (error is SimpleOneFailure.Unauthorized || error is SimpleOneFailure.MissingCredentials) return
        }
        if (requestGeneration == generation) loadSchedule(requestGeneration, false, preserveError = journalsFailed)
    }

    private suspend fun loadSchedule(requestGeneration: Int, force: Boolean, preserveError: Boolean = false) {
        val journal = mutableState.value.journal
        if (journal == null) {
            mutableState.update { it.copy(isLoading = false, errorMessage = it.errorMessage ?: "График за выбранный месяц не опубликован.") }
            return
        }
        mutableState.update { it.copy(isLoading = true, errorMessage = if (preserveError) it.errorMessage else null) }
        try {
            val snapshot = repository.loadSchedule(ownerUserId, simpleOneUserId, journal, force)
            if (requestGeneration != generation) return
            mutableState.update { it.copy(snapshot = snapshot, isLoading = false, errorMessage = if (preserveError) it.errorMessage else null) }
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) { failure(error, "Не удалось загрузить график работы.", requestGeneration) }
    }

    private fun failure(error: Exception, fallback: String, requestGeneration: Int) {
        if (requestGeneration != generation) return
        val needsAuth = error is SimpleOneFailure.Unauthorized || error is SimpleOneFailure.MissingCredentials
        mutableState.update { it.copy(isLoading = false, isAuthorized = it.isAuthorized && !needsAuth,
            errorMessage = if (needsAuth) "Войдите в SimpleOne в разделе «Заявки», чтобы открыть график работы." else (error as? SimpleOneFailure)?.message ?: fallback) }
    }

    companion object {
        fun factory(repository: WorkScheduleRepository, owner: String, user: String, offline: Boolean, city: String?) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = WorkScheduleViewModel(repository, owner, user, offline, city) as T
        }
    }
}
