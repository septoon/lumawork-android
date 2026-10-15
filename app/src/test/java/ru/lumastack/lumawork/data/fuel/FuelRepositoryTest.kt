package ru.lumastack.lumawork.data.fuel

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import ru.lumastack.lumawork.domain.fuel.FuelDraft
import ru.lumastack.lumawork.domain.fuel.FuelRecord
import ru.lumastack.lumawork.domain.fuel.FuelSnapshot
import ru.lumastack.lumawork.domain.model.AppSession
import ru.lumastack.lumawork.domain.model.AppUser

class FuelRepositoryTest {
    @Test
    fun `monthly mileage is applied locally before remote persistence`() = runBlocking {
        val api = FakeFuelApi()
        val cache = FakeFuelCache()
        val repository = FuelRepository(api, cache)

        val snapshot = repository.syncMonthlyMileage(
            session(), "2026-08", "2026-08-12", 120, allowRemotePersistence = false,
        )

        assertEquals(120.0, snapshot.records.single().mileage ?: 0.0, 0.0)
        assertEquals("route_monthly_mileage|2026-08-12|120", snapshot.records.single().comment)
        assertEquals(0, api.createCalls)
    }

    @Test
    fun `remote sync creates once and skips unchanged monthly marker`() = runBlocking {
        val api = FakeFuelApi()
        val cache = FakeFuelCache()
        val repository = FuelRepository(api, cache)

        val first = repository.syncMonthlyMileage(
            session(), "2026-08", "2026-08-12", 120, allowRemotePersistence = true,
        )
        val second = repository.syncMonthlyMileage(
            session(), "2026-08", "2026-08-12", 120, allowRemotePersistence = true,
        )

        assertEquals("remote-1", first.records.single().id)
        assertEquals(1, second.records.size)
        assertEquals(1, api.createCalls)
        assertEquals(0, api.updateCalls)
    }

    @Test
    fun `changed monthly mileage updates the same server record`() = runBlocking {
        val api = FakeFuelApi()
        val cache = FakeFuelCache().apply {
            value = FuelSnapshot(
                listOf(FuelRecord("existing", date = "2026-08-11", mileage = 100.0, comment = "route_monthly_mileage|2026-08-11|100")),
            )
        }
        val repository = FuelRepository(api, cache)

        val snapshot = repository.syncMonthlyMileage(
            session(), "2026-08", "2026-08-12", 120, allowRemotePersistence = true,
        )

        assertEquals(1, snapshot.records.size)
        assertEquals("existing", snapshot.records.single().id)
        assertEquals(0, api.createCalls)
        assertEquals(1, api.updateCalls)
    }

    private fun session() = AppSession("token", AppUser("owner", "owner@example.com", "user"))
}

private class FakeFuelApi : FuelApi {
    var createCalls = 0
    var updateCalls = 0

    override suspend fun records(token: String): List<FuelRecord> = emptyList()
    override suspend fun create(token: String, draft: FuelDraft): FuelRecord {
        createCalls += 1
        return draft.record("remote-$createCalls")
    }
    override suspend fun update(token: String, recordId: String, draft: FuelDraft): FuelRecord {
        updateCalls += 1
        return draft.record(recordId)
    }
    override suspend fun delete(token: String, recordId: String) = Unit

    private fun FuelDraft.record(id: String) = FuelRecord(
        id = id,
        date = date,
        mileage = mileageValue,
        comment = comment,
    )
}

private class FakeFuelCache : FuelCache {
    var value: FuelSnapshot? = null
    override suspend fun read(ownerUserId: String): FuelSnapshot? = value
    override suspend fun save(ownerUserId: String, snapshot: FuelSnapshot) { value = snapshot }
}
