package ru.lumastack.lumawork.data.analytics

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import ru.lumastack.lumawork.domain.analytics.*
import ru.lumastack.lumawork.domain.simpleone.*

@OptIn(ExperimentalCoroutinesApi::class)
class WorkStatisticsSyncCoordinatorTest {
    private val identity = StatisticsIdentity("owner", "token", "fingerprint")
    private val now = "2026-11-07T09:00:00Z"
    private class Api : WorkStatisticsApi {
        var failPreflight = false
        var error: Exception? = null
        val uploads = mutableListOf<WorkStatisticsUpload>()
        var preflights = 0
        override suspend fun me(token: String): WorkStatisticsMe { preflights++; if (failPreflight) error("offline"); return WorkStatisticsMe(4, true, "so", null, null, 1) }
        override suspend fun upload(token: String, payload: WorkStatisticsUpload): WorkStatisticsReceipt {
            uploads += payload; error?.let { throw it }
            return WorkStatisticsReceipt(true, 5, payload.requestId, payload.sourceSyncedAt, "2026-11-07T09:01:00Z")
        }
    }
    private fun store() = WorkStatisticsSourceIndex(Files.createTempDirectory("stats-test").toFile(), Json { encodeDefaults = true })
    private fun index() = WorkSourceIndex.baseline("owner", "so", "fingerprint", emptyList(), now)
    @Test fun failedPreflightCannotPublishUntilFreshSourcePass() = runTest {
        val api = Api(); val disk = store()
        val coordinator = WorkStatisticsSyncCoordinator(api, disk, { identity }, backgroundScope, now = { java.time.Instant.parse(now) })
        api.failPreflight = true
        val pass = coordinator.begin("owner", "so", "fingerprint")
        coordinator.commit(pass, index(), true); coordinator.end(pass)
        assertNull(disk.read("owner").pending)
        api.failPreflight = false
        coordinator.resumePending(); runCurrent()
        assertTrue(api.uploads.isEmpty())
        val fresh = coordinator.begin("owner", "so", "fingerprint")
        coordinator.commit(fresh, index(), true); coordinator.end(fresh)
        assertEquals(4, disk.read("owner").pending!!.payload.expectedRevision)
        assertEquals(2, api.preflights)
    }
    @Test fun retryAfterRestartPreservesBodyAndUuidAndRevisionConflictDropsIt() = runTest {
        val api = Api(); val disk = store()
        val coordinator = WorkStatisticsSyncCoordinator(api, disk, { identity }, backgroundScope, now = { java.time.Instant.parse(now) })
        val pass = coordinator.begin("owner", "so", "fingerprint")
        coordinator.commit(pass, index(), true); coordinator.end(pass)
        val pending = disk.read("owner").pending!!
        api.error = StatisticsFailure(409, "REVISION_CONFLICT")
        val restarted = WorkStatisticsSyncCoordinator(api, disk, { identity }, backgroundScope, now = { java.time.Instant.parse(now) })
        restarted.sendPendingOnce()
        assertEquals(pending.payload, api.uploads.single())
        assertNull(disk.read("owner").pending)
        assertTrue(disk.read("owner").dirty)
    }
    @Test fun interruptedPassForcesBaselineAndOldSessionCannotCommit() = runTest {
        val api = Api(); val disk = store(); var current: StatisticsIdentity? = identity
        val coordinator = WorkStatisticsSyncCoordinator(api, disk, { current }, backgroundScope, now = { java.time.Instant.parse(now) })
        val pass = coordinator.begin("owner", "so", "fingerprint")
        coordinator.end(pass)
        assertTrue(disk.read("owner").dirty)
        val next = coordinator.begin("owner", "so", "fingerprint")
        assertTrue(next.forceBaseline)
        current = identity.copy(owner = "other")
        try { coordinator.commit(next, index(), true); fail("old owner committed") } catch (_: CancellationException) { }
    }
}
