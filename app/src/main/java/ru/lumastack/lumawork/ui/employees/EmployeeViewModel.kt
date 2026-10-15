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
import ru.lumastack.lumawork.domain.employees.Employee
import ru.lumastack.lumawork.domain.employees.EmployeeAddress
import ru.lumastack.lumawork.domain.employees.EmployeeDirectory
import ru.lumastack.lumawork.domain.employees.EmployeeSnapshot
import ru.lumastack.lumawork.domain.employees.filterEmployees

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
    val employees: List<Employee>
        get() = visibleDirectory.employees
}

class EmployeeViewModel(
    private val repository: EmployeeRepository,
    private val ownerUserId: String,
    private val startOffline: Boolean,
) : ViewModel() {
    private val mutableState = MutableStateFlow(EmployeeUiState())
    val state: StateFlow<EmployeeUiState> = mutableState.asStateFlow()
    private var loadJob: Job? = null
    private var searchJob: Job? = null
    private var addressJob: Job? = null
    private var detailJob: Job? = null

    fun activate(simpleOneUserId: String, isAuthorized: Boolean) {
        if (simpleOneUserId.isBlank()) {
            mutableState.update { it.copy(isAuthorized = false) }
            return
        }
        if (mutableState.value.simpleOneUserId == simpleOneUserId) {
            val becameAuthorized = !mutableState.value.isAuthorized && isAuthorized
            mutableState.update { it.copy(isAuthorized = isAuthorized) }
            if (becameAuthorized && !startOffline) {
                loadJob?.cancel()
                loadJob = viewModelScope.launch { bootstrapAndRefresh(mutableState.value.snapshot) }
            }
            return
        }
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val cached = runCatching { repository.read(ownerUserId, simpleOneUserId) }.getOrNull()
                ?: EmployeeSnapshot(simpleOneUserId)
            mutableState.update {
                it.copy(
                    simpleOneUserId = simpleOneUserId,
                    isAuthorized = isAuthorized,
                    snapshot = cached,
                    visibleDirectory = cached.currentDirectory(),
                    query = "",
                    selectedEmployee = null,
                    errorMessage = null,
                )
            }
            if (isAuthorized && !startOffline) bootstrapAndRefresh(cached)
        }
    }

    fun updateQuery(value: String) {
        mutableState.update { state ->
            val cachedDirectory = state.snapshot.currentDirectory()
            state.copy(
                query = value,
                visibleDirectory = cachedDirectory.copy(
                    employees = filterEmployees(cachedDirectory.employees, value),
                ),
                errorMessage = null,
            )
        }
        searchJob?.cancel()
        if (!mutableState.value.isAuthorized || mutableState.value.snapshot.selectedAddress == null) return
        searchJob = viewModelScope.launch {
            if (value.isNotBlank()) delay(SEARCH_DEBOUNCE_MILLIS)
            refreshCurrent(showError = true)
        }
    }

    fun refresh() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch { refreshCurrent(showError = true) }
    }

    fun openAddressPicker() {
        mutableState.update { it.copy(isAddressPickerOpen = true, addressErrorMessage = null) }
    }

    fun closeAddressPicker() {
        addressJob?.cancel()
        mutableState.update {
            it.copy(isAddressPickerOpen = false, addressQuery = "", addressResults = emptyList())
        }
    }

    fun updateAddressQuery(value: String) {
        mutableState.update {
            it.copy(addressQuery = value, addressErrorMessage = null, addressResults = if (value.length < 2) emptyList() else it.addressResults)
        }
        addressJob?.cancel()
        if (value.trim().length < 2 || !mutableState.value.isAuthorized) return
        addressJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MILLIS)
            mutableState.update { it.copy(isSearchingAddresses = true) }
            try {
                val results = repository.searchAddresses(ownerUserId, value)
                mutableState.update { it.copy(addressResults = results, isSearchingAddresses = false) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: SimpleOneFailure.Unauthorized) {
                mutableState.update {
                    it.copy(isAuthorized = false, isSearchingAddresses = false, addressErrorMessage = "Сессия SimpleOne истекла.")
                }
            } catch (error: Exception) {
                mutableState.update {
                    it.copy(isSearchingAddresses = false, addressErrorMessage = error.userMessage("Не удалось найти населённый пункт."))
                }
            }
        }
    }

    fun selectAddress(address: EmployeeAddress) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val simpleOneUserId = mutableState.value.simpleOneUserId
            val snapshot = repository.selectAddress(ownerUserId, simpleOneUserId, address)
            mutableState.update {
                it.copy(
                    snapshot = snapshot,
                    visibleDirectory = snapshot.currentDirectory(),
                    query = "",
                    isAddressPickerOpen = false,
                    addressQuery = "",
                    addressResults = emptyList(),
                    errorMessage = null,
                )
            }
            if (!startOffline && mutableState.value.isAuthorized) refreshCurrent(showError = true)
        }
    }

    fun removeAddress(address: EmployeeAddress) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val current = mutableState.value
            val snapshot = repository.removeAddress(ownerUserId, current.simpleOneUserId, address)
            mutableState.update { it.copy(snapshot = snapshot, visibleDirectory = snapshot.currentDirectory()) }
        }
    }

    fun openEmployee(employee: Employee) {
        detailJob?.cancel()
        val cached = mutableState.value.snapshot.detailCache[employee.sysId]
        mutableState.update {
            it.copy(
                selectedEmployee = cached ?: employee,
                isDetailLoading = cached?.detailSections.isNullOrEmpty() && it.isAuthorized,
                detailErrorMessage = null,
            )
        }
        if (!mutableState.value.isAuthorized || cached?.detailSections?.isNotEmpty() == true) return
        detailJob = viewModelScope.launch {
            try {
                val current = mutableState.value
                val (snapshot, detailed) = repository.employeeDetails(
                    ownerUserId,
                    current.simpleOneUserId,
                    current.selectedEmployee ?: return@launch,
                )
                mutableState.update {
                    it.copy(snapshot = snapshot, selectedEmployee = detailed, isDetailLoading = false)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: SimpleOneFailure.Unauthorized) {
                mutableState.update {
                    it.copy(isAuthorized = false, isDetailLoading = false, detailErrorMessage = "Сессия SimpleOne истекла. Показаны сохранённые данные.")
                }
            } catch (error: Exception) {
                mutableState.update {
                    it.copy(isDetailLoading = false, detailErrorMessage = error.userMessage("Не удалось загрузить данные сотрудника."))
                }
            }
        }
    }

    fun closeEmployee() {
        detailJob?.cancel()
        mutableState.update { it.copy(selectedEmployee = null, isDetailLoading = false, detailErrorMessage = null) }
    }

    private suspend fun bootstrapAndRefresh(cached: EmployeeSnapshot) {
        mutableState.update { it.copy(isLoading = cached.selectedAddress == null, errorMessage = null) }
        try {
            val snapshot = repository.bootstrap(ownerUserId, mutableState.value.simpleOneUserId)
            mutableState.update {
                it.copy(snapshot = snapshot, visibleDirectory = snapshot.currentDirectory(), isLoading = false)
            }
            if (snapshot.selectedAddress != null) refreshCurrent(showError = cached.currentDirectory().employees.isEmpty())
            else mutableState.update { it.copy(errorMessage = "Выберите населённый пункт.") }
        } catch (error: CancellationException) {
            throw error
        } catch (error: SimpleOneFailure.Unauthorized) {
            mutableState.update { it.copy(isAuthorized = false, isLoading = false, errorMessage = "Сессия SimpleOne истекла.") }
        } catch (error: Exception) {
            mutableState.update { it.copy(isLoading = false, errorMessage = error.userMessage("Не удалось подготовить каталог сотрудников.")) }
        }
    }

    private suspend fun refreshCurrent(showError: Boolean) {
        val current = mutableState.value
        val address = current.snapshot.selectedAddress ?: return
        if (!current.isAuthorized) return
        mutableState.update { it.copy(isLoading = true, errorMessage = null) }
        try {
            val (snapshot, directory) = repository.refreshDirectory(
                ownerUserId,
                current.simpleOneUserId,
                address,
                current.query,
            )
            mutableState.update {
                it.copy(snapshot = snapshot, visibleDirectory = directory, isLoading = false, isAuthorized = true)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: SimpleOneFailure.Unauthorized) {
            mutableState.update {
                it.copy(isAuthorized = false, isLoading = false, errorMessage = if (showError) "Сессия SimpleOne истекла. Показан сохранённый каталог." else null)
            }
        } catch (error: Exception) {
            mutableState.update {
                it.copy(isLoading = false, errorMessage = if (showError) error.userMessage("Не удалось загрузить сотрудников.") else null)
            }
        }
    }

    private fun EmployeeSnapshot.currentDirectory(): EmployeeDirectory =
        selectedAddress?.sysId?.let(directoriesByAddressId::get) ?: EmployeeDirectory()

    private fun Throwable.userMessage(fallback: String): String =
        (this as? SimpleOneFailure)?.message ?: fallback

    companion object {
        private const val SEARCH_DEBOUNCE_MILLIS = 350L

        fun factory(
            repository: EmployeeRepository,
            ownerUserId: String,
            startOffline: Boolean,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                EmployeeViewModel(repository, ownerUserId, startOffline) as T
        }
    }
}
