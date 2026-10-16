package ru.lumastack.lumawork.ui.employees

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import ru.lumastack.lumawork.data.employees.*
import ru.lumastack.lumawork.data.simpleone.*
import ru.lumastack.lumawork.domain.employees.*

@OptIn(ExperimentalCoroutinesApi::class)
class EmployeeStateTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun `offline actions never request employee details or address search`() = runTest(dispatcher) {
        val api = TestEmployeeApi()
        val model = model(api, offline = true)
        model.activate("user", true)
        advanceUntilIdle()
        model.openEmployee(Employee("person"))
        model.updateAddressQuery("Алушта")
        advanceUntilIdle()
        assertEquals(0, api.detailCalls)
        assertEquals(0, api.addressCalls)
        assertFalse(model.state.value.isDetailLoading)
    }

    @Test fun `logging out clears private directory and selected employee`() = runTest(dispatcher) {
        val model = model(TestEmployeeApi(), offline = true)
        model.activate("user", true)
        advanceUntilIdle()
        model.openEmployee(Employee("person"))
        model.activate("", false)
        advanceUntilIdle()
        assertTrue(model.state.value.employees.isEmpty())
        assertNull(model.state.value.selectedEmployee)
        assertEquals("", model.state.value.simpleOneUserId)
    }

    @Test fun `clearing address search cancels spinner and discards late results`() = runTest(dispatcher) {
        val api = TestEmployeeApi().apply { addressGate = CompletableDeferred() }
        val model = model(api)
        model.activate("user", true)
        advanceUntilIdle()
        model.openAddressPicker()
        model.updateAddressQuery("Алушта")
        advanceTimeBy(400); runCurrent()
        assertTrue(model.state.value.isSearchingAddresses)
        model.updateAddressQuery("")
        api.addressGate!!.complete(Unit)
        advanceUntilIdle()
        assertFalse(model.state.value.isSearchingAddresses)
        assertTrue(model.state.value.addressResults.isEmpty())
    }

    @Test fun `search response from previous city cannot replace new selection`() = runTest(dispatcher) {
        val api = TestEmployeeApi()
        val model = model(api)
        model.activate("user", true)
        advanceUntilIdle()
        api.directoryGate = CompletableDeferred()
        model.updateQuery("Петров")
        advanceTimeBy(400); runCurrent()
        model.selectAddress(cityB)
        runCurrent()
        api.directoryGate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(cityB.sysId, model.state.value.snapshot.selectedAddress?.sysId)
        assertEquals("", model.state.value.query)
        assertEquals("person-B", model.state.value.employees.single().sysId)
    }

    @Test fun `account switch discards pending detail from previous account`() = runTest(dispatcher) {
        val api = TestEmployeeApi()
        val model = model(api)
        model.activate("user", true)
        advanceUntilIdle()
        api.detailGate = CompletableDeferred()
        model.openEmployee(Employee("person"))
        runCurrent()
        model.activate("other", false)
        runCurrent()
        api.detailGate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals("other", model.state.value.simpleOneUserId)
        assertNull(model.state.value.selectedEmployee)
        assertTrue(model.state.value.snapshot.detailCache.isEmpty())
    }

    @Test fun `concurrent detail and address writes retain both changes`() = runTest(dispatcher) {
        val cache = TestEmployeeCache().apply { yieldOnSave = true }
        val repository = EmployeeRepository(TestEmployeeApi(), employeeSession(), cache, ioDispatcher = dispatcher)
        val detail = launch { repository.employeeDetails("owner", "user", Employee("person")) }
        val address = launch { repository.selectAddress("owner", "user", cityB) }
        detail.join(); address.join()
        assertEquals(cityB.sysId, cache.value.selectedAddress?.sysId)
        assertEquals("person", cache.value.detailCache["person"]?.sysId)
    }

    @Test fun `old unauthorized response cannot clear newer session credentials`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val api = object : SimpleOneApi by TestEmployeeApi() {
            override suspend fun employeeDetails(employee: Employee, authKey: String): Employee {
                gate.await()
                throw SimpleOneFailure.Unauthorized
            }
        }
        var credentials = StoredSimpleOneSession("owner", "old-key", "engineer")
        val store = object : SimpleOneSessionStore by employeeSession() {
            override fun read(ownerUserId: String) = credentials
            override fun clear(ownerUserId: String) { credentials = credentials.copy(authKey = "") }
        }
        val repository = EmployeeRepository(api, store, TestEmployeeCache(), ioDispatcher = dispatcher)
        val response = async { runCatching { repository.employeeDetails("owner", "user", Employee("person")) } }
        runCurrent()
        credentials = credentials.copy(authKey = "new-key")
        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(response.await().exceptionOrNull() is SimpleOneFailure.Unauthorized)
        assertEquals("new-key", credentials.authKey)
    }

    @Test fun `forced refresh replaces cached employee dossier`() = runTest(dispatcher) {
        val api = TestEmployeeApi()
        val model = model(api)
        model.activate("user", true)
        advanceUntilIdle()
        model.openEmployee(Employee("person"))
        advanceUntilIdle()
        assertEquals("1", model.state.value.selectedEmployee?.updatedAt)
        model.closeEmployee()
        model.openEmployee(Employee("person"))
        advanceUntilIdle()
        assertEquals("1", model.state.value.selectedEmployee?.updatedAt)
        model.refreshEmployee()
        advanceUntilIdle()
        assertEquals("2", model.state.value.selectedEmployee?.updatedAt)
        assertEquals("2", model.state.value.snapshot.detailCache["person"]?.updatedAt)
    }

    private fun model(api: TestEmployeeApi, offline: Boolean = false) = EmployeeViewModel(
        EmployeeRepository(api, employeeSession(), TestEmployeeCache(), ioDispatcher = dispatcher), "owner", offline,
    )
}

private val cityA = EmployeeAddress("A", "Алушта")
private val cityB = EmployeeAddress("B", "Ялта")
private class TestEmployeeCache : EmployeeCache {
    var value = EmployeeSnapshot("user", cityA, listOf(cityA, cityB), mapOf("A" to EmployeeDirectory(listOf(Employee("cached")), 1)))
    var yieldOnSave = false
    override suspend fun read(ownerUserId: String) = value
    override suspend fun save(ownerUserId: String, snapshot: EmployeeSnapshot) { if (yieldOnSave) yield(); value = snapshot }
}
private class TestEmployeeApi : SimpleOneApi by OkHttpSimpleOneApi("https://simpleone.test") {
    var detailCalls = 0
    var addressCalls = 0
    var detailGate: CompletableDeferred<Unit>? = null
    var addressGate: CompletableDeferred<Unit>? = null
    var directoryGate: CompletableDeferred<Unit>? = null
    override suspend fun employeeDetails(employee: Employee, authKey: String): Employee {
        detailCalls++
        detailGate?.let { withContext(NonCancellable) { it.await() } }
        return employee.copy(updatedAt = detailCalls.toString(), detailSections = listOf(EmployeeSection("main", "Основное", listOf(EmployeeField("username", "Логин", "test")))))
    }
    override suspend fun employeeAddresses(query: String, authKey: String): List<EmployeeAddress> {
        addressCalls++
        addressGate?.let { withContext(NonCancellable) { it.await() } }
        return listOf(cityA)
    }
    override suspend fun employees(address: EmployeeAddress, query: String, authKey: String): Pair<List<Employee>, Int> {
        if (query.isNotBlank()) directoryGate?.let { withContext(NonCancellable) { it.await() } }
        return listOf(Employee("person-${address.sysId}")) to 1
    }
}
private fun employeeSession() = object : SimpleOneSessionStore {
    override fun read(ownerUserId: String) = StoredSimpleOneSession(ownerUserId, "test-auth", "engineer")
    override fun save(session: StoredSimpleOneSession) = Unit
    override fun clear(ownerUserId: String) = Unit
    override fun readUsername(ownerUserId: String) = "engineer"
    override fun saveUsername(ownerUserId: String, username: String) = Unit
}
