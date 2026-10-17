package ru.lumastack.lumawork.ui.profile

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import ru.lumastack.lumawork.data.auth.*
import ru.lumastack.lumawork.data.auto.*
import ru.lumastack.lumawork.data.session.*
import ru.lumastack.lumawork.domain.auto.*
import ru.lumastack.lumawork.domain.model.*

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileStateTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { Dispatchers.resetMain() }

    @Test fun `offline editing never sends profile or avatar mutations`() = runTest(dispatcher) {
        val fixture = Fixture()
        fixture.model.open(fixture.session, true)
        advanceUntilIdle()
        fixture.model.edit(UserProfile(firstName = "Имя"))
        fixture.model.save(); advanceUntilIdle()
        fixture.model.uploadAvatar { byteArrayOf(1) }; advanceUntilIdle()
        fixture.model.deleteAvatar(); advanceUntilIdle()
        assertEquals(0, fixture.authApi.calls)
        assertNotNull(fixture.model.state.value.error)
    }

    @Test fun `legacy profile vehicle populates editor when typed cars are absent`() = runTest(dispatcher) {
        val profile = UserProfile(vehicleModel = "Лада Веста", vehiclePlate = "А123ВС 82", engineVolumeCm3 = "1 600 см³", initialMileageKm = "100 000 км")
        val fixture = Fixture(profile)
        fixture.model.open(fixture.session, true); advanceUntilIdle()
        val draft = fixture.model.state.value.vehicle
        assertEquals("Лада Веста", draft.model)
        assertEquals("А123ВС 82", draft.licensePlate)
        assertEquals("1600", draft.engineVolumeCm3)
        assertEquals("100000", draft.currentMileageKm)
    }

    @Test fun `failed vehicle load cannot turn editing into creating a duplicate`() = runTest(dispatcher) {
        val fixture = Fixture().apply { autoApi.failLoad = true; cache.hasCache = false }
        fixture.model.open(fixture.session, false); advanceUntilIdle()
        fixture.model.editVehicle(VehicleDraft(licensePlate = "А123ВС 82", make = "Лада", model = "Веста"))
        fixture.model.save(); advanceUntilIdle()
        assertEquals(0, fixture.autoApi.writes)
    }

    @Test fun `closing cancels pending avatar preparation before server upload`() = runTest(dispatcher) {
        val fixture = Fixture()
        fixture.model.open(fixture.session, true); advanceUntilIdle()
        // Change to online without discarding the draft.
        fixture.model.open(fixture.session, false); advanceUntilIdle()
        val gate = CompletableDeferred<Unit>()
        fixture.model.uploadAvatar { gate.await(); byteArrayOf(1) }
        runCurrent(); fixture.model.close(); gate.complete(Unit); advanceUntilIdle()
        assertEquals(0, fixture.authApi.calls)
        assertFalse(fixture.model.state.value.isOpen)
    }

    @Test fun `blank vehicle does not prevent saving optional profile fields`() = runTest(dispatcher) {
        val fixture = Fixture()
        fixture.model.open(fixture.session, false); advanceUntilIdle()
        fixture.model.editVehicle(VehicleDraft())
        fixture.model.edit(UserProfile(firstName = "Имя", workEmail = "  Name@Company.test  "))
        fixture.model.save(); advanceUntilIdle()
        assertTrue(fixture.model.state.value.saved)
        assertEquals("Name@Company.test", fixture.auth.session?.user?.profile?.workEmail)
        assertEquals(0, fixture.autoApi.writes)
    }

    @Test fun `profile does not require make and model when updating vehicle documents`() = runTest(dispatcher) {
        val fixture = Fixture().apply {
            val car = Vehicle("v1", sts = "old", isPrimary = true)
            cache.value = AutoSnapshot(vehicles = listOf(car))
            autoApi.vehiclesResult = listOf(car)
        }
        fixture.model.open(fixture.session, true); advanceUntilIdle()
        fixture.model.open(fixture.session, false); advanceUntilIdle()
        fixture.model.editVehicle(fixture.model.state.value.vehicle.copy(sts = "new"))
        fixture.model.save(); advanceUntilIdle()
        assertEquals(1, fixture.autoApi.writes)
        assertTrue(fixture.model.state.value.saved)
    }
}

private class Fixture(profile: UserProfile = UserProfile()) {
    val session = AppSession("test-token", AppUser("owner", "test@example.test", "user", profile = profile))
    val authApi = ProfileAuthApi(session.user)
    val auth = AuthRepository(authApi, object : SessionStore {
        var value: AppSession? = session
        override fun read() = value
        override fun save(session: AppSession) { value = session }
        override fun clear() { value = null }
    }, object : RememberedEmailStore { override fun read() = ""; override fun save(email: String) {} })
    val autoApi = ProfileAutoApi()
    val cache = ProfileAutoCache()
    val model = ProfileViewModel(auth, AutoRepository(autoApi, cache))
}
private class ProfileAuthApi(var user: AppUser) : AuthApi by OkHttpAuthApi("https://profile.test") {
    var calls = 0
    override suspend fun updateProfile(token: String, profile: UserProfile): AppUser { calls++; return user.copy(profile = profile).also { user = it } }
    override suspend fun uploadAvatar(token: String, bytes: ByteArray): AppUser { calls++; return user }
    override suspend fun deleteAvatar(token: String): AppUser { calls++; return user }
}
private class ProfileAutoCache : AutoCache {
    var value = AutoSnapshot()
    var hasCache = true
    override suspend fun read(ownerUserId: String) = value.takeIf { hasCache }
    override suspend fun save(ownerUserId: String, snapshot: AutoSnapshot) { value = snapshot }
}
private class ProfileAutoApi : AutoApi by OkHttpAutoApi("https://auto.test", Json { ignoreUnknownKeys = true }) {
    var failLoad = false
    var vehiclesResult = emptyList<Vehicle>()
    var writes = 0
    override suspend fun vehicles(token: String): List<Vehicle> { if (failLoad) error("offline"); return vehiclesResult }
    override suspend fun maintenance(token: String) = emptyList<MaintenanceRecord>()
    override suspend fun createVehicle(token: String, draft: VehicleDraft): Vehicle { writes++; return Vehicle("created", isPrimary = true) }
    override suspend fun updateVehicle(token: String, vehicleId: String, draft: VehicleDraft): Vehicle { writes++; return Vehicle(vehicleId, isPrimary = true) }
}
