package ru.lumastack.lumawork.data.employees

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
) {
    suspend fun read(ownerUserId: String, simpleOneUserId: String): EmployeeSnapshot? =
        cache.read(ownerUserId)?.takeIf { it.simpleOneUserId == simpleOneUserId }

    suspend fun bootstrap(ownerUserId: String, simpleOneUserId: String): EmployeeSnapshot {
        val previous = read(ownerUserId, simpleOneUserId) ?: EmployeeSnapshot(simpleOneUserId)
        if (previous.selectedAddress != null) return previous
        val authKey = authKey(ownerUserId)
        return authorized(ownerUserId) {
            val currentEmployee = api.employeeDetails(Employee(sysId = simpleOneUserId), authKey)
            val address = currentEmployee.address ?: return@authorized previous
            previous.copy(
                selectedAddress = address,
                savedAddresses = listOf(address),
                detailCache = previous.detailCache + (currentEmployee.sysId to currentEmployee),
            ).also { cache.save(ownerUserId, it) }
        }
    }

    suspend fun refreshDirectory(
        ownerUserId: String,
        simpleOneUserId: String,
        address: EmployeeAddress,
        query: String,
    ): Pair<EmployeeSnapshot, EmployeeDirectory> {
        val authKey = authKey(ownerUserId)
        return authorized(ownerUserId) {
            val (employees, totalCount) = api.employees(address, query, authKey)
            val directory = EmployeeDirectory(employees, totalCount, nowEpochMillis())
            val previous = read(ownerUserId, simpleOneUserId) ?: EmployeeSnapshot(simpleOneUserId)
            val snapshot = if (query.isBlank()) {
                previous.copy(
                    selectedAddress = address,
                    savedAddresses = previous.savedAddresses.upsert(address),
                    directoriesByAddressId = previous.directoriesByAddressId + (address.sysId to directory),
                ).also { cache.save(ownerUserId, it) }
            } else {
                previous.copy(selectedAddress = address, savedAddresses = previous.savedAddresses.upsert(address))
            }
            snapshot to directory
        }
    }

    suspend fun searchAddresses(ownerUserId: String, query: String): List<EmployeeAddress> {
        val authKey = authKey(ownerUserId)
        return authorized(ownerUserId) { api.employeeAddresses(query, authKey) }
    }

    suspend fun selectAddress(
        ownerUserId: String,
        simpleOneUserId: String,
        address: EmployeeAddress,
    ): EmployeeSnapshot {
        val previous = read(ownerUserId, simpleOneUserId) ?: EmployeeSnapshot(simpleOneUserId)
        return previous.copy(
            selectedAddress = address,
            savedAddresses = previous.savedAddresses.upsert(address),
        ).also { cache.save(ownerUserId, it) }
    }

    suspend fun removeAddress(
        ownerUserId: String,
        simpleOneUserId: String,
        address: EmployeeAddress,
    ): EmployeeSnapshot {
        val previous = read(ownerUserId, simpleOneUserId) ?: EmployeeSnapshot(simpleOneUserId)
        val saved = previous.savedAddresses.filterNot { it.sysId == address.sysId }
        return previous.copy(
            selectedAddress = if (previous.selectedAddress?.sysId == address.sysId) saved.firstOrNull()
                else previous.selectedAddress,
            savedAddresses = saved,
            directoriesByAddressId = previous.directoriesByAddressId - address.sysId,
        ).also { cache.save(ownerUserId, it) }
    }

    suspend fun employeeDetails(
        ownerUserId: String,
        simpleOneUserId: String,
        employee: Employee,
    ): Pair<EmployeeSnapshot, Employee> {
        val authKey = authKey(ownerUserId)
        return authorized(ownerUserId) {
            val detailed = api.employeeDetails(employee, authKey)
            val previous = read(ownerUserId, simpleOneUserId) ?: EmployeeSnapshot(simpleOneUserId)
            val directories = previous.directoriesByAddressId.mapValues { (_, directory) ->
                directory.copy(employees = directory.employees.map { if (it.sysId == detailed.sysId) detailed else it })
            }
            val snapshot = previous.copy(
                directoriesByAddressId = directories,
                detailCache = previous.detailCache + (detailed.sysId to detailed),
            ).also { cache.save(ownerUserId, it) }
            snapshot to detailed
        }
    }

    private suspend fun authKey(ownerUserId: String): String = withContext(Dispatchers.IO) {
        sessionStore.read(ownerUserId)?.authKey
    } ?: throw SimpleOneFailure.MissingCredentials

    private suspend fun <Value> authorized(ownerUserId: String, block: suspend () -> Value): Value = try {
        block()
    } catch (error: CancellationException) {
        throw error
    } catch (error: SimpleOneFailure.Unauthorized) {
        withContext(Dispatchers.IO) { sessionStore.clear(ownerUserId) }
        throw error
    }

    private fun List<EmployeeAddress>.upsert(address: EmployeeAddress): List<EmployeeAddress> =
        filterNot { it.sysId == address.sysId } + address
}
