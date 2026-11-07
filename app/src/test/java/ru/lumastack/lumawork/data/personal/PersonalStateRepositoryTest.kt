package ru.lumastack.lumawork.data.personal

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

class PersonalStateRepositoryTest {
    @Test fun `offline changes survive restart and deleting a migrated favorite stays deleted`() = runTest {
        val cache = MemoryCache()
        var online = false
        val remote = mutableMapOf<String, String?>("/server" to "selected")
        val api = PersonalStateApi { _, _, seed, changes ->
            if (!online) error("offline")
            seed.forEach { if (!remote.containsKey(it.key)) remote[it.key] = it.value }
            changes.forEach { if (it.value == null) remote.remove(it.key) else remote[it.key] = it.value }
            remote.map { PersonalEntry(it.key, it.value) }
        }
        val store = PersonalStateRepository("ftp-favorites", cache, api, { "token" }, backgroundScope)
        assertEquals(setOf("/local"), store.values("a", mapOf("/local" to "selected")).keys)
        store.set("a", "/local", null)
        store.synchronize("a")
        val restored = PersonalStateRepository("ftp-favorites", cache, api, { "token" }, backgroundScope)
        assertTrue(restored.values("a").isEmpty())
        online = true; restored.synchronize("a")
        assertEquals(setOf("/server"), restored.values("a").keys)
        assertTrue(restored.values("b").isEmpty())
    }

    @Test fun `response cannot acknowledge an edit made while request is in flight`() = runTest {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var remote = "home"
        val api = PersonalStateApi { _, _, _, changes ->
            entered.complete(Unit); release.await()
            changes.firstOrNull { it.key == "SN" }?.value?.let { remote = it }
            listOf(PersonalEntry("SN", remote))
        }
        val store = PersonalStateRepository("backpack-locations", MemoryCache(), api, { "token" }, backgroundScope)
        store.values("a")
        val request = async { store.synchronize("a") }; entered.await()
        store.set("a", "SN", "car"); release.complete(Unit); request.await()
        assertEquals("car", store.values("a")["SN"])
    }
}
private class MemoryCache : PersonalStateCache {
    val values = mutableMapOf<String, PersonalState>()
    override suspend fun read(owner: String) = values[owner]
    override suspend fun save(owner: String, state: PersonalState) { values[owner] = state }
}
