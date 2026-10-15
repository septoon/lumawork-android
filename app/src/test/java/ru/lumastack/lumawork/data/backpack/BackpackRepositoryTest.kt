package ru.lumastack.lumawork.data.backpack

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.lumastack.lumawork.data.simpleone.SimpleOneApi
import ru.lumastack.lumawork.data.simpleone.SimpleOneSessionStore
import ru.lumastack.lumawork.data.simpleone.StoredSimpleOneSession
import ru.lumastack.lumawork.domain.backpack.BackpackItem
import ru.lumastack.lumawork.domain.backpack.BackpackSnapshot
import ru.lumastack.lumawork.domain.coordination.CoordinationRegion
import ru.lumastack.lumawork.domain.simpleone.SimpleOneRequest
import ru.lumastack.lumawork.domain.simpleone.SimpleOneUser
import ru.lumastack.lumawork.domain.timereport.TimeReportEntry

class BackpackRepositoryTest {
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
    override suspend fun read(ownerUserId: String): BackpackSnapshot? = value
    override suspend fun save(ownerUserId: String, snapshot: BackpackSnapshot) {
        value = snapshot
    }
}

private class FakeBackpackApi(private val items: List<BackpackItem>) : SimpleOneApi {
    override suspend fun login(username: String, password: String): String = "auth"
    override suspend fun currentUser(authKey: String): SimpleOneUser = SimpleOneUser("simple-user", "user", "", "")
    override suspend fun activeRequests(userId: String, authKey: String): List<SimpleOneRequest> = emptyList()
    override suspend fun closedRequests(userId: String, authKey: String): List<SimpleOneRequest> = emptyList()
    override suspend fun exportClosedRequests(userId: String, authKey: String): ByteArray = byteArrayOf()
    override suspend fun timeReportEntries(authKey: String): List<TimeReportEntry> = emptyList()
    override suspend fun exportTimeReport(userId: String, authKey: String): ByteArray = byteArrayOf()
    override suspend fun backpackItems(authKey: String): List<BackpackItem> = items
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
