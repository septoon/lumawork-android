package ru.lumastack.lumawork.ui.simpleone

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.lumastack.lumawork.domain.requests.ClosedRequestsAnalytics
import ru.lumastack.lumawork.domain.requests.buildClosedRequestsAnalytics
import ru.lumastack.lumawork.domain.requests.ClosedRequestsItem
import ru.lumastack.lumawork.domain.requests.prepareClosedRequests
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.lumastack.lumawork.domain.requests.closedEffectiveTime
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
    val closedArchiveItems: List<ClosedRequestsItem> = emptyList(),
    val closedAnalytics: ClosedRequestsAnalytics = ClosedRequestsAnalytics(),
    val mode: RequestsMode = RequestsMode.Active,
    val searchQuery: String = "",
    val statusFilter: ActiveRequestsStatusFilter = ActiveRequestsStatusFilter.Available,
    val sortOrder: ActiveRequestsSortOrder = ActiveRequestsSortOrder.RegisteredAt,
    val selectedRequest: SimpleOneRequest? = null,
    val isInitializing: Boolean = true,
    val isResolvingUser: Boolean = false,
    val initialArchiveReady: Boolean = false,
    val initialLoadRequested: Boolean = false,
    val showConnection: Boolean = false,
    val archiveProgress: String? = null,
    val isOffline: Boolean = false,
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
    private val mutableState = MutableStateFlow(SimpleOneUiState(isOffline = startOffline))
    val state: StateFlow<SimpleOneUiState> = mutableState.asStateFlow()
    private var activeJob: Job? = null
    private var archiveJob: Job? = null
    private var detailJob: Job? = null
    private var authJob: Job? = null
    private var identityJob: Job? = null
    private var sessionEpoch = 0L

    init { restore() }

    fun updateUsername(value: String) { mutableState.update { it.copy(username = value, errorMessage = null) } }
    fun updatePassword(value: String) { mutableState.update { it.copy(password = value, errorMessage = null) } }
    fun updateSearchQuery(value: String) { mutableState.update { it.copy(searchQuery = value) } }
    fun updateStatusFilter(value: ActiveRequestsStatusFilter) { mutableState.update { it.copy(statusFilter = value) } }
    fun updateSortOrder(value: ActiveRequestsSortOrder) { mutableState.update { it.copy(sortOrder = value) } }
    fun updateMode(value: RequestsMode) {
        if (!state.value.initialArchiveReady) return
        mutableState.update { it.copy(mode = value, selectedRequest = null, errorMessage = null) }
    }

    fun initialLoad() {
        val state = state.value
        if (state.isInitializing || state.isClosedLoading || state.isLoading) return
        if (startOffline) {
            mutableState.update { it.copy(initialLoadRequested = true, errorMessage = "Для загрузки заявок требуется подключение к сети.") }
        } else if (!state.isAuthorized) {
            mutableState.update { it.copy(initialLoadRequested = true, showConnection = true, errorMessage = null) }
        } else loadClosedArchive()
    }

    fun dismissConnection() { mutableState.update { it.copy(showConnection = false, password = "", errorMessage = null) } }

    fun signIn() = signIn(loadRequests = true)
    fun signInIdentityOnly() = signIn(loadRequests = false)

    private fun signIn(loadRequests: Boolean) {
        val state = state.value
        if (startOffline || state.isLoading || state.isClosedLoading || authJob?.isActive == true) return
        if (state.username.isBlank() || state.password.isBlank()) {
            mutableState.update { it.copy(errorMessage = "Укажите логин и пароль SimpleOne.") }; return
        }
        val epoch = sessionEpoch
        mutableState.update { it.copy(isLoading = true, errorMessage = null, notice = null) }
        authJob = viewModelScope.launch {
            try {
                repository.authenticate(ownerUserId, state.username, state.password)
                if (epoch != sessionEpoch) return@launch
                mutableState.update { it.copy(password = "", snapshot = SimpleOneSnapshot(), isAuthorized = true, isLoading = false, showConnection = false) }
                if (!loadRequests) ensureCurrentUser()
                else if (!mutableState.value.initialArchiveReady) loadClosedArchive() else refreshActiveRequests()
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                if (epoch == sessionEpoch) mutableState.update { it.copy(isLoading = false,
                    errorMessage = if (error is SimpleOneFailure.Unauthorized) "Проверьте логин и пароль SimpleOne." else error.userMessage()) }
            }
        }
    }

    fun ensureCurrentUser() {
        val current = state.value
        if (startOffline || !current.isAuthorized || current.isInitializing || current.snapshot.currentUser != null || identityJob?.isActive == true) return
        val epoch = sessionEpoch
        mutableState.update { it.copy(isResolvingUser = true) }
        identityJob = viewModelScope.launch {
            try {
                val snapshot = repository.resolveCurrentUser(ownerUserId)
                if (epoch == sessionEpoch) mutableState.update { it.copy(snapshot = if (it.snapshot.currentUser != null && it.snapshot.sessionFingerprint == snapshot.sessionFingerprint) it.snapshot else snapshot, isResolvingUser = false) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (epoch == sessionEpoch) mutableState.update { it.copy(isResolvingUser = false,
                    isAuthorized = it.isAuthorized && error !is SimpleOneFailure.Unauthorized && error !is SimpleOneFailure.MissingCredentials,
                    errorMessage = error.userMessage()) }
            }
        }
    }

    fun refresh() {
        if (!state.value.initialArchiveReady) return
        if (state.value.mode == RequestsMode.Closed) loadClosedArchive() else refreshActiveRequests()
    }

    fun refreshActiveRequests() {
        val state = state.value
        if (startOffline || !state.isAuthorized || state.isInitializing || state.isLoading || activeJob?.isActive == true) return
        val epoch = sessionEpoch
        mutableState.update { it.copy(isLoading = true, errorMessage = null, notice = null) }
        activeJob = viewModelScope.launch {
            try {
                val snapshot = repository.refresh(ownerUserId, mutableState.value.snapshot.takeIf { it.currentUser != null })
                if (epoch == sessionEpoch) mutableState.update { it.copy(snapshot = snapshot, isAuthorized = true, isLoading = false) }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                if (epoch == sessionEpoch) mutableState.update { it.copy(isLoading = false,
                    isAuthorized = it.isAuthorized && error !is SimpleOneFailure.Unauthorized,
                    errorMessage = error.userMessage()) }
            }
        }
    }

    fun refreshAnalytics() { if (state.value.initialArchiveReady) loadClosedArchive() }
    fun refreshClosedRequests() { if (state.value.initialArchiveReady) loadClosedArchive() }

    fun signOut() {
        sessionEpoch++
        activeJob?.cancel(); archiveJob?.cancel(); authJob?.cancel(); detailJob?.cancel(); identityJob?.cancel()
        mutableState.update { it.copy(password = "", isInitializing = false, isAuthorized = false, isLoading = false,
            isClosedLoading = false, isDetailLoading = false, isResolvingUser = false, selectedRequest = null, archiveProgress = null,
            showConnection = false, errorMessage = null, notice = null) }
        viewModelScope.launch { repository.signOut(ownerUserId) }
    }

    fun openRequest(request: SimpleOneRequest) {
        detailJob?.cancel()
        val epoch = sessionEpoch
        mutableState.update { it.copy(selectedRequest = request, isDetailLoading = !startOffline, detailErrorMessage = null) }
        if (startOffline) return
        detailJob = viewModelScope.launch {
            try {
                val detailed = repository.requestDetails(ownerUserId, request, force = true)
                if (epoch != sessionEpoch || mutableState.value.selectedRequest?.id != request.id) return@launch
                mutableState.update { state -> state.copy(
                    snapshot = state.snapshot.copy(activeRequests = state.activeRequests.map { if (it.id == detailed.id) detailed else it }),
                    selectedRequest = detailed, isDetailLoading = false) }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                if (epoch == sessionEpoch) mutableState.update { it.copy(isDetailLoading = false,
                    isAuthorized = it.isAuthorized && error !is SimpleOneFailure.Unauthorized, detailErrorMessage = error.userMessage()) }
            }
        }
    }
    fun closeRequest() {
        detailJob?.cancel()
        mutableState.update { it.copy(selectedRequest = null, isDetailLoading = false, detailErrorMessage = null) }
    }
    suspend fun browserAuthKey(): String? = if (startOffline) null else repository.storedSession(ownerUserId)?.authKey
    suspend fun exportArchive(): ByteArray = repository.exportClosedArchive(ownerUserId)

    fun importArchive(bytes: ByteArray) = mutateArchive { repository.importClosedArchive(ownerUserId, bytes) }
    fun cleanupArchive(period: ru.lumastack.lumawork.domain.requests.ClosedRequestsPeriod?) = mutateArchive {
        repository.cleanupClosedArchive(ownerUserId) { request -> period == null ||
            ru.lumastack.lumawork.domain.requests.parseClosedRequestsDate(request.closedEffectiveTime())?.toLocalDate()?.let { it in period } == true }
    }
    private fun mutateArchive(block: suspend () -> ClosedSimpleOneArchive) {
        if (state.value.isClosedLoading || state.value.isLoading) return
        val epoch = sessionEpoch
        mutableState.update { it.copy(isClosedLoading = true, errorMessage = null) }
        archiveJob = viewModelScope.launch {
            try {
                val archive = block()
                val prepared = prepareArchive(archive)
                if (epoch == sessionEpoch) mutableState.update { it.copy(closedArchive = archive, closedArchiveItems = prepared.items, closedAnalytics = prepared.analytics,
                    hasLoadedClosedArchive = true, initialArchiveReady = archive.isReady, isClosedLoading = false) }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { if (epoch == sessionEpoch) mutableState.update { it.copy(isClosedLoading = false, errorMessage = error.userMessage()) } }
        }
    }

    private fun restore() {
        val epoch = sessionEpoch
        viewModelScope.launch {
            val session = localOrNull { repository.storedSession(ownerUserId) }
            val username = session?.username ?: localOrNull { repository.rememberedUsername(ownerUserId) }.orEmpty()
            val cached = localOrNull { repository.readSnapshot(ownerUserId) }
            if (epoch != sessionEpoch) return@launch
            // Session and active requests must not wait for the closed archive and its analytics.
            mutableState.update { it.copy(username = username, snapshot = cached ?: it.snapshot, isAuthorized = session != null) }
            val archive = localOrNull { repository.readClosedArchive(ownerUserId) }
            val prepared = archive?.let { prepareArchive(it) } ?: PreparedArchive(emptyList(), ClosedRequestsAnalytics())
            if (epoch != sessionEpoch) return@launch
            mutableState.update { it.copy(
                closedArchive = archive ?: it.closedArchive, closedArchiveItems = prepared.items, closedAnalytics = prepared.analytics, hasLoadedClosedArchive = archive != null,
                initialArchiveReady = archive?.isReady == true, isInitializing = false) }
            // First entry performs local reads only. Active networking starts after a full baseline exists.
            if (archive?.isReady == true) refreshActiveRequests()
        }
    }

    private fun loadClosedArchive() {
        val current = state.value
        if (startOffline || current.isInitializing || current.isClosedLoading || archiveJob?.isActive == true) return
        if (!current.isAuthorized) { mutableState.update { it.copy(showConnection = true) }; return }
        val epoch = sessionEpoch
        mutableState.update { it.copy(initialLoadRequested = true, isClosedLoading = true, errorMessage = null,
            archiveProgress = "Загрузка закрытых заявок…") }
        archiveJob = viewModelScope.launch {
            try {
                val archive = repository.refreshClosedArchive(ownerUserId, mutableState.value.snapshot) { progress ->
                    if (epoch == sessionEpoch) mutableState.update { it.copy(archiveProgress = progress) }
                }
                val prepared = prepareArchive(archive)
                if (epoch != sessionEpoch) return@launch
                val wasReady = mutableState.value.initialArchiveReady
                mutableState.update { it.copy(closedArchive = archive, closedArchiveItems = prepared.items, closedAnalytics = prepared.analytics, hasLoadedClosedArchive = true,
                    initialArchiveReady = archive.isReady, isClosedLoading = false, isAuthorized = true, archiveProgress = null) }
                if (!wasReady) refreshActiveRequests()
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                if (epoch == sessionEpoch) mutableState.update { it.copy(isClosedLoading = false, archiveProgress = null,
                    isAuthorized = it.isAuthorized && error !is SimpleOneFailure.Unauthorized, errorMessage = error.userMessage()) }
            }
        }
    }
    // Owned by the screen model, so navigation never discards the parsed/searchable archive.
    private data class PreparedArchive(val items: List<ClosedRequestsItem>, val analytics: ClosedRequestsAnalytics)

    private suspend fun prepareArchive(archive: ClosedSimpleOneArchive): PreparedArchive {
        val current = state.value
        if (archive.requests === current.closedArchive.requests) return PreparedArchive(current.closedArchiveItems, current.closedAnalytics)
        return withContext(Dispatchers.Default) {
            val items = prepareClosedRequests(archive.requests)
            PreparedArchive(items, buildClosedRequestsAnalytics(items))
        }
    }

    private suspend fun <T> localOrNull(block: suspend () -> T): T? = try { block() }
        catch (error: CancellationException) { throw error }
        catch (_: Exception) { null }

    private fun Throwable.userMessage(): String = when (this) {
        is SimpleOneFailure, is ru.lumastack.lumawork.data.simpleone.SimpleOneXlsxException -> message ?: "Не удалось загрузить данные SimpleOne."
        else -> "Не удалось загрузить или сохранить данные SimpleOne. Попробуйте ещё раз."
    }

    companion object {
        fun factory(repository: SimpleOneRepository, ownerUserId: String, startOffline: Boolean): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = SimpleOneViewModel(repository, ownerUserId, startOffline) as T
            }
    }
}
