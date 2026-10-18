package ru.lumastack.lumawork.ui.timereport

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.lumastack.lumawork.domain.timereport.TimeReportMonthGroup
import ru.lumastack.lumawork.domain.timereport.timeReportMonthGroups
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.lumastack.lumawork.data.simpleone.SimpleOneFailure
import ru.lumastack.lumawork.data.timereport.TimeReportRepository
import ru.lumastack.lumawork.domain.timereport.TimeReportSnapshot

data class TimeReportUiState(
    val simpleOneUserId: String = "",
    val isAuthorized: Boolean = false,
    val snapshot: TimeReportSnapshot? = null,
    val monthGroups: List<TimeReportMonthGroup> = emptyList(),
    val selectedMonthId: String? = null,
    val isInitializing: Boolean = true,
    val initialLoadRequested: Boolean = false,
    val progress: String? = null,
    val filterMonthId: String? = null,
    val isLoading: Boolean = false,
    val isDeleting: Boolean = false,
    val errorMessage: String? = null,
    val notice: String? = null,
)

class TimeReportViewModel(
    private val repository: TimeReportRepository,
    private val ownerUserId: String,
    private val startOffline: Boolean,
) : ViewModel() {
    private val mutableState = MutableStateFlow(TimeReportUiState())
    val state: StateFlow<TimeReportUiState> = mutableState.asStateFlow()
    private var loadJob: Job? = null

    private var activated = false
    private var connectedUserId = ""
    private var sessionRevision = ""

    fun activate(simpleOneUserId: String, isAuthorized: Boolean, revision: String = "") {
        val changedSession = sessionRevision != revision
        sessionRevision = revision
        if ((!isAuthorized && mutableState.value.isAuthorized) || changedSession) {
            loadJob?.cancel()
            mutableState.update { it.copy(isLoading = false, isDeleting = false, progress = null) }
        }
        val changedUser = simpleOneUserId.isNotBlank() && simpleOneUserId != connectedUserId
        connectedUserId = simpleOneUserId
        mutableState.update { it.copy(isAuthorized = isAuthorized) }
        if (activated && !changedUser && !changedSession && !mutableState.value.isInitializing) return
        activated = true
        mutableState.update { it.copy(isInitializing = true,
            snapshot = it.snapshot?.takeIf { cached -> (!changedSession && simpleOneUserId.isBlank()) || cached.simpleOneUserId == simpleOneUserId },
            monthGroups = it.monthGroups.takeIf { _ -> (!changedSession && simpleOneUserId.isBlank()) || it.snapshot?.simpleOneUserId == simpleOneUserId }.orEmpty()) }
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val cached = repository.read(ownerUserId, simpleOneUserId, allowOfflineCache = startOffline)
            val groups = prepareGroups(cached)
            mutableState.update { it.copy(
                simpleOneUserId = simpleOneUserId.ifBlank { cached?.simpleOneUserId.orEmpty() },
                snapshot = cached, monthGroups = groups, selectedMonthId = null, filterMonthId = null,
                isInitializing = false, isLoading = false, progress = null, errorMessage = null, notice = null,
            ) }
        }
    }

    fun refresh(): Boolean {
        if (mutableState.value.isLoading || mutableState.value.isDeleting || mutableState.value.isInitializing || loadJob?.isActive == true) return false
        if (startOffline) {
            mutableState.update { it.copy(initialLoadRequested = true, errorMessage = "Для загрузки трудозатрат требуется подключение к сети.") }
            return true
        }
        if (!mutableState.value.isAuthorized) return false
        loadJob = viewModelScope.launch { refreshCurrent(showError = true) }
        return true
    }

    suspend fun loadAfterActivation(): Boolean {
        loadJob?.join()
        return refresh()
    }

    fun filterMonth(monthId: String?) { mutableState.update { it.copy(filterMonthId = monthId) } }
    fun clearMessage() { mutableState.update { it.copy(errorMessage = null, notice = null) } }

    fun importXlsx(bytes: ByteArray) {
        val current = mutableState.value
        if (current.isLoading || current.isDeleting || loadJob?.isActive == true || current.simpleOneUserId.isBlank()) return
        loadJob = viewModelScope.launch {
            mutableState.update { it.copy(isLoading = true, errorMessage = null, notice = null, progress = "Разбираю XLSX.") }
            try {
                val snapshot = repository.importXlsx(ownerUserId, current.simpleOneUserId, bytes)
                val groups = prepareGroups(snapshot)
                mutableState.update { it.copy(snapshot = snapshot, monthGroups = groups, isLoading = false, progress = null,
                    notice = "Трудозатраты загружены: ${snapshot.entries.size} строк.") }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { mutableState.update { it.copy(isLoading = false, progress = null, errorMessage = error.userMessage()) } }
        }
    }

    suspend fun exportXlsx(): ByteArray = repository.exportXlsx(mutableState.value.snapshot?.entries.orEmpty())

    fun openMonth(monthId: String) {
        mutableState.update { it.copy(selectedMonthId = monthId) }
    }

    fun closeMonth() {
        mutableState.update { it.copy(selectedMonthId = null) }
    }

    fun deleteAll() {
        val current = mutableState.value
        if (current.isDeleting || current.isLoading || loadJob?.isActive == true || current.simpleOneUserId.isBlank()) return
        loadJob = viewModelScope.launch {
            mutableState.update { it.copy(isDeleting = true, errorMessage = null, notice = null) }
            try {
                val deleted = repository.deleteAll(ownerUserId, current.simpleOneUserId)
                mutableState.update {
                    it.copy(
                        snapshot = null,
                        monthGroups = emptyList(),
                        selectedMonthId = null,
                        filterMonthId = null,
                        isDeleting = false,
                        notice = "Удалено строк трудозатрат: $deleted.",
                    )
                }
            } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) {
                mutableState.update { it.copy(isDeleting = false, errorMessage = "Не удалось очистить архив.") }
            }
        }
    }

    fun deleteRange(startDate: LocalDate, endDate: LocalDate) {
        val current = mutableState.value
        if (current.isDeleting || current.isLoading || loadJob?.isActive == true || current.simpleOneUserId.isBlank()) return
        loadJob = viewModelScope.launch {
            mutableState.update { it.copy(isDeleting = true, errorMessage = null, notice = null) }
            try {
                val deleted = repository.deleteRange(
                    ownerUserId = ownerUserId,
                    simpleOneUserId = current.simpleOneUserId,
                    startDate = startDate,
                    endDate = endDate,
                )
                val updated = repository.read(ownerUserId, current.simpleOneUserId)
                val groups = prepareGroups(updated)
                mutableState.update {
                    it.copy(
                        snapshot = updated,
                        monthGroups = groups,
                        selectedMonthId = it.selectedMonthId?.takeIf { monthId ->
                            groups.any { group -> group.monthId == monthId }
                        },
                        filterMonthId = it.filterMonthId?.takeIf { monthId -> groups.any { group -> group.monthId == monthId } },
                        isDeleting = false,
                        notice = if (deleted > 0) {
                            "Удалено строк трудозатрат: $deleted."
                        } else {
                            "За выбранный период трудозатраты не найдены."
                        },
                    )
                }
            } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) {
                mutableState.update { it.copy(isDeleting = false, errorMessage = "Не удалось очистить выбранный период.") }
            }
        }
    }

    fun reportInputError(message: String) {
        mutableState.update { it.copy(errorMessage = message) }
    }

    private suspend fun refreshCurrent(showError: Boolean) {
        val current = mutableState.value
        if (!current.isAuthorized) return
        mutableState.update { it.copy(isLoading = true, initialLoadRequested = true, errorMessage = null, notice = null) }
        try {
            val snapshot = repository.refresh(ownerUserId, connectedUserId) { text -> mutableState.update { it.copy(progress = text) } }
            val groups = prepareGroups(snapshot)
            mutableState.update {
                it.copy(
                    snapshot = snapshot,
                    monthGroups = groups,
                    simpleOneUserId = snapshot.simpleOneUserId,
                    progress = null,
                    isAuthorized = true,
                    isLoading = false,
                    notice = if (showError) "Трудозатраты обновлены: ${snapshot.entries.size} строк." else null,
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: SimpleOneFailure.Unauthorized) {
            mutableState.update {
                it.copy(
                    isAuthorized = false,
                    isLoading = false,
                    progress = null,
                    errorMessage = if (showError) "Войдите в SimpleOne, чтобы обновить трудозатраты." else null,
                )
            }
        } catch (error: Exception) {
            mutableState.update {
                it.copy(
                    isLoading = false,
                    progress = null,
                    errorMessage = if (showError) error.userMessage() else null,
                )
            }
        }
    }

    private suspend fun prepareGroups(snapshot: TimeReportSnapshot?): List<TimeReportMonthGroup> =
        withContext(Dispatchers.Default) { timeReportMonthGroups(snapshot?.entries.orEmpty()) }

    private fun Throwable.userMessage(): String = when (this) {
        is SimpleOneFailure -> message ?: "Не удалось загрузить трудозатраты."
        else -> "Не удалось загрузить трудозатраты."
    }

    companion object {
        fun factory(
            repository: TimeReportRepository,
            ownerUserId: String,
            startOffline: Boolean,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = TimeReportViewModel(
                repository,
                ownerUserId,
                startOffline,
            ) as T
        }
    }
}
