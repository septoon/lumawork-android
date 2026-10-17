package ru.lumastack.lumawork.ui.timereport

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
import ru.lumastack.lumawork.data.simpleone.SimpleOneFailure
import ru.lumastack.lumawork.data.timereport.TimeReportRepository
import ru.lumastack.lumawork.domain.timereport.TimeReportSnapshot

data class TimeReportUiState(
    val simpleOneUserId: String = "",
    val isAuthorized: Boolean = false,
    val snapshot: TimeReportSnapshot? = null,
    val selectedMonthId: String? = null,
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

    fun activate(simpleOneUserId: String, isAuthorized: Boolean) {
        if (simpleOneUserId.isBlank()) {
            mutableState.update { it.copy(isAuthorized = false) }
            return
        }
        if (mutableState.value.simpleOneUserId == simpleOneUserId) {
            mutableState.update { it.copy(isAuthorized = isAuthorized) }
            if (isAuthorized && !startOffline && mutableState.value.snapshot == null) refresh()
            return
        }
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val cached = runCatching { repository.read(ownerUserId, simpleOneUserId) }.getOrNull()
            mutableState.update {
                it.copy(
                    simpleOneUserId = simpleOneUserId,
                    isAuthorized = isAuthorized,
                    snapshot = cached,
                    selectedMonthId = null,
                    errorMessage = null,
                    notice = null,
                )
            }
            if (isAuthorized && !startOffline) refreshCurrent(showError = cached == null)
        }
    }

    fun refresh() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch { refreshCurrent(showError = true) }
    }

    fun openMonth(monthId: String) {
        mutableState.update { it.copy(selectedMonthId = monthId) }
    }

    fun closeMonth() {
        mutableState.update { it.copy(selectedMonthId = null) }
    }

    fun deleteAll() {
        val current = mutableState.value
        if (current.isDeleting || current.simpleOneUserId.isBlank()) return
        viewModelScope.launch {
            mutableState.update { it.copy(isDeleting = true, errorMessage = null, notice = null) }
            try {
                val deleted = repository.deleteAll(ownerUserId, current.simpleOneUserId)
                mutableState.update {
                    it.copy(
                        snapshot = null,
                        selectedMonthId = null,
                        isDeleting = false,
                        notice = "Удалено строк трудозатрат: $deleted.",
                    )
                }
            } catch (_: Exception) {
                mutableState.update { it.copy(isDeleting = false, errorMessage = "Не удалось очистить архив.") }
            }
        }
    }

    fun deleteRange(startDate: LocalDate, endDate: LocalDate) {
        val current = mutableState.value
        if (current.isDeleting || current.simpleOneUserId.isBlank()) return
        viewModelScope.launch {
            mutableState.update { it.copy(isDeleting = true, errorMessage = null, notice = null) }
            try {
                val deleted = repository.deleteRange(
                    ownerUserId = ownerUserId,
                    simpleOneUserId = current.simpleOneUserId,
                    startDate = startDate,
                    endDate = endDate,
                )
                val updated = repository.read(ownerUserId, current.simpleOneUserId)
                mutableState.update {
                    it.copy(
                        snapshot = updated,
                        selectedMonthId = it.selectedMonthId?.takeIf { monthId ->
                            updated?.entries?.any { entry -> entry.createdAt.startsWith(monthId) } == true
                        },
                        isDeleting = false,
                        notice = if (deleted > 0) {
                            "Удалено строк трудозатрат: $deleted."
                        } else {
                            "За выбранный период трудозатраты не найдены."
                        },
                    )
                }
            } catch (_: Exception) {
                mutableState.update { it.copy(isDeleting = false, errorMessage = "Не удалось очистить выбранный период.") }
            }
        }
    }

    fun reportInputError(message: String) {
        mutableState.update { it.copy(errorMessage = message) }
    }

    private suspend fun refreshCurrent(showError: Boolean) {
        val current = mutableState.value
        if (!current.isAuthorized || current.simpleOneUserId.isBlank()) return
        mutableState.update { it.copy(isLoading = true, errorMessage = null, notice = null) }
        try {
            val snapshot = repository.refresh(ownerUserId, current.simpleOneUserId)
            mutableState.update {
                it.copy(
                    snapshot = snapshot,
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
                    errorMessage = if (showError) "Войдите в SimpleOne, чтобы обновить трудозатраты." else null,
                )
            }
        } catch (error: Exception) {
            mutableState.update {
                it.copy(
                    isLoading = false,
                    errorMessage = if (showError) error.userMessage() else null,
                )
            }
        }
    }

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
