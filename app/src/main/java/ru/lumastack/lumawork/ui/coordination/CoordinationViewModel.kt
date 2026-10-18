package ru.lumastack.lumawork.ui.coordination

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import ru.lumastack.lumawork.data.coordination.CoordinationRepository
import ru.lumastack.lumawork.data.simpleone.SimpleOneFailure
import ru.lumastack.lumawork.data.simpleone.SimpleOneRepository
import ru.lumastack.lumawork.domain.coordination.*
import ru.lumastack.lumawork.domain.simpleone.SimpleOneRequest

data class CoordinationUiState(
    val simpleOneUserId: String = "", val isAuthorized: Boolean = false,
    val section: CoordinationSection = CoordinationSection.Distribution, val region: CoordinationRegion = CoordinationRegion.Alushta,
    val snapshot: CoordinationSnapshot = CoordinationSnapshot(), val selectedEngineerId: String? = null,
    val selectedRequest: SimpleOneRequest? = null, val isArchiveOpen: Boolean = false,
    val isLoading: Boolean = false, val isDetailLoading: Boolean = false, val loadingStatusIds: Set<String> = emptySet(),
    val errorMessage: String? = null, val detailErrorMessage: String? = null, val statusErrorMessage: String? = null,
) {
    val currentGroup: CoordinationRequestGroup? get() = when (section) {
        CoordinationSection.Distribution -> snapshot.regions[region.name]
        CoordinationSection.ReturnEquipment -> snapshot.returnEquipment
    }
    val engineerRequests: List<SimpleOneRequest> get() = currentGroup?.requests.orEmpty()
        .filter { it.coordinationEngineerKey() == selectedEngineerId }.sortedCoordinationRequests()
}

class CoordinationViewModel(
    private val coordinationRepository: CoordinationRepository, private val simpleOneRepository: SimpleOneRepository,
    private val ownerUserId: String, private val startOffline: Boolean,
) : ViewModel() {
    private val mutableState = MutableStateFlow(CoordinationUiState())
    val state = mutableState.asStateFlow()
    private var loadJob: Job? = null; private var detailJob: Job? = null; private var statusesJob: Job? = null
    private var sessionEpoch = 0L; private var loadEpoch = 0L; private var detailEpoch = 0L; private var statusesEpoch = 0L
    fun activate(simpleOneUserId: String, isAuthorized: Boolean) {
        val current = state.value
        if (!isAuthorized || simpleOneUserId.isBlank()) {
            sessionEpoch++; stopLoads()
            mutableState.value = CoordinationUiState(section = current.section, region = current.region)
            return
        }
        if (current.simpleOneUserId == simpleOneUserId && current.isAuthorized) return
        val epoch = ++sessionEpoch; stopLoads()
        mutableState.value = CoordinationUiState(simpleOneUserId, true, current.section, current.region)
        loadJob = viewModelScope.launch {
            try {
                val cached = coordinationRepository.read(ownerUserId, simpleOneUserId)
                if (epoch != sessionEpoch) return@launch
                mutableState.update { it.copy(snapshot = cached ?: CoordinationSnapshot(simpleOneUserId), region = cached?.selectedRegion ?: it.region) }
                if (!startOffline) refreshCurrent()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { if (epoch == sessionEpoch) mutableState.update { it.copy(errorMessage = error.userMessage()) } }
        }
    }
    private fun stopLoads() { loadJob?.cancel(); detailJob?.cancel(); statusesJob?.cancel(); loadEpoch++; detailEpoch++; statusesEpoch++ }
    private fun applySnapshot(snapshot: CoordinationSnapshot?) { if (snapshot != null) mutableState.update {
        if (snapshot.simpleOneUserId == it.simpleOneUserId && snapshot.revision >= it.snapshot.revision) it.copy(snapshot = snapshot) else it
    } }
    fun selectSection(section: CoordinationSection) {
        if (state.value.section == section) return
        stopLoads()
        mutableState.update { it.copy(section = section, selectedEngineerId = null, selectedRequest = null, isLoading = false, isDetailLoading = false, loadingStatusIds = emptySet(), errorMessage = null, statusErrorMessage = null) }
        refresh()
    }
    fun selectRegion(region: CoordinationRegion) {
        if (state.value.region == region) return
        stopLoads()
        mutableState.update { it.copy(region = region, selectedEngineerId = null, selectedRequest = null, isLoading = false, loadingStatusIds = emptySet(), errorMessage = null, statusErrorMessage = null) }
        val user = state.value.simpleOneUserId
        loadJob = viewModelScope.launch {
            try { applySnapshot(coordinationRepository.selectRegion(ownerUserId, user, region)); if (!startOffline) refreshCurrent() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { mutableState.update { it.copy(errorMessage = error.userMessage()) } }
        }
    }
    fun refresh() {
        if (startOffline || !state.value.isAuthorized) return
        loadJob?.cancel(); statusesJob?.cancel(); statusesEpoch++
        loadJob = viewModelScope.launch { refreshCurrent() }
    }
    private suspend fun refreshCurrent() {
        val captured = state.value; val epoch = ++loadEpoch; val session = sessionEpoch
        if (!captured.isAuthorized || startOffline) return
        mutableState.update { it.copy(isLoading = true, errorMessage = null, loadingStatusIds = emptySet()) }
        try {
            val snapshot = when (captured.section) {
                CoordinationSection.Distribution -> coordinationRepository.refreshRegion(ownerUserId, captured.simpleOneUserId, captured.region)
                CoordinationSection.ReturnEquipment -> coordinationRepository.refreshReturnEquipment(ownerUserId, captured.simpleOneUserId)
            }
            if (epoch != loadEpoch || session != sessionEpoch) return
            applySnapshot(snapshot); mutableState.update { it.copy(isLoading = false) }; loadStatuses()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { if (epoch == loadEpoch && session == sessionEpoch) mutableState.update {
            it.copy(isLoading = false, isAuthorized = it.isAuthorized && error !is SimpleOneFailure.Unauthorized, errorMessage = error.userMessage())
        } }
    }
    fun openEngineer(engineerId: String) { mutableState.update { it.copy(selectedEngineerId = engineerId, statusErrorMessage = null) }; loadStatuses() }
    fun openArchive() { mutableState.update { it.copy(isArchiveOpen = true, selectedEngineerId = null, loadingStatusIds = emptySet()) }; statusesJob?.cancel(); statusesEpoch++ }
    fun openRequest(request: SimpleOneRequest, force: Boolean = false) {
        detailJob?.cancel(); val epoch = ++detailEpoch; val session = sessionEpoch; val user = state.value.simpleOneUserId
        mutableState.update { it.copy(selectedRequest = request, isDetailLoading = !startOffline, detailErrorMessage = null) }
        if (startOffline) return
        detailJob = viewModelScope.launch {
            try {
                val detailed = simpleOneRepository.requestDetails(ownerUserId, request, force, requiresCurrentVersion = true)
                if (epoch != detailEpoch || session != sessionEpoch) return@launch
                applySnapshot(coordinationRepository.replaceRequest(ownerUserId, user, detailed, request.sysUpdatedAt))
                if (epoch == detailEpoch && session == sessionEpoch) mutableState.update { it.copy(selectedRequest = detailed, isDetailLoading = false) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { if (epoch == detailEpoch && session == sessionEpoch) mutableState.update {
                it.copy(isDetailLoading = false, isAuthorized = it.isAuthorized && error !is SimpleOneFailure.Unauthorized, detailErrorMessage = error.userMessage())
            } }
        }
    }
    private fun loadStatuses() {
        val captured = state.value
        if (startOffline || !captured.isAuthorized || captured.isArchiveOpen) return
        val requests = if (captured.section == CoordinationSection.ReturnEquipment) captured.currentGroup?.requests.orEmpty() else {
            if (captured.selectedEngineerId == null) return
            captured.engineerRequests
        }
        statusesJob?.cancel(); val epoch = ++statusesEpoch; val session = sessionEpoch
        statusesJob = viewModelScope.launch {
            mutableState.update { it.copy(loadingStatusIds = requests.map { it.id }.toSet(), statusErrorMessage = null) }
            try {
                for (chunk in requests.chunked(4)) {
                    val results = coroutineScope { chunk.map { request -> async {
                        try { simpleOneRepository.requestDetails(ownerUserId, request, requiresCurrentVersion = true) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (unauthorized: SimpleOneFailure.Unauthorized) { throw unauthorized }
                        catch (error: Exception) { if (epoch == statusesEpoch) mutableState.update { it.copy(statusErrorMessage = error.userMessage()) }; null }
                    } }.awaitAll().filterNotNull() }
                    if (epoch != statusesEpoch || session != sessionEpoch) return@launch
                    applySnapshot(coordinationRepository.replaceRequests(ownerUserId, captured.simpleOneUserId, results, chunk.associate { it.id to it.sysUpdatedAt }))
                    if (epoch == statusesEpoch) mutableState.update { it.copy(loadingStatusIds = it.loadingStatusIds - chunk.map { request -> request.id }.toSet()) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { if (epoch == statusesEpoch && session == sessionEpoch) mutableState.update {
                it.copy(statusErrorMessage = error.userMessage(), isAuthorized = it.isAuthorized && error !is SimpleOneFailure.Unauthorized)
            } } finally { if (epoch == statusesEpoch) mutableState.update { it.copy(loadingStatusIds = emptySet()) } }
        }
    }
    fun back() {
        val closingArchive = state.value.isArchiveOpen && state.value.selectedRequest == null
        detailJob?.cancel(); detailEpoch++
        mutableState.update { when {
            it.selectedRequest != null -> it.copy(selectedRequest = null, isDetailLoading = false, detailErrorMessage = null)
            it.selectedEngineerId != null -> { statusesJob?.cancel(); statusesEpoch++; it.copy(selectedEngineerId = null, loadingStatusIds = emptySet(), statusErrorMessage = null) }
            it.isArchiveOpen -> it.copy(isArchiveOpen = false)
            else -> it
        } }
        if (closingArchive) loadStatuses()
    }
    private fun Throwable.userMessage() = (this as? SimpleOneFailure)?.message ?: "Не удалось загрузить координацию."
    companion object { fun factory(coordinationRepository: CoordinationRepository, simpleOneRepository: SimpleOneRepository, ownerUserId: String, startOffline: Boolean) = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = CoordinationViewModel(coordinationRepository, simpleOneRepository, ownerUserId, startOffline) as T
    } }
}
