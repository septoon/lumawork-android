package ru.lumastack.lumawork.ui.employees

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.lumastack.lumawork.data.employees.EmployeeRepository
import ru.lumastack.lumawork.data.simpleone.SimpleOneFailure
import ru.lumastack.lumawork.domain.employees.*

data class EmployeeUiState(
    val simpleOneUserId: String = "",
    val isAuthorized: Boolean = false,
    val snapshot: EmployeeSnapshot = EmployeeSnapshot(),
    val visibleDirectory: EmployeeDirectory = EmployeeDirectory(),
    val query: String = "",
    val isAddressPickerOpen: Boolean = false,
    val addressQuery: String = "",
    val addressResults: List<EmployeeAddress> = emptyList(),
    val selectedEmployee: Employee? = null,
    val isLoading: Boolean = false,
    val isSearchingAddresses: Boolean = false,
    val isDetailLoading: Boolean = false,
    val errorMessage: String? = null,
    val addressErrorMessage: String? = null,
    val detailErrorMessage: String? = null,
) {
    val employees: List<Employee> get() = visibleDirectory.employees
}

class EmployeeViewModel(
    private val repository: EmployeeRepository,
    private val ownerUserId: String,
    private val startOffline: Boolean,
) : ViewModel() {
    private val mutableState = MutableStateFlow(EmployeeUiState())
    val state: StateFlow<EmployeeUiState> = mutableState.asStateFlow()
    private var bootstrapJob: Job? = null
    private var loadJob: Job? = null
    private var addressJob: Job? = null
    private var detailJob: Job? = null
    private var generation = 0L
    private var directoryRevision = 0L
    private var addressRevision = 0L
    private var detailRevision = 0L

    fun activate(simpleOneUserId: String, isAuthorized: Boolean) {
        val old = state.value
        if (old.simpleOneUserId == simpleOneUserId && simpleOneUserId.isNotBlank()) {
            if (old.isAuthorized && !isAuthorized) {
                cancelRequests()
                mutableState.update { it.copy(isAuthorized = false, isLoading = false, isSearchingAddresses = false, isDetailLoading = false,
                    selectedEmployee = null, isAddressPickerOpen = false) }
            } else {
                mutableState.update { it.copy(isAuthorized = isAuthorized) }
                if (!old.isAuthorized && isAuthorized && !startOffline && bootstrapJob?.isActive != true) {
                    val revision = generation
                    bootstrapJob = viewModelScope.launch { bootstrapAndRefresh(revision) }
                }
            }
            return
        }
        cancelRequests()
        mutableState.value = EmployeeUiState(simpleOneUserId = simpleOneUserId, isAuthorized = isAuthorized && simpleOneUserId.isNotBlank(), isLoading = simpleOneUserId.isNotBlank())
        if (simpleOneUserId.isBlank()) return
        val revision = generation
        bootstrapJob = viewModelScope.launch {
            val cached = repository.read(ownerUserId, simpleOneUserId) ?: EmployeeSnapshot(simpleOneUserId)
            if (generation != revision) return@launch
            mutableState.update { it.copy(snapshot = cached, visibleDirectory = cached.currentDirectory(it.query), isLoading = false) }
            if (state.value.isAuthorized && !startOffline) bootstrapAndRefresh(revision)
        }
    }

    fun updateQuery(value: String) {
        mutableState.update { it.copy(query = value, visibleDirectory = it.snapshot.currentDirectory(value), errorMessage = null) }
        requestDirectory(debounce = value.isNotBlank())
    }

    fun refresh() = requestDirectory(debounce = false)

    private fun requestDirectory(debounce: Boolean) {
        loadJob?.cancel()
        val request = ++directoryRevision
        mutableState.update { it.copy(isLoading = false) }
        if (!canLoad() || state.value.snapshot.selectedAddress == null) return
        val revision = generation
        loadJob = viewModelScope.launch {
            if (debounce) delay(SEARCH_DEBOUNCE_MILLIS)
            refreshCurrent(revision, request)
        }
    }

    fun openAddressPicker() {
        if (state.value.isAuthorized) mutableState.update { it.copy(isAddressPickerOpen = true, addressErrorMessage = null) }
    }

    fun closeAddressPicker() {
        addressJob?.cancel(); ++addressRevision
        mutableState.update { it.copy(isAddressPickerOpen = false, addressQuery = "", addressResults = emptyList(), isSearchingAddresses = false) }
    }

    fun updateAddressQuery(value: String) {
        addressJob?.cancel()
        val request = ++addressRevision
        val query = value.trim()
        mutableState.update { it.copy(addressQuery = value, addressErrorMessage = null, addressResults = emptyList(), isSearchingAddresses = false) }
        if (query.length < 2 || !canLoad()) return
        val revision = generation
        addressJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MILLIS)
            mutableState.update { it.copy(isSearchingAddresses = true) }
            try {
                val results = repository.searchAddresses(ownerUserId, query)
                if (generation == revision && addressRevision == request) mutableState.update { it.copy(addressResults = results) }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { if (generation == revision && addressRevision == request) reportFailure(error, address = true) }
            finally { if (generation == revision && addressRevision == request) mutableState.update { it.copy(isSearchingAddresses = false) } }
        }
    }

    fun selectAddress(address: EmployeeAddress) {
        changeAddress { user -> repository.selectAddress(ownerUserId, user, address) }
    }

    fun removeAddress(address: EmployeeAddress) {
        changeAddress(closePicker = false) { user -> repository.removeAddress(ownerUserId, user, address) }
    }

    private fun changeAddress(closePicker: Boolean = true, change: suspend (String) -> EmployeeSnapshot) {
        if (state.value.simpleOneUserId.isBlank()) return
        bootstrapJob?.cancel(); loadJob?.cancel()
        closeEmployee()
        val request = ++directoryRevision
        val revision = generation
        val user = state.value.simpleOneUserId
        if (closePicker) closeAddressPicker()
        loadJob = viewModelScope.launch {
            try {
                val snapshot = change(user)
                if (generation != revision || directoryRevision != request) return@launch
                mutableState.update { it.copy(snapshot = snapshot, visibleDirectory = snapshot.currentDirectory(), query = "", errorMessage = null, isLoading = false) }
                if (canLoad()) refreshCurrent(revision, request)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { if (generation == revision && directoryRevision == request) reportFailure(error, address = true) }
        }
    }

    fun openEmployee(employee: Employee) = loadEmployee(employee, force = false)
    fun refreshEmployee() { state.value.selectedEmployee?.let { loadEmployee(it, force = true) } }
    fun showActionError(message: String) { mutableState.update { it.copy(detailErrorMessage = message) } }

    private fun loadEmployee(employee: Employee, force: Boolean) {
        detailJob?.cancel()
        val request = ++detailRevision
        val cached = state.value.snapshot.detailCache[employee.sysId]
        val shouldFetch = canLoad() && (force || cached?.detailSections.isNullOrEmpty())
        mutableState.update { it.copy(selectedEmployee = cached ?: employee,
            isDetailLoading = shouldFetch, detailErrorMessage = null) }
        if (!shouldFetch) return
        val revision = generation
        val user = state.value.simpleOneUserId
        detailJob = viewModelScope.launch {
            try {
                val (snapshot, detailed) = repository.employeeDetails(ownerUserId, user, cached ?: employee)
                if (generation == revision && detailRevision == request && state.value.selectedEmployee?.sysId == employee.sysId) {
                    mutableState.update { it.copy(snapshot = snapshot, selectedEmployee = detailed,
                        visibleDirectory = it.visibleDirectory.copy(employees = it.employees.map { old -> if (old.sysId == detailed.sysId) detailed else old })) }
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { if (generation == revision && detailRevision == request) reportFailure(error, detail = true) }
            finally { if (generation == revision && detailRevision == request) mutableState.update { it.copy(isDetailLoading = false) } }
        }
    }

    fun closeEmployee() {
        detailJob?.cancel(); ++detailRevision
        mutableState.update { it.copy(selectedEmployee = null, isDetailLoading = false, detailErrorMessage = null) }
    }

    private suspend fun bootstrapAndRefresh(revision: Long) {
        if (!canLoad()) return
        mutableState.update { it.copy(isLoading = it.snapshot.selectedAddress == null, errorMessage = null) }
        try {
            val snapshot = repository.bootstrap(ownerUserId, state.value.simpleOneUserId)
            if (generation != revision || !canLoad()) return
            mutableState.update { it.copy(snapshot = snapshot, visibleDirectory = snapshot.currentDirectory(it.query), isLoading = false) }
            if (snapshot.selectedAddress != null) refreshCurrent(revision, ++directoryRevision)
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            if (generation == revision) {
                mutableState.update { it.copy(isLoading = false) }
                reportFailure(error)
            }
        }
    }

    private suspend fun refreshCurrent(revision: Long, request: Long) {
        val current = state.value
        val address = current.snapshot.selectedAddress ?: return
        if (!canLoad()) return
        mutableState.update { it.copy(isLoading = true, errorMessage = null) }
        try {
            val (snapshot, directory) = repository.refreshDirectory(ownerUserId, current.simpleOneUserId, address, current.query)
            if (generation == revision && directoryRevision == request && state.value.snapshot.selectedAddress?.sysId == address.sysId && state.value.query == current.query) {
                mutableState.update { it.copy(snapshot = snapshot, visibleDirectory = directory) }
            }
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) { if (generation == revision && directoryRevision == request) reportFailure(error) }
        finally { if (generation == revision && directoryRevision == request) mutableState.update { it.copy(isLoading = false) } }
    }

    private fun canLoad() = !startOffline && state.value.isAuthorized && state.value.simpleOneUserId.isNotBlank()
    private fun cancelRequests() {
        ++generation; ++directoryRevision; ++addressRevision; ++detailRevision
        bootstrapJob?.cancel(); loadJob?.cancel(); addressJob?.cancel(); detailJob?.cancel()
    }
    private fun reportFailure(error: Exception, address: Boolean = false, detail: Boolean = false) {
        if (error is SimpleOneFailure.Unauthorized || error is SimpleOneFailure.MissingCredentials) activate(state.value.simpleOneUserId, false)
        val message = (error as? SimpleOneFailure)?.message ?: "Не удалось загрузить данные сотрудников."
        mutableState.update { when { address -> it.copy(addressErrorMessage = message); detail -> it.copy(detailErrorMessage = message); else -> it.copy(errorMessage = message) } }
    }
    private fun EmployeeSnapshot.currentDirectory(query: String = ""): EmployeeDirectory {
        val directory = selectedAddress?.sysId?.let(directoriesByAddressId::get) ?: EmployeeDirectory()
        return directory.copy(employees = filterEmployees(directory.employees, query))
    }
    companion object {
        private const val SEARCH_DEBOUNCE_MILLIS = 350L
        fun factory(repository: EmployeeRepository, ownerUserId: String, startOffline: Boolean): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = EmployeeViewModel(repository, ownerUserId, startOffline) as T
        }
    }
}
