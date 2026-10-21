package ru.lumastack.lumawork.data.backpack

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceUntilIdle
import ru.lumastack.lumawork.domain.backpack.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import okhttp3.ResponseBody.Companion.toResponseBody
import ru.lumastack.lumawork.data.simpleone.SimpleOneApi
import ru.lumastack.lumawork.data.simpleone.SimpleOneSessionStore
import ru.lumastack.lumawork.data.simpleone.StoredSimpleOneSession
import ru.lumastack.lumawork.domain.backpack.BackpackItem
import ru.lumastack.lumawork.domain.backpack.BackpackSnapshot
import ru.lumastack.lumawork.domain.coordination.CoordinationRegion
import ru.lumastack.lumawork.domain.simpleone.SimpleOneRequest
import ru.lumastack.lumawork.domain.simpleone.SimpleOneUser
import ru.lumastack.lumawork.domain.timereport.TimeReportEntry

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class BackpackRepositoryTest {
    private val models = mutableListOf<ru.lumastack.lumawork.ui.backpack.BackpackViewModel>()
    private suspend fun closeModels() {
        models.forEach { it.viewModelScope.coroutineContext[Job]?.cancelAndJoin() }
        models.clear()
    }
    @Test fun `offline mode never starts list refresh`() = runTest {
        kotlinx.coroutines.Dispatchers.setMain(kotlinx.coroutines.test.StandardTestDispatcher(testScheduler))
        try {
            val api = FakeBackpackApi(listOf(item("1")))
            val model = ru.lumastack.lumawork.ui.backpack.BackpackViewModel(BackpackRepository(api, authorizedStore(), FakeBackpackCache()), testPhotos(), "owner", true).also(models::add)
            model.activate("simple-user", true)
            advanceUntilIdle()
            model.refresh()
            advanceUntilIdle()
            assertEquals(0, api.listCalls)
        } finally { closeModels(); kotlinx.coroutines.Dispatchers.resetMain() }
    }

    @Test fun `refresh cannot revert a location edited while cache save was suspended`() = runTest {
        kotlinx.coroutines.Dispatchers.setMain(kotlinx.coroutines.test.StandardTestDispatcher(testScheduler))
        try {
            val cache = FakeBackpackCache().apply { value = BackpackSnapshot("simple-user", listOf(item("1"))); saveGate = CompletableDeferred() }
            val model = ru.lumastack.lumawork.ui.backpack.BackpackViewModel(BackpackRepository(FakeBackpackApi(listOf(item("1"))), authorizedStore(), cache), testPhotos(), "owner", false).also(models::add)
            model.activate("simple-user", true)
            runCurrent()
            model.setLocation(item("1"), BackpackLocation.Absent)
            runCurrent()
            cache.saveGate!!.complete(Unit)
            advanceUntilIdle()
            assertEquals(BackpackLocation.Absent, model.state.value.snapshot!!.location(item("1")))
        } finally { closeModels(); kotlinx.coroutines.Dispatchers.resetMain() }
    }

    @Test fun `reauthorization refreshes an existing cached inventory`() = runTest {
        kotlinx.coroutines.Dispatchers.setMain(kotlinx.coroutines.test.StandardTestDispatcher(testScheduler))
        try {
            val cache = FakeBackpackCache().apply { value = BackpackSnapshot("simple-user", listOf(item("old"))) }
            val api = FakeBackpackApi(listOf(item("new")))
            val model = ru.lumastack.lumawork.ui.backpack.BackpackViewModel(BackpackRepository(api, authorizedStore(), cache), testPhotos(), "owner", false).also(models::add)
            model.activate("simple-user", false)
            advanceUntilIdle()
            model.activate("simple-user", true)
            advanceUntilIdle()
            assertEquals(1, api.listCalls)
            assertEquals("new", model.state.value.snapshot!!.items.single().id)
        } finally { closeModels(); kotlinx.coroutines.Dispatchers.resetMain() }
    }

    @Test fun `reauthorization during cache restore still refreshes inventory`() = runTest {
        kotlinx.coroutines.Dispatchers.setMain(kotlinx.coroutines.test.StandardTestDispatcher(testScheduler))
        try {
            val cache = FakeBackpackCache().apply { value = BackpackSnapshot("simple-user", listOf(item("old"))); readGate = CompletableDeferred() }
            val api = FakeBackpackApi(listOf(item("new")))
            val model = ru.lumastack.lumawork.ui.backpack.BackpackViewModel(BackpackRepository(api, authorizedStore(), cache), testPhotos(), "owner", false).also(models::add)
            model.activate("simple-user", false)
            runCurrent()
            model.activate("simple-user", true)
            cache.readGate!!.complete(Unit)
            advanceUntilIdle()
            assertEquals(1, api.listCalls)
            assertEquals("new", model.state.value.snapshot!!.items.single().id)
        } finally { closeModels(); kotlinx.coroutines.Dispatchers.resetMain() }
    }

    @Test fun `authorization loss cancels pending refresh and keeps cached inventory`() = runTest {
        kotlinx.coroutines.Dispatchers.setMain(kotlinx.coroutines.test.StandardTestDispatcher(testScheduler))
        try {
            val cache = FakeBackpackCache().apply { value = BackpackSnapshot("simple-user", listOf(item("old"))) }
            val api = FakeBackpackApi(listOf(item("new"))).apply { listGate = CompletableDeferred() }
            val model = ru.lumastack.lumawork.ui.backpack.BackpackViewModel(BackpackRepository(api, authorizedStore(), cache), testPhotos(), "owner", false).also(models::add)
            model.activate("simple-user", true)
            runCurrent()
            model.activate("simple-user", false)
            api.listGate!!.complete(Unit)
            advanceUntilIdle()
            assertEquals(false, model.state.value.isLoading)
            assertEquals(false, model.state.value.isAuthorized)
            assertEquals("old", model.state.value.snapshot!!.items.single().id)
        } finally { closeModels(); kotlinx.coroutines.Dispatchers.resetMain() }
    }

    @Test fun `accepted scan clears location filter without changing serial text`() = runTest {
        kotlinx.coroutines.Dispatchers.setMain(kotlinx.coroutines.test.StandardTestDispatcher(testScheduler))
        try {
            val model = ru.lumastack.lumawork.ui.backpack.BackpackViewModel(BackpackRepository(FakeBackpackApi(emptyList()), authorizedStore(), FakeBackpackCache()), testPhotos(), "owner", true).also(models::add)
            model.selectLocation(BackpackLocation.Home)
            model.openGroup("model:pax")
            model.applyScan("  000AbC-123/45  ")
            assertEquals("000AbC-123/45", model.state.value.query)
            assertNull(model.state.value.locationFilter)
            assertNull(model.state.value.selectedGroupId)
            model.applyScan("—")
            assertEquals("000AbC-123/45", model.state.value.query)
            advanceUntilIdle()
        } finally { closeModels(); kotlinx.coroutines.Dispatchers.resetMain() }
    }

    @Test fun `list is published before details and concurrent location edits survive hydration`() = runTest {
        val cache = FakeBackpackCache()
        val api = FakeBackpackApi(listOf(item("1")))
        api.detailGate = CompletableDeferred()
        val repository = BackpackRepository(api, authorizedStore(), cache, { 1234 })
        val listed = repository.refresh("owner", "simple-user")
        assertEquals(0, api.detailCalls)
        val progress = mutableListOf<BackpackSnapshot>()
        val job = launch { repository.hydrate("owner", "simple-user", listed, force = true) { snapshot, _ -> progress += snapshot } }
        runCurrent()
        assertEquals(1, api.detailCalls)
        repository.updateLocation("owner", "simple-user", listed.items.single(), BackpackLocation.Absent)
        api.detailGate!!.complete(Unit)
        advanceUntilIdle()
        job.join()
        assertEquals("Догруженная дата", cache.value!!.items.single().receivedAt)
        assertEquals(BackpackLocation.Absent, cache.value!!.location(cache.value!!.items.single()))
        assertEquals("Догруженная дата", progress.last().items.single().receivedAt)
    }

    @Test fun `refresh preserves same user location and return classification`() = runBlocking {
        val cache = FakeBackpackCache()
        cache.value = BackpackSnapshot("simple-user", listOf(item("1")), locationOverrides = mapOf("SN" to BackpackLocation.Home), returnSerialNumbers = setOf("sn"))
        val repository = BackpackRepository(FakeBackpackApi(listOf(item("1"))), authorizedStore(), cache)
        val fresh = repository.refresh("owner", "simple-user")
        assertEquals(BackpackLocation.Home, fresh.location(fresh.items.single()))
        assertEquals(setOf("sn"), fresh.returnSerialNumbers)
    }

    @Test
    fun `refresh atomically replaces user scoped snapshot`() = runBlocking {
        val cache = FakeBackpackCache()
        val repository = BackpackRepository(
            api = FakeBackpackApi(listOf(item("new"))),
            sessionStore = authorizedStore(),
            cache = cache,
            nowEpochMillis = { 1234L },
        )

        val snapshot = repository.refresh("owner", "simple-user")

        assertEquals("new", snapshot.items.single().id)
        assertEquals(1234L, snapshot.updatedAtEpochMillis)
        assertEquals(snapshot, repository.read("owner", "simple-user"))
        assertNull(repository.read("owner", "other-user"))
    }
}

private class FakeBackpackCache : BackpackCache {
    var value: BackpackSnapshot? = null
    var saveGate: CompletableDeferred<Unit>? = null
    var readGate: CompletableDeferred<Unit>? = null
    override suspend fun read(ownerUserId: String): BackpackSnapshot? { readGate?.await(); return value }
    override suspend fun save(ownerUserId: String, snapshot: BackpackSnapshot) {
        saveGate?.await()
        value = snapshot
    }
}

private class FakeBackpackApi(private val items: List<BackpackItem>) : SimpleOneApi {
    var detailGate: CompletableDeferred<Unit>? = null
    var detailCalls = 0
    var listCalls = 0
    var listGate: CompletableDeferred<Unit>? = null
    override suspend fun backpackItemDetails(item: BackpackItem, authKey: String): BackpackItem {
        detailCalls++
        detailGate?.await()
        return item.copy(receivedAt = "Догруженная дата")
    }
    override suspend fun login(username: String, password: String): String = "auth"
    override suspend fun currentUser(authKey: String): SimpleOneUser = SimpleOneUser("simple-user", "user", "", "")
    override suspend fun activeRequests(userId: String, authKey: String): List<SimpleOneRequest> = emptyList()
    override suspend fun closedRequests(userId: String, authKey: String): List<SimpleOneRequest> = emptyList()
    override suspend fun exportClosedRequests(userId: String, authKey: String): ByteArray = byteArrayOf()
    override suspend fun timeReportEntries(authKey: String): List<TimeReportEntry> = emptyList()
    override suspend fun exportTimeReport(userId: String, authKey: String): ByteArray = byteArrayOf()
    override suspend fun backpackItems(authKey: String): List<BackpackItem> { listCalls++; listGate?.await(); return items }
    override suspend fun requestDetails(request: SimpleOneRequest, authKey: String): SimpleOneRequest = request
    override suspend fun coordinationRequests(
        region: CoordinationRegion,
        authKey: String,
    ): List<SimpleOneRequest> = emptyList()
    override suspend fun returnEquipmentRequests(
        userId: String,
        authKey: String,
    ): List<SimpleOneRequest> = emptyList()
}

private fun authorizedStore(): SimpleOneSessionStore = object : SimpleOneSessionStore {
    private var value: StoredSimpleOneSession? = StoredSimpleOneSession("owner", "auth", "engineer")
    override fun read(ownerUserId: String): StoredSimpleOneSession? = value?.takeIf { it.ownerUserId == ownerUserId }
    override fun save(session: StoredSimpleOneSession) {
        value = session
    }
    override fun clear(ownerUserId: String) {
        if (value?.ownerUserId == ownerUserId) value = null
    }
    override fun readUsername(ownerUserId: String): String = "engineer"
    override fun saveUsername(ownerUserId: String, username: String) = Unit
}

private fun item(id: String) = BackpackItem(id, "Pax D200", "SN", "Инженер", "12.08.2026", 1L, 1)

private fun testPhotos() = BackpackPhotoRepository("https://photo.test", object : BackpackPhotoCatalogCache {
    override suspend fun read() = BackpackPhotoCatalog()
    override suspend fun save(catalog: BackpackPhotoCatalog) = Unit
}, equipmentCache = object : BackpackPhotoCatalogCache {
    override suspend fun read() = BackpackPhotoCatalog()
    override suspend fun save(catalog: BackpackPhotoCatalog) = Unit
}, client = okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
    okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("OK")
        .body("{\"items\":[]}".toResponseBody()).build()
}.build())
