package ru.lumastack.lumawork.data.coordination

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.lumastack.lumawork.data.simpleone.SimpleOneApi
import ru.lumastack.lumawork.data.simpleone.SimpleOneFailure
import ru.lumastack.lumawork.data.simpleone.SimpleOneSessionStore
import ru.lumastack.lumawork.data.simpleone.StoredSimpleOneSession
import ru.lumastack.lumawork.domain.coordination.CoordinationRegion
import ru.lumastack.lumawork.domain.coordination.CoordinationSnapshot
import ru.lumastack.lumawork.domain.simpleone.SimpleOneRequest
import ru.lumastack.lumawork.domain.simpleone.SimpleOneUser
import ru.lumastack.lumawork.domain.timereport.TimeReportEntry
import ru.lumastack.lumawork.domain.backpack.BackpackItem

class CoordinationRepositoryTest {
    @Test
    fun `region refresh is scoped by LumaWork and SimpleOne users`() = runBlocking {
        val session = FakeSessionStore().apply {
            save(StoredSimpleOneSession("owner-1", "auth", "engineer"))
        }
        val cache = FakeCoordinationCache()
        val repository = CoordinationRepository(FakeCoordinationApi(), session, cache) { 1234L }

        val snapshot = repository.refreshRegion("owner-1", "simple-1", CoordinationRegion.Alushta)

        assertEquals("simple-1", snapshot.simpleOneUserId)
        assertEquals("REQ1", snapshot.regions[CoordinationRegion.Alushta.name]?.requests?.single()?.number)
        assertEquals(1234L, snapshot.regions[CoordinationRegion.Alushta.name]?.updatedAtEpochMillis)
        assertNull(repository.read("owner-1", "other-simple-user"))
        assertNull(cache.values["owner-2"])
    }

    @Test
    fun `refresh preserves cached detail fields missing from list payload`() = runBlocking {
        val session = FakeSessionStore().apply {
            save(StoredSimpleOneSession("owner-1", "auth", "engineer"))
        }
        val cache = FakeCoordinationCache().apply {
            values["owner-1"] = CoordinationSnapshot(
                simpleOneUserId = "simple-1",
                regions = mapOf(
                    CoordinationRegion.Alushta.name to ru.lumastack.lumawork.domain.coordination.CoordinationRequestGroup(
                        requests = listOf(request().copy(waitingReason = "Ждём клиента", resolution = "Сохранено")),
                    ),
                ),
            )
        }
        val repository = CoordinationRepository(FakeCoordinationApi(), session, cache)

        val snapshot = repository.refreshRegion("owner-1", "simple-1", CoordinationRegion.Alushta)

        val refreshed = snapshot.regions[CoordinationRegion.Alushta.name]!!.requests.single()
        assertEquals("Ждём клиента", refreshed.waitingReason)
        assertEquals("Сохранено", refreshed.resolution)
    }

    @Test
    fun `unauthorized refresh clears only current SimpleOne key`() = runBlocking {
        val session = FakeSessionStore().apply {
            save(StoredSimpleOneSession("owner-1", "expired", "engineer"))
        }
        val repository = CoordinationRepository(
            FakeCoordinationApi(error = SimpleOneFailure.Unauthorized),
            session,
            FakeCoordinationCache(),
        )

        runCatching { repository.refreshRegion("owner-1", "simple-1", CoordinationRegion.Alushta) }

        assertNull(session.read("owner-1"))
    }
}

private class FakeCoordinationApi(
    private val error: SimpleOneFailure? = null,
) : SimpleOneApi {
    override suspend fun login(username: String, password: String): String = "auth"
    override suspend fun currentUser(authKey: String): SimpleOneUser = SimpleOneUser("simple-1", "", "", "")
    override suspend fun activeRequests(userId: String, authKey: String): List<SimpleOneRequest> = emptyList()
    override suspend fun closedRequests(userId: String, authKey: String): List<SimpleOneRequest> = emptyList()
    override suspend fun exportClosedRequests(userId: String, authKey: String): ByteArray = byteArrayOf()
    override suspend fun timeReportEntries(authKey: String): List<TimeReportEntry> = emptyList()
    override suspend fun exportTimeReport(userId: String, authKey: String): ByteArray = byteArrayOf()
    override suspend fun backpackItems(authKey: String): List<BackpackItem> = emptyList()
    override suspend fun requestDetails(request: SimpleOneRequest, authKey: String): SimpleOneRequest = request
    override suspend fun coordinationRequests(
        region: CoordinationRegion,
        authKey: String,
    ): List<SimpleOneRequest> {
        error?.let { throw it }
        return listOf(request())
    }
    override suspend fun returnEquipmentRequests(
        userId: String,
        authKey: String,
    ): List<SimpleOneRequest> = emptyList()
}

private class FakeSessionStore : SimpleOneSessionStore {
    private var session: StoredSimpleOneSession? = null
    override fun read(ownerUserId: String): StoredSimpleOneSession? = session?.takeIf { it.ownerUserId == ownerUserId }
    override fun save(session: StoredSimpleOneSession) { this.session = session }
    override fun clear(ownerUserId: String) { if (session?.ownerUserId == ownerUserId) session = null }
    override fun readUsername(ownerUserId: String): String = session?.username.orEmpty()
    override fun saveUsername(ownerUserId: String, username: String) = Unit
}

private class FakeCoordinationCache : CoordinationCache {
    val values = mutableMapOf<String, CoordinationSnapshot>()
    override suspend fun read(ownerUserId: String): CoordinationSnapshot? = values[ownerUserId]
    override suspend fun save(ownerUserId: String, snapshot: CoordinationSnapshot) { values[ownerUserId] = snapshot }
}

private fun request() = SimpleOneRequest(sysId = "1", number = "REQ1", assignedUser = "Иван Инженеров")
