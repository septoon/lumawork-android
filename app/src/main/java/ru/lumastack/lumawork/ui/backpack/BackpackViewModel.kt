package ru.lumastack.lumawork.ui.backpack

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import ru.lumastack.lumawork.data.backpack.BackpackPhotoRepository
import ru.lumastack.lumawork.data.backpack.BackpackRepository
import ru.lumastack.lumawork.data.simpleone.SimpleOneFailure
import ru.lumastack.lumawork.domain.backpack.*

data class BackpackUiState(
    val simpleOneUserId: String = "", val isAuthorized: Boolean = false,
    val snapshot: BackpackSnapshot? = null, val photoCatalog: BackpackPhotoCatalog = BackpackPhotoCatalog(),
    val officePhotoCatalog: BackpackPhotoCatalog = BackpackPhotoCatalog(),
    val query: String = "", val selectedGroupId: String? = null, val locationFilter: BackpackLocation? = null,
    val isLoading: Boolean = false, val loadingDetailIds: Set<String> = emptySet(), val isLoadingReturns: Boolean = false,
    val officeOpen: Boolean = false, val officeQuery: String = "", val isLoadingOffice: Boolean = false,
    val selectedOfficeItem: OfficeEquipmentItem? = null, val isLoadingOfficeDetails: Boolean = false,
    val errorMessage: String? = null, val officeError: String? = null,
)

class BackpackViewModel(
    private val repository: BackpackRepository, private val photoRepository: BackpackPhotoRepository,
    private val ownerUserId: String, private val startOffline: Boolean,
) : ViewModel() {
    private val mutableState = MutableStateFlow(BackpackUiState())
    val state: StateFlow<BackpackUiState> = mutableState.asStateFlow()
    private var loadJob: Job? = null
    private var secondaryJob: Job? = null
    private var officeJob: Job? = null
    private var detailJob: Job? = null
    private var generation = 0L

    init { viewModelScope.launch {
        runCatching { photoRepository.cached() }.getOrNull()?.let { catalog -> mutableState.update { it.copy(photoCatalog = catalog) } }
        runCatching { photoRepository.cachedEquipment() }.getOrNull()?.let { catalog -> mutableState.update { it.copy(officePhotoCatalog = catalog) } }
    } }

    fun activate(simpleOneUserId: String, isAuthorized: Boolean) {
        val old = state.value
        if (old.simpleOneUserId == simpleOneUserId && simpleOneUserId.isNotBlank()) {
            if (!isAuthorized && old.isAuthorized) {
                ++generation
                loadJob?.cancel(); secondaryJob?.cancel(); officeJob?.cancel(); detailJob?.cancel()
                mutableState.update { it.copy(isAuthorized = false, isLoading = false, loadingDetailIds = emptySet(), isLoadingReturns = false, isLoadingOffice = false, isLoadingOfficeDetails = false) }
            } else {
                mutableState.update { it.copy(isAuthorized = isAuthorized) }
                if (isAuthorized && !startOffline && (!old.isAuthorized || old.snapshot == null) && !old.isLoading) refresh()
            }
            return
        }
        val revision = ++generation
        loadJob?.cancel(); secondaryJob?.cancel(); officeJob?.cancel(); detailJob?.cancel()
        mutableState.value = BackpackUiState(simpleOneUserId = simpleOneUserId, isAuthorized = isAuthorized,
            photoCatalog = old.photoCatalog, officePhotoCatalog = old.officePhotoCatalog, isLoading = simpleOneUserId.isNotBlank())
        if (simpleOneUserId.isBlank()) return
        loadJob = viewModelScope.launch {
            val cached = repository.read(ownerUserId, simpleOneUserId)
            if (generation != revision) return@launch
            mutableState.update { it.copy(snapshot = cached, isLoading = false) }
            if (state.value.isAuthorized && !startOffline) refreshCurrent(force = false, revision)
        }
    }

    fun refresh() {
        if (startOffline || !state.value.isAuthorized || state.value.isLoading) return
        val revision = generation
        loadJob?.cancel(); secondaryJob?.cancel()
        loadJob = viewModelScope.launch { refreshCurrent(force = true, revision) }
    }
    fun updateQuery(value: String) { mutableState.update { it.copy(query = value) } }
    fun applyScan(value: String) { SerialBarcode.serial(value)?.let { serial -> mutableState.update { it.copy(query = serial, locationFilter = null, selectedGroupId = null) } } }
    fun selectLocation(location: BackpackLocation?) { mutableState.update { it.copy(locationFilter = location) } }
    fun openGroup(groupId: String) { mutableState.update { it.copy(selectedGroupId = groupId) } }
    fun closeGroup() { mutableState.update { it.copy(selectedGroupId = null) } }
    fun setLocation(item: BackpackItem, location: BackpackLocation) {
        val current = state.value
        val snapshot = current.snapshot ?: return
        mutableState.update { it.copy(snapshot = snapshot.copy(locationOverrides = snapshot.locationOverrides + (item.storageIdentity to location))) }
        val revision = generation
        viewModelScope.launch {
            try { repository.updateLocation(ownerUserId, current.simpleOneUserId, item, location) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (generation == revision) mutableState.update { it.copy(errorMessage = "Не удалось сохранить местоположение.") } }
        }
    }
    fun openOffice() { mutableState.update { it.copy(officeOpen = true) }; if (state.value.isAuthorized && !startOffline) refreshOffice() }
    fun closeOffice() { mutableState.update { it.copy(officeOpen = false, selectedOfficeItem = null) }; detailJob?.cancel() }
    fun updateOfficeQuery(value: String) { mutableState.update { it.copy(officeQuery = value) } }
    fun closeOfficeItem() { detailJob?.cancel(); mutableState.update { it.copy(selectedOfficeItem = null, isLoadingOfficeDetails = false) } }
    fun refreshOffice() {
        if (!state.value.isAuthorized || startOffline || state.value.isLoadingOffice || officeJob?.isActive == true) return
        val user = state.value.simpleOneUserId
        val revision = generation
        mutableState.update { it.copy(isLoadingOffice = true, officeError = null) }
        officeJob = viewModelScope.launch {
            launch {
                photoRepository.refreshEquipment()?.let { catalog ->
                    if (generation == revision) mutableState.update { it.copy(officePhotoCatalog = catalog) }
                }
            }
            try {
                val snapshot = repository.refreshOffice(ownerUserId, user)
                if (generation == revision) mutableState.update { it.copy(snapshot = (it.snapshot ?: snapshot).copy(officeEquipment = snapshot.officeEquipment, officeUpdatedAtEpochMillis = snapshot.officeUpdatedAtEpochMillis)) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { if (generation == revision) reportFailure(error, office = true) }
            finally { if (generation == revision) mutableState.update { it.copy(isLoadingOffice = false) } }
        }
    }
    fun openOfficeItem(item: OfficeEquipmentItem) {
        detailJob?.cancel()
        mutableState.update { it.copy(selectedOfficeItem = item, isLoadingOfficeDetails = !startOffline && it.isAuthorized, officeError = null) }
        if (startOffline || !state.value.isAuthorized) return
        val revision = generation
        detailJob = viewModelScope.launch {
            try {
                val detailed = repository.officeDetails(ownerUserId, state.value.simpleOneUserId, item)
                if (generation == revision && state.value.selectedOfficeItem?.id == item.id) mutableState.update { it.copy(selectedOfficeItem = detailed, snapshot = it.snapshot?.copy(officeEquipment = it.snapshot.officeEquipment.map { old -> if (old.id == detailed.id) detailed else old })) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { if (generation == revision) reportFailure(error, office = true) }
            finally { if (generation == revision && state.value.selectedOfficeItem?.id == item.id) mutableState.update { it.copy(isLoadingOfficeDetails = false) } }
        }
    }

    private suspend fun refreshCurrent(force: Boolean, revision: Long) {
        val user = state.value.simpleOneUserId
        mutableState.update { it.copy(isLoading = true, errorMessage = null) }
        try {
            val snapshot = repository.refresh(ownerUserId, user)
            if (generation != revision) return
            mutableState.update { it.copy(snapshot = snapshot.copy(locationOverrides = it.snapshot?.locationOverrides ?: snapshot.locationOverrides), isLoading = false) }
            secondaryJob = viewModelScope.launch {
                supervisorScope {
                    launch {
                        try { repository.hydrate(ownerUserId, user, snapshot, force) { progress, ids ->
                            if (generation == revision) mutableState.update { it.copy(snapshot = (it.snapshot ?: progress).copy(items = progress.items), loadingDetailIds = ids) }
                        } } catch (cancelled: CancellationException) { throw cancelled }
                        catch (error: Exception) { if (generation == revision) reportFailure(error) }
                        finally { if (generation == revision) mutableState.update { it.copy(loadingDetailIds = emptySet()) } }
                    }
                    launch {
                        mutableState.update { it.copy(isLoadingReturns = true) }
                        try {
                            val returns = repository.refreshReturns(ownerUserId, user)
                            if (generation == revision) mutableState.update { it.copy(snapshot = (it.snapshot ?: returns).copy(returnSerialNumbers = returns.returnSerialNumbers, returnUpdatedAtEpochMillis = returns.returnUpdatedAtEpochMillis)) }
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (error: Exception) { if (generation == revision && error is SimpleOneFailure.Unauthorized) reportFailure(error) }
                        finally { if (generation == revision) mutableState.update { it.copy(isLoadingReturns = false) } }
                    }
                    launch { photoRepository.refresh()?.let { catalog -> if (generation == revision) mutableState.update { it.copy(photoCatalog = catalog) } } }
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            if (generation == revision) {
                mutableState.update { it.copy(isLoading = false) }
                reportFailure(error)
            }
        }
    }
    private fun reportFailure(error: Exception, office: Boolean = false) {
        if (error is SimpleOneFailure.Unauthorized || error is SimpleOneFailure.MissingCredentials) activate(state.value.simpleOneUserId, false)
        mutableState.update { if (office) it.copy(officeError = error.userMessage()) else it.copy(errorMessage = error.userMessage()) }
    }
    private fun Throwable.userMessage(): String = (this as? SimpleOneFailure)?.message ?: "Не удалось загрузить оборудование."
    companion object {
        fun factory(repository: BackpackRepository, photoRepository: BackpackPhotoRepository, ownerUserId: String, startOffline: Boolean): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = BackpackViewModel(repository, photoRepository, ownerUserId, startOffline) as T
        }
    }
}
