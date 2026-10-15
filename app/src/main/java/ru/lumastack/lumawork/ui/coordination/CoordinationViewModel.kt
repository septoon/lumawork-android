package ru.lumastack.lumawork.ui.coordination

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.lumastack.lumawork.data.coordination.CoordinationRepository
import ru.lumastack.lumawork.data.simpleone.SimpleOneFailure
import ru.lumastack.lumawork.data.simpleone.SimpleOneRepository
import ru.lumastack.lumawork.domain.coordination.CoordinationRegion
import ru.lumastack.lumawork.domain.coordination.CoordinationSection
import ru.lumastack.lumawork.domain.coordination.CoordinationSnapshot
import ru.lumastack.lumawork.domain.simpleone.SimpleOneRequest

data class CoordinationUiState(
    val simpleOneUserId: String = "",
    val isAuthorized: Boolean = false,
    val section: CoordinationSection = CoordinationSection.Distribution,
    val region: CoordinationRegion = CoordinationRegion.Alushta,
    val snapshot: CoordinationSnapshot = CoordinationSnapshot(),
    val selectedEngineerId: String? = null,
    val selectedRequest: SimpleOneRequest? = null,
    val isLoading: Boolean = false,
    val isDetailLoading: Boolean = false,
    val errorMessage: String? = null,
    val detailErrorMessage: String? = null,
)

class CoordinationViewModel(
    private val coordinationRepository: CoordinationRepository,
    private val simpleOneRepository: SimpleOneRepository,
    private val ownerUserId: String,
    private val startOffline: Boolean,
) : ViewModel() {
    private val mutableState = MutableStateFlow(CoordinationUiState())
    val state: StateFlow<CoordinationUiState> = mutableState.asStateFlow()
    private var loadJob: Job? = null
    private var detailJob: Job? = null

    fun activate(simpleOneUserId: String, isAuthorized: Boolean) {
        if (simpleOneUserId.isBlank()) {
            mutableState.update { it.copy(isAuthorized = false) }
            return
        }
        val current = mutableState.value
        if (current.simpleOneUserId == simpleOneUserId) {
            mutableState.update { it.copy(isAuthorized = isAuthorized) }
            if (isAuthorized && !startOffline) refresh(showError = false)
            return
        }

        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val cached = runCatching { coordinationRepository.read(ownerUserId, simpleOneUserId) }.getOrNull()
            mutableState.update {
                it.copy(
                    simpleOneUserId = simpleOneUserId,
                    isAuthorized = isAuthorized,
                    snapshot = cached ?: CoordinationSnapshot(simpleOneUserId),
                    selectedEngineerId = null,
                    selectedRequest = null,
                    errorMessage = null,
                )
            }
            if (isAuthorized && !startOffline) refreshCurrent(showError = false)
        }
    }

    fun selectSection(section: CoordinationSection) {
        if (mutableState.value.section == section) return
        mutableState.update {
            it.copy(
                section = section,
                selectedEngineerId = null,
                selectedRequest = null,
                errorMessage = null,
            )
        }
        if (mutableState.value.isAuthorized && !startOffline) refresh(showError = false)
    }

    fun selectRegion(region: CoordinationRegion) {
        if (mutableState.value.region == region) return
        mutableState.update {
            it.copy(
                region = region,
                selectedEngineerId = null,
                selectedRequest = null,
                errorMessage = null,
            )
        }
        if (mutableState.value.isAuthorized && !startOffline) refresh(showError = false)
    }

    fun refresh(showError: Boolean = true) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch { refreshCurrent(showError) }
    }

    fun openEngineer(engineerId: String) {
        mutableState.update { it.copy(selectedEngineerId = engineerId) }
    }

    fun openRequest(request: SimpleOneRequest) {
        detailJob?.cancel()
        mutableState.update {
            it.copy(selectedRequest = request, isDetailLoading = true, detailErrorMessage = null)
        }
        detailJob = viewModelScope.launch {
            try {
                val detailed = simpleOneRepository.requestDetails(ownerUserId, request)
                val updatedSnapshot = coordinationRepository.replaceRequest(
                    ownerUserId,
                    mutableState.value.simpleOneUserId,
                    detailed,
                )
                mutableState.update {
                    it.copy(
                        snapshot = updatedSnapshot ?: it.snapshot,
                        selectedRequest = detailed,
                        isDetailLoading = false,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: SimpleOneFailure.Unauthorized) {
                mutableState.update {
                    it.copy(
                        isAuthorized = false,
                        isDetailLoading = false,
                        detailErrorMessage = "Сессия SimpleOne истекла. Показаны сохранённые данные.",
                    )
                }
            } catch (error: Exception) {
                mutableState.update {
                    it.copy(isDetailLoading = false, detailErrorMessage = error.userMessage())
                }
            }
        }
    }

    fun back() {
        detailJob?.cancel()
        mutableState.update {
            when {
                it.selectedRequest != null -> it.copy(
                    selectedRequest = null,
                    isDetailLoading = false,
                    detailErrorMessage = null,
                )
                it.selectedEngineerId != null -> it.copy(selectedEngineerId = null)
                else -> it
            }
        }
    }

    private suspend fun refreshCurrent(showError: Boolean) {
        val state = mutableState.value
        if (!state.isAuthorized || state.simpleOneUserId.isBlank()) return
        mutableState.update { it.copy(isLoading = true, errorMessage = null) }
        try {
            val snapshot = when (state.section) {
                CoordinationSection.Distribution -> coordinationRepository.refreshRegion(
                    ownerUserId,
                    state.simpleOneUserId,
                    state.region,
                )
                CoordinationSection.ReturnEquipment -> coordinationRepository.refreshReturnEquipment(
                    ownerUserId,
                    state.simpleOneUserId,
                )
            }
            mutableState.update { it.copy(snapshot = snapshot, isLoading = false) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: SimpleOneFailure.Unauthorized) {
            mutableState.update {
                it.copy(
                    isAuthorized = false,
                    isLoading = false,
                    errorMessage = if (showError) "Сессия SimpleOne истекла. Войдите снова." else null,
                )
            }
        } catch (error: Exception) {
            mutableState.update {
                it.copy(isLoading = false, errorMessage = if (showError) error.userMessage() else null)
            }
        }
    }

    private fun Throwable.userMessage(): String = when (this) {
        is SimpleOneFailure -> message ?: "Не удалось загрузить координацию."
        else -> "Не удалось загрузить координацию."
    }

    companion object {
        fun factory(
            coordinationRepository: CoordinationRepository,
            simpleOneRepository: SimpleOneRepository,
            ownerUserId: String,
            startOffline: Boolean,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = CoordinationViewModel(
                coordinationRepository,
                simpleOneRepository,
                ownerUserId,
                startOffline,
            ) as T
        }
    }
}
