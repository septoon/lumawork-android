package ru.lumastack.lumawork.data.employees

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.lumastack.lumawork.data.simpleone.SimpleOneApi
import ru.lumastack.lumawork.data.simpleone.SimpleOneFailure
import ru.lumastack.lumawork.data.simpleone.SimpleOneSessionStore
import ru.lumastack.lumawork.domain.employees.Employee
import ru.lumastack.lumawork.domain.employees.EmployeeAddress
import ru.lumastack.lumawork.domain.employees.EmployeeDirectory
import ru.lumastack.lumawork.domain.employees.EmployeeSnapshot

class EmployeeRepository(
    private val api: SimpleOneApi,
    private val sessionStore: SimpleOneSessionStore,
    private val cache: EmployeeCache,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val cacheMutex = Mutex()

    suspend fun read(ownerUserId: String, simpleOneUserId: String): EmployeeSnapshot? =
        cache.read(ownerUserId)?.takeIf { it.simpleOneUserId == simpleOneUserId }

    suspend fun bootstrap(ownerUserId: String, simpleOneUserId: String): EmployeeSnapshot {
        val previous = read(ownerUserId, simpleOneUserId) ?: EmployeeSnapshot(simpleOneUserId)
        if (previous.selectedAddress != null) return previous
        val authKey = authKey(ownerUserId)
        return authorized(ownerUserId, authKey) {
            val currentEmployee = api.employeeDetails(Employee(sysId = simpleOneUserId), authKey)
            val address = currentEmployee.address ?: return@authorized previous
            update(ownerUserId, simpleOneUserId) { current ->
                if (current.selectedAddress != null) current else current.copy(
                    selectedAddress = address,
                    savedAddresses = current.savedAddresses.upsert(address),
                    detailCache = current.detailCache + (currentEmployee.sysId to currentEmployee),
                )
            }
        }
    }

    suspend fun refreshDirectory(
        ownerUserId: String,
        simpleOneUserId: String,
        address: EmployeeAddress,
        query: String,
    ): Pair<EmployeeSnapshot, EmployeeDirectory> {
        val authKey = authKey(ownerUserId)
        return authorized(ownerUserId, authKey) {
            val (employees, totalCount) = api.employees(address, query, authKey)
            val directory = EmployeeDirectory(employees, totalCount, nowEpochMillis())
            currentCoroutineContext().ensureActive()
            val snapshot = update(ownerUserId, simpleOneUserId) { previous ->
                if (query.isNotBlank() || previous.selectedAddress?.sysId != address.sysId) previous
                else previous.copy(directoriesByAddressId = previous.directoriesByAddressId + (address.sysId to directory))
            }
            snapshot to directory
        }
    }

    suspend fun searchAddresses(ownerUserId: String, query: String): List<EmployeeAddress> {
        val authKey = authKey(ownerUserId)
        return authorized(ownerUserId, authKey) { api.employeeAddresses(query, authKey) }
    }

    suspend fun selectAddress(
        ownerUserId: String,
        simpleOneUserId: String,
        address: EmployeeAddress,
    ): EmployeeSnapshot {
        return update(ownerUserId, simpleOneUserId) { previous -> previous.copy(
            selectedAddress = address,
            savedAddresses = previous.savedAddresses.upsert(address),
        ) }
    }

    suspend fun removeAddress(ownerUserId: String, simpleOneUserId: String, address: EmployeeAddress): EmployeeSnapshot =
        update(ownerUserId, simpleOneUserId) { previous ->
            val saved = previous.savedAddresses.filterNot { it.sysId == address.sysId }
            previous.copy(
                selectedAddress = if (previous.selectedAddress?.sysId == address.sysId) saved.firstOrNull() else previous.selectedAddress,
                savedAddresses = saved,
                directoriesByAddressId = previous.directoriesByAddressId - address.sysId,
            )
        }

    suspend fun employeeDetails(
        ownerUserId: String,
        simpleOneUserId: String,
        employee: Employee,
    ): Pair<EmployeeSnapshot, Employee> {
        val authKey = authKey(ownerUserId)
        return authorized(ownerUserId, authKey) {
            val detailed = api.employeeDetails(employee, authKey)
            val snapshot = update(ownerUserId, simpleOneUserId) { previous ->
                val directories = previous.directoriesByAddressId.mapValues { (_, directory) ->
                    directory.copy(employees = directory.employees.map { if (it.sysId == detailed.sysId) detailed else it })
                }
                previous.copy(directoriesByAddressId = directories, detailCache = previous.detailCache + (detailed.sysId to detailed))
            }
            snapshot to detailed
        }
    }

    private suspend fun update(owner: String, user: String, transform: (EmployeeSnapshot) -> EmployeeSnapshot): EmployeeSnapshot = cacheMutex.withLock {
        currentCoroutineContext().ensureActive()
        val previous = read(owner, user) ?: EmployeeSnapshot(user)
        transform(previous).also { if (it != previous) cache.save(owner, it) }
    }

    private suspend fun authKey(ownerUserId: String): String = withContext(ioDispatcher) {
        sessionStore.read(ownerUserId)?.authKey
    } ?: throw SimpleOneFailure.MissingCredentials

    private suspend fun <Value> authorized(ownerUserId: String, authKey: String, block: suspend () -> Value): Value = try {
        withContext(ioDispatcher) { block() }
    } catch (error: CancellationException) {
        throw error
    } catch (error: SimpleOneFailure.Unauthorized) {
        withContext(ioDispatcher) { if (sessionStore.read(ownerUserId)?.authKey == authKey) sessionStore.clear(ownerUserId) }
        throw error
    }

    private fun List<EmployeeAddress>.upsert(address: EmployeeAddress): List<EmployeeAddress> =
        filterNot { it.sysId == address.sysId } + address
}
