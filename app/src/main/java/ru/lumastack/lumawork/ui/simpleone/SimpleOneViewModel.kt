package ru.lumastack.lumawork.ui.simpleone

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
import ru.lumastack.lumawork.data.simpleone.SimpleOneFailure
import ru.lumastack.lumawork.data.simpleone.SimpleOneRepository
import ru.lumastack.lumawork.domain.simpleone.ActiveRequestsSortOrder
import ru.lumastack.lumawork.domain.simpleone.ActiveRequestsStatusFilter
import ru.lumastack.lumawork.domain.simpleone.ClosedSimpleOneArchive
import ru.lumastack.lumawork.domain.simpleone.RequestsMode
import ru.lumastack.lumawork.domain.simpleone.SimpleOneRequest
import ru.lumastack.lumawork.domain.simpleone.SimpleOneSnapshot

data class SimpleOneUiState(
    val username: String = "",
    val password: String = "",
    val snapshot: SimpleOneSnapshot = SimpleOneSnapshot(),
    val closedArchive: ClosedSimpleOneArchive = ClosedSimpleOneArchive(),
    val mode: RequestsMode = RequestsMode.Active,
    val searchQuery: String = "",
    val statusFilter: ActiveRequestsStatusFilter = ActiveRequestsStatusFilter.Available,
    val sortOrder: ActiveRequestsSortOrder = ActiveRequestsSortOrder.RegisteredAt,
    val selectedRequest: SimpleOneRequest? = null,
    val isInitializing: Boolean = true,
    val isAuthorized: Boolean = false,
    val isLoading: Boolean = false,
    val isClosedLoading: Boolean = false,
    val hasLoadedClosedArchive: Boolean = false,
    val isDetailLoading: Boolean = false,
    val notice: String? = null,
    val errorMessage: String? = null,
    val detailErrorMessage: String? = null,
) {
    val activeRequests: List<SimpleOneRequest>
        get() = snapshot.activeRequests
}

class SimpleOneViewModel(
    private val repository: SimpleOneRepository,
    private val ownerUserId: String,
    private val startOffline: Boolean,
) : ViewModel() {
    private val mutableState = MutableStateFlow(SimpleOneUiState())
    val state: StateFlow<SimpleOneUiState> = mutableState.asStateFlow()
    private var detailJob: Job? = null

    init {
        restore()
    }

    fun updateUsername(value: String) {
        mutableState.update { it.copy(username = value, errorMessage = null, notice = null) }
    }

    fun updatePassword(value: String) {
        mutableState.update { it.copy(password = value, errorMessage = null, notice = null) }
    }

    fun updateSearchQuery(value: String) {
        mutableState.update { it.copy(searchQuery = value) }
    }

    fun updateStatusFilter(value: ActiveRequestsStatusFilter) {
        mutableState.update { it.copy(statusFilter = value) }
    }

    fun updateSortOrder(value: ActiveRequestsSortOrder) {
        mutableState.update { it.copy(sortOrder = value) }
    }

    fun updateMode(value: RequestsMode) {
        if (mutableState.value.mode == value) return
        mutableState.update { it.copy(mode = value, errorMessage = null) }
        if (value == RequestsMode.Closed) loadClosedArchive(force = false)
    }

    fun signIn() {
        val username = mutableState.value.username.trim()
        val password = mutableState.value.password.trim()
        if (username.isEmpty() || password.isEmpty()) {
            mutableState.update { it.copy(errorMessage = "Укажите логин и пароль SimpleOne.") }
            return
        }

        viewModelScope.launch {
            mutableState.update { it.copy(isLoading = true, errorMessage = null, notice = null) }
            try {
                val snapshot = repository.signIn(
                    ownerUserId = ownerUserId,
                    username = username,
                    password = password,
                    cached = mutableState.value.snapshot.takeIf { it.currentUser != null },
                )
                mutableState.update {
                    it.copy(
                        snapshot = snapshot,
                        password = "",
                        isAuthorized = true,
                        isLoading = false,
                        notice = "SimpleOne подключен.",
                    )
                }
                if (mutableState.value.mode == RequestsMode.Closed) loadClosedArchive(force = true)
            } catch (error: CancellationException) {
                throw error
            } catch (error: SimpleOneFailure.Unauthorized) {
                mutableState.update {
                    it.copy(
                        isAuthorized = false,
                        isLoading = false,
                        errorMessage = "Проверьте логин и пароль SimpleOne.",
                    )
                }
            } catch (error: Exception) {
                val authorized = repository.storedSession(ownerUserId) != null
                mutableState.update {
                    it.copy(
                        isAuthorized = authorized,
                        isLoading = false,
                        errorMessage = error.userMessage(),
                    )
                }
            }
        }
    }

    fun refresh() {
        if (mutableState.value.mode == RequestsMode.Closed) {
            loadClosedArchive(force = true)
        } else {
            viewModelScope.launch { refreshActive(showError = true) }
        }
    }

    fun signOut() {
        viewModelScope.launch {
            repository.signOut(ownerUserId)
            mutableState.update {
                it.copy(
                    password = "",
                    isAuthorized = false,
                    isLoading = false,
                    isClosedLoading = false,
                    notice = null,
                    errorMessage = null,
                )
            }
        }
    }

    fun openRequest(request: SimpleOneRequest) {
        detailJob?.cancel()
        mutableState.update {
            it.copy(
                selectedRequest = request,
                isDetailLoading = true,
                detailErrorMessage = null,
            )
        }
        detailJob = viewModelScope.launch {
            try {
                val detailed = repository.requestDetails(ownerUserId, request)
                mutableState.update { state ->
                    val active = state.snapshot.activeRequests.map { existing ->
                        if (existing.id == detailed.id) detailed else existing
                    }
                    val closed = state.closedArchive.requests.map { existing ->
                        if (existing.id == detailed.id) detailed else existing
                    }
                    state.copy(
                        snapshot = state.snapshot.copy(activeRequests = active),
                        closedArchive = state.closedArchive.copy(requests = closed),
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

    fun closeRequest() {
        detailJob?.cancel()
        mutableState.update {
            it.copy(selectedRequest = null, isDetailLoading = false, detailErrorMessage = null)
        }
    }

    private fun restore() {
        viewModelScope.launch {
            val cached = runCatching { repository.readSnapshot(ownerUserId) }.getOrNull()
            val storedSession = runCatching { repository.storedSession(ownerUserId) }.getOrNull()
            val username = storedSession?.username
                ?: runCatching { repository.rememberedUsername(ownerUserId) }.getOrDefault("")
            mutableState.update {
                it.copy(
                    username = username,
                    snapshot = cached ?: it.snapshot,
                    isInitializing = false,
                    isAuthorized = storedSession != null,
                )
            }

            if (storedSession != null && !startOffline) refreshActive(showError = false)
        }
    }

    private suspend fun refreshActive(showError: Boolean) {
        mutableState.update { it.copy(isLoading = true, errorMessage = null, notice = null) }
        try {
            val snapshot = repository.refresh(
                ownerUserId,
                mutableState.value.snapshot.takeIf { it.currentUser != null },
            )
            mutableState.update {
                it.copy(snapshot = snapshot, isAuthorized = true, isLoading = false)
            }
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
                it.copy(
                    isLoading = false,
                    errorMessage = if (showError) error.userMessage() else null,
                )
            }
        }
    }

    private fun loadClosedArchive(force: Boolean) {
        viewModelScope.launch {
            var state = mutableState.value
            if (!state.hasLoadedClosedArchive) {
                val cached = runCatching { repository.readClosedArchive(ownerUserId) }.getOrNull()
                mutableState.update {
                    it.copy(
                        closedArchive = cached ?: it.closedArchive,
                        hasLoadedClosedArchive = true,
                    )
                }
                state = mutableState.value
            }

            val cacheIsFresh = state.closedArchive.updatedAtEpochMillis?.let {
                System.currentTimeMillis() - it < CLOSED_REFRESH_INTERVAL_MILLIS
            } == true
            if (startOffline || !state.isAuthorized || (!force && state.closedArchive.isComplete && cacheIsFresh)) {
                return@launch
            }

            mutableState.update { it.copy(isClosedLoading = true, errorMessage = null, notice = null) }
            try {
                val archive = repository.refreshClosedArchive(ownerUserId, mutableState.value.snapshot)
                mutableState.update {
                    it.copy(
                        closedArchive = archive,
                        hasLoadedClosedArchive = true,
                        isClosedLoading = false,
                        isAuthorized = true,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: SimpleOneFailure.Unauthorized) {
                mutableState.update {
                    it.copy(
                        isAuthorized = false,
                        isClosedLoading = false,
                        errorMessage = "Сессия SimpleOne истекла. Показан локальный архив.",
                    )
                }
            } catch (error: Exception) {
                mutableState.update {
                    it.copy(isClosedLoading = false, errorMessage = error.userMessage())
                }
            }
        }
    }

    private fun Throwable.userMessage(): String = when (this) {
        is SimpleOneFailure -> message ?: "Не удалось загрузить данные SimpleOne."
        else -> "Не удалось загрузить данные SimpleOne."
    }

    companion object {
        private const val CLOSED_REFRESH_INTERVAL_MILLIS = 60_000L

        fun factory(
            repository: SimpleOneRepository,
            ownerUserId: String,
            startOffline: Boolean,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                SimpleOneViewModel(repository, ownerUserId, startOffline) as T
        }
    }
}
