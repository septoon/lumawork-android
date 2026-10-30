package ru.lumastack.lumawork.data.simpleone

import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import ru.lumastack.lumawork.domain.backpack.BackpackItem
import ru.lumastack.lumawork.domain.coordination.CoordinationRegion
import ru.lumastack.lumawork.domain.simpleone.*
import ru.lumastack.lumawork.domain.timereport.TimeReportEntry

class SimpleOnePerformanceRepositoryTest {
    @Test fun `active list and later details publish while first detail is stalled without a chunk barrier`() = runBlocking {
        val firstStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val lastPublished = CompletableDeferred<Unit>()
        val running = AtomicInteger()
        val maximum = AtomicInteger()
        val api = PerformanceApi {
            val count = running.incrementAndGet()
            maximum.updateAndGet { old -> maxOf(old, count) }
            try {
                if (it.id == "1") { firstStarted.complete(Unit); release.await() }
                else { firstStarted.await(); delay(10) }
                it.copy(description = "detail-${it.id}")
            } finally { running.decrementAndGet() }
        }.apply { listed = (1..8).map { row(it.toString(), "v1") } }
        val snapshots = mutableListOf<Pair<SimpleOneSnapshot, Set<String>>>()
        val refresh = async {
            repository(api).refresh("owner", null) { snapshot, pending ->
                snapshots += snapshot to pending
                if (snapshot.activeRequests.last().description == "detail-8") lastPublished.complete(Unit)
            }
        }
        try {
            withTimeout(5_000) { lastPublished.await() }
            assertFalse(refresh.isCompleted)
            assertEquals((1..8).map { it.toString() }, snapshots.first().second.toList())
            assertTrue(snapshots.first().first.activeRequests.all { it.description.isEmpty() })
            assertTrue(snapshots.last().second.contains("1"))
            assertTrue(maximum.get() <= 4)
            release.complete(Unit)
            assertEquals((1..8).map { "detail-$it" }, refresh.await().activeRequests.map { it.description })
            assertTrue(snapshots.last().second.isEmpty())
        } finally { release.complete(Unit); refresh.cancelAndJoin() }
    }

    @Test fun `progressive hydration terminates promptly when a worker detects a replaced session`() = runBlocking {
        val sessions = PerformanceSessionStore()
        val api = PerformanceApi {
            sessions.value = sessions.value.copy(authKey = "replacement")
            it.copy(description = "late")
        }
        var published = 0
        val error = runCatching {
            withTimeout(1_000) { repository(api, sessions).refresh("owner", null) { _, _ -> published++ } }
        }.exceptionOrNull()
        assertTrue(published > 0)
        assertTrue(error is CancellationException)
        assertFalse("A cancelled worker must terminate its collector, never strand receive", error is TimeoutCancellationException)
    }

    @Test fun `a cancelled detail consumer leaves the shared request alive for another consumer`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val api = PerformanceApi { started.complete(Unit); release.await(); it.copy(description = "kept") }
        val repo = repository(api)
        val first = async { repo.requestDetails("owner", row("1", "v1")) }
        started.await()
        val second = async(start = CoroutineStart.UNDISPATCHED) { repo.requestDetails("owner", row("1", "v1")) }
        first.cancelAndJoin()
        release.complete(Unit)
        assertEquals("kept", second.await().description)
        assertEquals(1, api.detailCalls.get())
    }

    @Test fun `read only card hydration never fetches missing changed or another session details`() = runBlocking {
        val sessions = PerformanceSessionStore()
        val api = PerformanceApi { it.copy(description = "local detail") }
        val repo = repository(api, sessions)
        assertNull(repo.cachedRequestDetails("owner", row("missing", "v1")))
        assertEquals(0, api.detailCalls.get())
        repo.requestDetails("owner", row("1", "v1"))
        assertEquals("local detail", repo.cachedRequestDetails("owner", row("1", "v1"))!!.description)
        assertNull(repo.cachedRequestDetails("owner", row("1", "v2")))
        sessions.value = sessions.value.copy(authKey = "replacement")
        assertNull(repo.cachedRequestDetails("owner", row("1", "v1")))
        assertEquals(1, api.detailCalls.get())
    }

    @Test fun `ordinary details refresh when list version changes with identical state and deadline`() = runBlocking {
        val api = PerformanceApi { it.copy(description = "details-${it.sysUpdatedAt}") }
        val repo = repository(api)
        repo.requestDetails("owner", row("1", "v1"))
        assertEquals("details-v2", repo.requestDetails("owner", row("1", "v2")).description)
        assertEquals(2, api.detailCalls.get())
    }

    @Test fun `same session and version share one running request`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val api = PerformanceApi { started.complete(Unit); release.await(); it.copy(description = "shared") }
        val repo = repository(api)
        val first = async { repo.requestDetails("owner", row("1", "v1")) }
        started.await()
        val second = async(start = CoroutineStart.UNDISPATCHED) { repo.requestDetails("owner", row("1", "v1")) }
        yield()
        release.complete(Unit)
        assertEquals("shared", first.await().description)
        assertEquals("shared", second.await().description)
        assertEquals(1, api.detailCalls.get())
    }

    @Test fun `same version with different list context shares hydration and fills each caller context`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val api = PerformanceApi { started.complete(Unit); release.await(); it.copy(description = "shared") }
        val repo = repository(api)
        val first = async { repo.requestDetails("owner", row("1", "v1")) }
        started.await()
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            repo.requestDetails("owner", row("1", "v1").copy(address = "caller address", source = SimpleOneRequestSource.Closed))
        }
        release.complete(Unit)
        assertEquals("shared", first.await().description)
        assertEquals("caller address", second.await().address)
        assertEquals(SimpleOneRequestSource.Closed, second.await().source)
        assertEquals(1, api.detailCalls.get())
    }

    @Test fun `rejected staged archive commit keeps rows and cursor atomically unchanged`() = runBlocking {
        val directory = Files.createTempDirectory("simpleone-rejected-commit").toFile()
        try {
            val cache = FileSimpleOneSupplementalCache(directory, Json)
            val initial = ClosedSimpleOneArchive(listOf(row("1")), isComplete = true, queryRevision = 2,
                wideCursor = ClosedRequestsSyncCursor("2026-10-28 12:00:00", "1"))
            cache.saveClosedArchive("owner", initial)
            val changed = initial.copy(requests = listOf(row("1"), row("2")),
                wideCursor = ClosedRequestsSyncCursor("2026-10-29 12:00:00", "2"))
            val result = runCatching { cache.saveClosedArchive("owner", changed) { throw CancellationException("session changed") } }
            assertTrue(result.exceptionOrNull() is CancellationException)
            assertEquals(initial, cache.readClosedArchive("owner"))
            assertEquals(initial, FileSimpleOneSupplementalCache(directory, Json).readClosedArchive("owner"))
            assertFalse(directory.listFiles()!!.any { it.name.endsWith(".tmp") })
        } finally { directory.deleteRecursively() }
    }

    @Test fun `details remain available while an archive waits for its commit`() = runBlocking {
        val directory = Files.createTempDirectory("simpleone-independent-cache").toFile()
        val release = CompletableDeferred<Unit>()
        try {
            val cache = FileSimpleOneSupplementalCache(directory, Json)
            val entry = SimpleOneDetailCacheEntry(row("1"), 100)
            cache.saveDetail("owner", entry)
            val staged = CompletableDeferred<Unit>()
            val archiveSave = async {
                cache.saveClosedArchive("owner", ClosedSimpleOneArchive(listOf(row("2")))) { commit ->
                    staged.complete(Unit); release.await(); commit()
                }
            }
            staged.await()
            assertEquals(entry, withTimeout(1_000) { cache.readDetail("owner", "1") })
            release.complete(Unit)
            archiveSave.await()
        } finally { release.complete(Unit); directory.deleteRecursively() }
    }

    @Test fun `replacement session cannot reuse previous session details`() = runBlocking {
        val sessions = PerformanceSessionStore()
        val api = PerformanceApi { it.copy(description = "response-${apiSession.incrementAndGet()}") }
        val repo = repository(api, sessions)
        assertEquals("response-1", repo.requestDetails("owner", row("1", "v1")).description)
        sessions.value = sessions.value.copy(authKey = "replacement")
        assertEquals("response-2", repo.requestDetails("owner", row("1", "v1")).description)
    }

    @Test fun `metadata-only archive update leaves payload untouched and survives recreation`() = runBlocking {
        val directory = Files.createTempDirectory("simpleone-metadata").toFile()
        try {
            val cache = FileSimpleOneSupplementalCache(directory, Json)
            val initial = ClosedSimpleOneArchive(listOf(row("1", "v1")), updatedAtEpochMillis = 1,
                isComplete = true, queryRevision = 2)
            cache.saveClosedArchive("owner", initial)
            val payload = directory.listFiles()!!.single { it.name.endsWith("-closed.json") }
            val bytes = payload.readBytes()
            payload.setLastModified(1_000)
            val metadata = initial.copy(updatedAtEpochMillis = 2,
                wideCursor = ClosedRequestsSyncCursor("2026-10-29 12:00:00", "1"),
                syncRecords = mapOf("1" to ClosedRequestsSyncRecord("REQ1", "2026-10-29 12:00:00")))
            cache.saveClosedArchive("owner", metadata)
            assertArrayEquals(bytes, payload.readBytes())
            assertEquals(1_000L, payload.lastModified())
            assertEquals(metadata, FileSimpleOneSupplementalCache(directory, Json).readClosedArchive("owner"))
            // A restored/replaced payload must never consume a cursor written for another payload.
            val replacement = initial.copy(requests = listOf(row("2", "v2")), updatedAtEpochMillis = 3)
            payload.writeText(Json.encodeToString(ClosedSimpleOneArchive.serializer(), replacement))
            assertEquals(replacement, FileSimpleOneSupplementalCache(directory, Json).readClosedArchive("owner"))
        } finally { directory.deleteRecursively() }
    }

    @Test fun `late older detail cannot replace a newer persisted version from the same session`() = runBlocking {
        val directory = Files.createTempDirectory("simpleone-monotonic-details").toFile()
        try {
            val cache = FileSimpleOneSupplementalCache(directory, Json)
            val newer = SimpleOneDetailCacheEntry(row("1", "2026-10-29 12:00:00").copy(description = "new"), 100, "session")
            val older = SimpleOneDetailCacheEntry(row("1", "2026-10-28 12:00:00").copy(description = "old"), 200, "session")
            cache.saveDetail("owner", newer)
            cache.saveDetail("owner", older)
            assertEquals(newer, cache.readDetail("owner", "1"))
            assertEquals(newer, FileSimpleOneSupplementalCache(directory, Json).readDetail("owner", "1"))
            // The ordering rule applies only within a session, never across different accounts.
            val replacementSession = older.copy(sessionFingerprint = "replacement")
            cache.saveDetail("owner", replacementSession)
            assertEquals(replacementSession, cache.readDetail("owner", "1"))
        } finally { directory.deleteRecursively() }
    }

    @Test fun `detail reads reuse the loaded in-memory index`() = runBlocking {
        val directory = Files.createTempDirectory("simpleone-details").toFile()
        try {
            val cache = FileSimpleOneSupplementalCache(directory, Json)
            val entry = SimpleOneDetailCacheEntry(row("1", "v1"), 100)
            cache.saveDetail("owner", entry)
            directory.listFiles()!!.single { it.name.endsWith("-details.json") }.delete()
            assertEquals(entry, cache.readDetail("owner", "1"))
            assertNull(cache.readDetail("other-owner", "1"))
        } finally { directory.deleteRecursively() }
    }

    private val apiSession = AtomicInteger()
}

private fun row(id: String, version: String = "") = SimpleOneRequest(id, "REQ$id", sysUpdatedAt = version)
private class PerformanceSessionStore : SimpleOneSessionStore {
    var value = StoredSimpleOneSession("owner", "auth", "engineer")
    override fun read(ownerUserId: String) = value.takeIf { it.ownerUserId == ownerUserId && it.authKey.isNotEmpty() }
    override fun save(session: StoredSimpleOneSession) { value = session }
    override fun clear(ownerUserId: String) { if (ownerUserId == value.ownerUserId) value = value.copy(authKey = "") }
    override fun readUsername(ownerUserId: String) = "engineer"
    override fun saveUsername(ownerUserId: String, username: String) = Unit
}
private class PerformanceApi(private val detail: suspend (SimpleOneRequest) -> SimpleOneRequest) : SimpleOneApi {
    val detailCalls = AtomicInteger()
    var listed = listOf(row("1", "v1"))
    override suspend fun requestDetails(request: SimpleOneRequest, authKey: String): SimpleOneRequest {
        detailCalls.incrementAndGet()
        return detail(request)
    }
    override suspend fun currentUser(authKey: String) = SimpleOneUser("user", "engineer", "", "")
    override suspend fun activeRequests(userId: String, authKey: String) = listed
    override suspend fun login(username: String, password: String) = "auth"
    override suspend fun closedRequests(userId: String, authKey: String) = emptyList<SimpleOneRequest>()
    override suspend fun exportClosedRequests(userId: String, authKey: String) = byteArrayOf()
    override suspend fun timeReportEntries(authKey: String) = emptyList<TimeReportEntry>()
    override suspend fun exportTimeReport(userId: String, authKey: String) = byteArrayOf()
    override suspend fun backpackItems(authKey: String) = emptyList<BackpackItem>()
    override suspend fun coordinationRequests(region: CoordinationRegion, authKey: String) = emptyList<SimpleOneRequest>()
    override suspend fun returnEquipmentRequests(userId: String, authKey: String) = emptyList<SimpleOneRequest>()
}
private fun repository(api: SimpleOneApi, sessions: SimpleOneSessionStore = PerformanceSessionStore()): SimpleOneRepository {
    val details = mutableMapOf<Pair<String, String>, SimpleOneDetailCacheEntry>()
    return SimpleOneRepository(api, sessions, object : SimpleOneCache {
        override suspend fun read(ownerUserId: String): SimpleOneSnapshot? = null
        override suspend fun save(ownerUserId: String, snapshot: SimpleOneSnapshot) = Unit
    }, object : SimpleOneSupplementalCache {
        override suspend fun readClosedArchive(ownerUserId: String): ClosedSimpleOneArchive? = null
        override suspend fun saveClosedArchive(ownerUserId: String, archive: ClosedSimpleOneArchive) = Unit
        override suspend fun readDetail(ownerUserId: String, requestId: String) = details[ownerUserId to requestId]
        override suspend fun saveDetail(ownerUserId: String, entry: SimpleOneDetailCacheEntry) { details[ownerUserId to entry.request.id] = entry }
    })
}
