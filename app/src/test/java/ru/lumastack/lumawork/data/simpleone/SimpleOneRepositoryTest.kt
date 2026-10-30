package ru.lumastack.lumawork.data.simpleone

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import ru.lumastack.lumawork.ui.simpleone.SimpleOneUiState
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import ru.lumastack.lumawork.ui.simpleone.SimpleOneViewModel
import ru.lumastack.lumawork.domain.requests.ClosedRequestsPeriod
import ru.lumastack.lumawork.domain.requests.closedEffectiveTime
import ru.lumastack.lumawork.domain.requests.parseClosedRequestsDate
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.lumastack.lumawork.domain.simpleone.ClosedArchiveSource
import ru.lumastack.lumawork.domain.coordination.CoordinationRegion
import ru.lumastack.lumawork.domain.simpleone.ClosedSimpleOneArchive
import ru.lumastack.lumawork.domain.simpleone.SimpleOneRequest
import ru.lumastack.lumawork.domain.simpleone.SimpleOneSnapshot
import ru.lumastack.lumawork.domain.simpleone.SimpleOneUser
import ru.lumastack.lumawork.domain.simpleone.ClosedRequestsSyncScope
import ru.lumastack.lumawork.domain.simpleone.ClosedRequestsSyncCursor
import ru.lumastack.lumawork.domain.simpleone.ClosedRequestsSyncChange
import ru.lumastack.lumawork.domain.simpleone.ClosedRequestsSyncBatch
import ru.lumastack.lumawork.domain.simpleone.ClosedRequestsSyncRecord
import ru.lumastack.lumawork.domain.timereport.TimeReportEntry
import ru.lumastack.lumawork.domain.backpack.BackpackItem

class SimpleOneRepositoryTest {
    @Test fun `successful empty XLSX baseline without heads does not export again on manual refresh`() = runBlocking {
        val cache = FakeSimpleOneSupplementalCache()
        val sessions = FakeSimpleOneSessionStore().apply { save(StoredSimpleOneSession("owner", "auth", "engineer")) }
        val api = FakeSimpleOneApi(exportedXlsx = ClosedRequestsXlsxExport.create(emptyList()))
        val repository = SimpleOneRepository(api, sessions, FakeSimpleOneCache(), cache)
        val first = repository.refreshClosedArchive("owner")
        assertTrue(first.isReady)
        assertTrue(first.requests.isEmpty())
        assertNull(first.wideCursor)
        assertTrue(first.lastWideSyncAtEpochMillis != null)
        repository.refreshClosedArchive("owner")
        assertEquals(1, api.exportCalls)
        assertEquals(1, api.syncCalls)
    }

    @Test fun `successful nonempty legacy XLSX baseline with unavailable head is not exported again`() = runBlocking {
        val monthly = ClosedRequestsSyncCursor("2026-10-28 12:00:00", "request-1")
        val record = request().copy(state = "Закрыта", requestType = "returnEquip", registeredAt = "2026-10-27 10:00:00",
            sysUpdatedAt = "2026-10-29 12:01:00")
        val cache = FakeSimpleOneSupplementalCache().apply {
            archives["owner"] = ClosedSimpleOneArchive(listOf(record), isComplete = true, queryRevision = 2,
                syncRevision = 1, syncUserId = "simple-user", monthlyCursor = monthly)
        }
        val sessions = FakeSimpleOneSessionStore().apply { save(StoredSimpleOneSession("owner", "auth", "engineer")) }
        val api = FakeSimpleOneApi(closed = listOf(record), exportedXlsx = ClosedRequestsXlsxExport.create(listOf(record)),
            beforeHead = { throw SimpleOneFailure.Http(500, "Head unavailable") })
        val first = SimpleOneRepository(api, sessions, FakeSimpleOneCache(), cache).refreshClosedArchive("owner")
        assertEquals(1, api.exportCalls)
        assertNull(first.wideCursor)
        assertTrue(first.lastWideSyncAtEpochMillis != null)
        SimpleOneRepository(api, sessions, FakeSimpleOneCache(), cache).refreshClosedArchive("owner")
        assertEquals(1, api.exportCalls)
        assertEquals(1, api.syncCalls)
        assertNull(api.lastUpdatedSince)
    }

    @Test fun `legacy ready archive without requested wide cursor establishes one XLSX baseline before incremental refresh`() = runBlocking {
        val monthly = ClosedRequestsSyncCursor("2026-10-28 12:00:00", "request-1")
        val head = ClosedRequestsSyncCursor("2026-10-29 12:00:00", "request-2")
        val existing = request().copy(state = "Закрыта", requestType = "returnEquip", registeredAt = "2026-10-27 10:00:00",
            contactPhone = "+79780000000", tableFields = mapOf("Локальная колонка" to "000123", "ИНН ТСП" to "1234567890"))
        val historical = existing.copy(sysId = "historic", number = "REQ-HISTORY")
        val old = ClosedSimpleOneArchive(listOf(existing, historical), isComplete = true, queryRevision = 2,
            syncRevision = 1, syncUserId = "simple-user", monthlyCursor = monthly)
        val cache = FakeSimpleOneSupplementalCache().apply { archives["owner"] = old }
        val sessions = FakeSimpleOneSessionStore().apply { save(StoredSimpleOneSession("owner", "auth", "engineer")) }
        val fresh = existing.copy(contactPhone = "", engineerComment = "Новый комментарий", tableFields = emptyMap(),
            sysUpdatedAt = "2026-10-29 12:01:00")
        val new = fresh.copy(sysId = "request-2", number = "REQ2")
        lateinit var api: FakeSimpleOneApi
        api = FakeSimpleOneApi(closed = listOf(fresh, new), exportedXlsx = ClosedRequestsXlsxExport.create(listOf(fresh, new)),
            closedHeads = mapOf(ClosedRequestsSyncScope.Monthly to monthly, ClosedRequestsSyncScope.All to head),
            beforeExport = {
                assertEquals(ClosedRequestsSyncScope.entries.toSet(), api.headCalls.toSet())
                assertEquals(old, cache.archives["owner"])
            })
        val first = SimpleOneRepository(api, sessions, FakeSimpleOneCache(), cache).refreshClosedArchive("owner")
        assertEquals(1, api.exportCalls)
        assertEquals(0, api.syncCalls + api.archiveCalls + api.detailCalls)
        assertEquals(head, first.wideCursor)
        assertEquals(setOf("REQ1", "REQ2", "REQ-HISTORY"), first.requests.map { it.number }.toSet())
        val repaired = first.requests.single { it.number == "REQ1" }
        assertEquals("Новый комментарий", repaired.engineerComment)
        assertEquals("+79780000000", repaired.contactPhone)
        assertEquals("000123", repaired.tableFields["Локальная колонка"])
        assertEquals("1234567890", repaired.tableFields["ИНН ТСП"])
        SimpleOneRepository(api, sessions, FakeSimpleOneCache(), cache).refreshClosedArchive("owner")
        assertEquals("2026-10-29 12:00:00", api.lastUpdatedSince)
        assertEquals(1, api.exportCalls)
        assertEquals(1, api.syncCalls)
    }

    @Test fun `failed legacy baseline cannot save captured head over unchanged cached rows`() = runBlocking {
        val monthly = ClosedRequestsSyncCursor("2026-10-28 12:00:00", "request-1")
        val head = ClosedRequestsSyncCursor("2026-10-29 12:00:00", "request-2")
        val old = ClosedSimpleOneArchive(listOf(request()), isComplete = true, queryRevision = 2,
            syncRevision = 1, syncUserId = "simple-user", monthlyCursor = monthly)
        val cache = FakeSimpleOneSupplementalCache().apply { archives["owner"] = old }
        val sessions = FakeSimpleOneSessionStore().apply { save(StoredSimpleOneSession("owner", "auth", "engineer")) }
        val api = FakeSimpleOneApi(error = SimpleOneFailure.Http(500, "Unavailable"),
            closedHeads = mapOf(ClosedRequestsSyncScope.Monthly to monthly, ClosedRequestsSyncScope.All to head))
        val snapshot = SimpleOneSnapshot(currentUser = SimpleOneUser("simple-user", "engineer", "", ""),
            sessionFingerprint = simpleOneSessionFingerprint(sessions.read("owner")!!))
        assertTrue(runCatching { SimpleOneRepository(api, sessions, FakeSimpleOneCache(), cache)
            .refreshClosedArchive("owner", snapshot) }.isFailure)
        assertEquals(1, api.exportCalls)
        assertEquals(0, api.syncCalls)
        assertEquals(old, cache.archives["owner"])
    }

    @Test fun `a new TIN in description replaces the old direct TIN`() = runBlocking {
        val oldRecord = request().copy(tableFields = mapOf("ИНН ТСП" to "1234567890"))
        val cache = FakeSimpleOneSupplementalCache().apply {
            archives["owner"] = ClosedSimpleOneArchive(listOf(oldRecord), isComplete = true, queryRevision = 2,
                syncRevision = 1, syncUserId = "simple-user", wideCursor = ClosedRequestsSyncCursor("2026-10-27 12:00:00", "request-1"))
        }
        val sessions = FakeSimpleOneSessionStore().apply { save(StoredSimpleOneSession("owner", "auth", "engineer")) }
        val fresh = request().copy(description = "Адрес: Алушта<br>ИНН ТСП: 9876543210<br>Контакт: Иван")
        val result = SimpleOneRepository(FakeSimpleOneApi(closed = listOf(fresh)), sessions, FakeSimpleOneCache(), cache).refreshClosedArchive("owner")
        assertEquals("9876543210", result.requests.single().tableFields["ИНН ТСП"])
    }

    @Test fun `legacy list baseline preserves extra cached export fields`() = runBlocking {
        val oldRecord = request().copy(requestType = "returnEquip", registeredAt = "2026-10-27 10:00:00",
            tableFields = mapOf("SN POS установка" to "00001234", "ИНН ТСП" to "1234567890"))
        val cache = FakeSimpleOneSupplementalCache().apply {
            archives["owner"] = ClosedSimpleOneArchive(listOf(oldRecord), isComplete = true, queryRevision = 2)
        }
        val sessions = FakeSimpleOneSessionStore().apply { save(StoredSimpleOneSession("owner", "auth", "engineer")) }
        val api = FakeSimpleOneApi(closed = listOf(oldRecord.copy(tableFields = emptyMap())))
        val result = SimpleOneRepository(api, sessions, FakeSimpleOneCache(), cache).refreshClosedArchive("owner")
        assertEquals("00001234", result.requests.single().tableFields["SN POS установка"])
        assertEquals("1234567890", result.requests.single().tableFields["ИНН ТСП"])
    }

    @Test fun `a sync cannot overwrite a saved valid merchant TIN with an invalid value`() = runBlocking {
        val oldRecord = request().copy(tableFields = mapOf("ИНН ТСП" to "1234567890"))
        val old = ClosedSimpleOneArchive(listOf(oldRecord), isComplete = true, queryRevision = 2, syncRevision = 1, syncUserId = "simple-user", wideCursor = ClosedRequestsSyncCursor("2026-10-27 12:00:00", "request-1"))
        val cache = FakeSimpleOneSupplementalCache().apply { archives["owner"] = old }
        val sessions = FakeSimpleOneSessionStore().apply { save(StoredSimpleOneSession("owner", "auth", "engineer")) }
        val api = FakeSimpleOneApi(closed = listOf(request().copy(tableFields = mapOf("ИНН ТСП" to "123..."))))
        val result = SimpleOneRepository(api, sessions, FakeSimpleOneCache(), cache).refreshClosedArchive("owner")
        assertEquals("1234567890", result.requests.single().tableFields["ИНН ТСП"])
    }

    @Test fun `sync rewinds to the earliest locally missing nonexcluded record`() = runBlocking {
        val recent = ClosedRequestsSyncCursor("2026-10-28 12:00:00", "head")
        val old = ClosedSimpleOneArchive(emptyList(), isComplete = true, queryRevision = 2,
            syncRevision = 1, syncUserId = "simple-user", wideCursor = recent,
            syncRecords = mapOf("request-1" to ClosedRequestsSyncRecord("REQ1", "2026-10-26 12:00:00")))
        val cache = FakeSimpleOneSupplementalCache().apply { archives["owner"] = old }
        val sessions = FakeSimpleOneSessionStore().apply { save(StoredSimpleOneSession("owner", "auth", "engineer")) }
        val api = FakeSimpleOneApi(closed = listOf(request().copy(requestType = "returnEquip", registeredAt = "2026-10-26 10:00:00", sysUpdatedAt = "2026-10-26 12:00:00")))
        val result = SimpleOneRepository(api, sessions, FakeSimpleOneCache(), cache).refreshClosedArchive("owner")
        assertEquals("2026-10-26 12:00:00", api.lastUpdatedSince)
        assertEquals("REQ1", result.requests.single().number)
    }

    @Test fun `monthly changes preserve historical rows and the independent wide cursor`() = runBlocking {
        val old = request().copy(sysId = "historic", number = "REQ-HISTORY", requestType = "returnEquip", registeredAt = "2020-01-01 10:00:00")
        val recent = request().copy(requestType = "returnEquip", registeredAt = "2026-10-28 10:00:00", sysUpdatedAt = "2026-10-28 12:00:00")
        val wide = ClosedRequestsSyncCursor("2026-10-27 12:00:00", "historic")
        val archive = ClosedSimpleOneArchive(listOf(old), isComplete = true, queryRevision = 2,
            syncRevision = 1, syncUserId = "simple-user", wideCursor = wide, monthlyCursor = wide, lastWideSyncAtEpochMillis = 100)
        val cache = FakeSimpleOneSupplementalCache().apply { archives["owner"] = archive }
        val sessions = FakeSimpleOneSessionStore().apply { save(StoredSimpleOneSession("owner", "auth", "engineer")) }
        val result = SimpleOneRepository(FakeSimpleOneApi(closed = listOf(recent)), sessions, FakeSimpleOneCache(), cache)
            .refreshClosedArchive("owner", scope = ClosedRequestsSyncScope.Monthly)
        assertEquals(setOf("REQ-HISTORY", recent.number), result.requests.map { it.number }.toSet())
        assertEquals(wide, result.wideCursor)
        assertEquals(100L, result.lastWideSyncAtEpochMillis)
        assertEquals(ClosedRequestsSyncCursor("2026-10-28 12:00:00", recent.sysId), result.monthlyCursor)
        assertEquals(recent.sysUpdatedAt, result.syncRecords[recent.sysId]?.updatedAt)
    }

    @Test fun `failed sync preserves committed records and both cursors`() = runBlocking {
        val cursor = ClosedRequestsSyncCursor("2026-10-27 12:00:00", "request-1")
        val old = ClosedSimpleOneArchive(listOf(request()), isComplete = true, queryRevision = 2,
            syncRevision = 1, syncUserId = "simple-user", monthlyCursor = cursor, wideCursor = cursor)
        val cache = FakeSimpleOneSupplementalCache().apply { archives["owner"] = old }
        val sessions = FakeSimpleOneSessionStore().apply { save(StoredSimpleOneSession("owner", "auth", "engineer")) }
        val api = FakeSimpleOneApi(error = SimpleOneFailure.Http(500, "Unavailable"))
        val snapshot = SimpleOneSnapshot(currentUser = SimpleOneUser("simple-user", "engineer", "Иван", "Инженеров"),
            sessionFingerprint = simpleOneSessionFingerprint(sessions.read("owner")!!))
        assertTrue(runCatching { SimpleOneRepository(api, sessions, FakeSimpleOneCache(), cache)
            .refreshClosedArchive("owner", snapshot, ClosedRequestsSyncScope.Monthly) }.isFailure)
        assertEquals(old, cache.archives["owner"])
    }

    @Test fun `sync identity conflict cannot replace a saved request or advance cursor`() = runBlocking {
        val old = ClosedSimpleOneArchive(listOf(request()), isComplete = true, queryRevision = 2,
            syncRevision = 1, syncUserId = "simple-user", wideCursor = ClosedRequestsSyncCursor("2026-10-27 12:00:00", "request-1"), syncRecords = mapOf("request-1" to ClosedRequestsSyncRecord("REQ1", "2026-10-27 12:00:00")))
        val cache = FakeSimpleOneSupplementalCache().apply { archives["owner"] = old }
        val sessions = FakeSimpleOneSessionStore().apply { save(StoredSimpleOneSession("owner", "auth", "engineer")) }
        val api = FakeSimpleOneApi(closed = listOf(request().copy(sysId = "wrong-id", sysUpdatedAt = "2026-10-28 12:00:00")))
        assertTrue(runCatching { SimpleOneRepository(api, sessions, FakeSimpleOneCache(), cache).refreshClosedArchive("owner") }.isFailure)
        assertEquals(old, cache.archives["owner"])
    }

    @Test fun `hydration preserves list version when detail response omits it`() = runBlocking {
        val api = FakeSimpleOneApi(closed = listOf(request().copy(sysUpdatedAt = "2026-10-28 12:00:00")),
            closedDetail = { it.copy(requestType = "returnEquip", registeredAt = "2026-10-27 10:00:00", sysUpdatedAt = "") })
        val cache = FakeSimpleOneSupplementalCache().apply {
            archives["owner"] = ClosedSimpleOneArchive(listOf(request()), isComplete = true, queryRevision = 2, wideCursor = ClosedRequestsSyncCursor("2026-10-27 12:00:00", "request-1"))
        }
        val sessions = FakeSimpleOneSessionStore().apply { save(StoredSimpleOneSession("owner", "auth", "engineer")) }
        val repo = SimpleOneRepository(api, sessions, FakeSimpleOneCache(), cache)
        assertEquals("2026-10-28 12:00:00", repo.refreshClosedArchive("owner").requests.single().sysUpdatedAt)
        repo.refreshClosedArchive("owner")
        assertEquals(1, api.detailCalls)
    }

    @Test fun `unchanged archive reuses rows and server cursor after repository recreation`() = runBlocking {
        val record = request().copy(requestType = "returnEquip", registeredAt = "2026-10-27 10:00:00", sysUpdatedAt = "2026-10-28 12:00:00")
        val cache = FakeSimpleOneSupplementalCache().apply {
            archives["owner"] = ClosedSimpleOneArchive(listOf(record), isComplete = true, queryRevision = 2,
                syncRevision = 1, syncUserId = "simple-user", wideCursor = ClosedRequestsSyncCursor("2026-10-27 12:00:00", "request-1"))
        }
        val api = FakeSimpleOneApi(closed = listOf(record))
        val sessions = FakeSimpleOneSessionStore().apply { save(StoredSimpleOneSession("owner", "auth", "engineer")) }
        val first = SimpleOneRepository(api, sessions, FakeSimpleOneCache(), cache).refreshClosedArchive("owner")
        assertEquals("2026-10-28 12:00:00", first.syncUpdatedAt)
        val second = SimpleOneRepository(api, sessions, FakeSimpleOneCache(), cache).refreshClosedArchive("owner")
        org.junit.Assert.assertSame(first.requests, second.requests)
        assertEquals("2026-10-28 12:00:00", api.lastUpdatedSince)
        assertEquals(0, api.detailCalls + api.exportCalls)
    }

    @Test fun `failed incremental hydration does not advance cursor or replace archive`() = runBlocking {
        val old = ClosedSimpleOneArchive(listOf(request()), isComplete = true, queryRevision = 2,
            syncUpdatedAt = "2026-10-27 12:00:00", syncUserId = "simple-user", wideCursor = ClosedRequestsSyncCursor("2026-10-27 12:00:00", "request-1"))
        val cache = FakeSimpleOneSupplementalCache().apply { archives["owner"] = old }
        val api = FakeSimpleOneApi(closed = listOf(request().copy(sysUpdatedAt = "2026-10-28 12:00:00")),
            closedDetail = { throw SimpleOneFailure.InvalidResponse })
        val sessions = FakeSimpleOneSessionStore().apply { save(StoredSimpleOneSession("owner", "auth", "engineer")) }
        val result = runCatching { SimpleOneRepository(api, sessions, FakeSimpleOneCache(), cache).refreshClosedArchive("owner") }
        assertTrue(result.isFailure)
        assertEquals(old, cache.archives["owner"])
    }

    @Test fun `ready archive refresh merges changes without another XLSX export`() = runBlocking {
        val old = request().copy(sysId = "old", number = "REQ-OLD", requestType = "returnEquip", registeredAt = "2026-10-01 10:00:00")
        val changed = request().copy(requestType = "returnEquip", registeredAt = "2026-10-27 10:00:00", engineerComment = "Новый комментарий")
        val api = FakeSimpleOneApi(closed = listOf(changed))
        val cache = FakeSimpleOneSupplementalCache().apply {
            archives["owner"] = ClosedSimpleOneArchive(listOf(old, changed.copy(engineerComment = "Старый комментарий")), isComplete = true, queryRevision = 2,
                syncRevision = 1, syncUserId = "simple-user", wideCursor = ClosedRequestsSyncCursor("2026-10-27 12:00:00", "request-1"))
        }
        val sessions = FakeSimpleOneSessionStore().apply { save(StoredSimpleOneSession("owner", "auth", "engineer")) }
        val result = SimpleOneRepository(api, sessions, FakeSimpleOneCache(), cache).refreshClosedArchive("owner")
        assertEquals("Refresh must not wait for export generation", 0, api.exportCalls)
        assertEquals(setOf("REQ-OLD", changed.number), result.requests.map { it.number }.toSet())
        assertEquals("Новый комментарий", result.requests.single { it.number == changed.number }.engineerComment)
        assertEquals(0, api.detailCalls)
    }

    @Test fun `list fallback hydrates all incomplete records and preserves distinct external duplicates`() = runBlocking {
        val listed = listOf(
            request().copy(sysId = "one", number = "REQ1", incomingNumber = "SUTS-SAME", state = "Отклонена"),
            request().copy(sysId = "two", number = "REQ2", incomingNumber = "SUTS-SAME", state = "Выполнена"),
        )
        val api = FakeSimpleOneApi(closed = listed, closedDetail = { it.copy(requestType = "returnEquip", registeredAt = "2026-10-27 09:48:00") })
        val sessions = FakeSimpleOneSessionStore().apply { save(StoredSimpleOneSession("owner", "auth", "engineer")) }
        val cache = FakeSimpleOneSupplementalCache()
        val result = SimpleOneRepository(api, sessions, FakeSimpleOneCache(), cache).refreshClosedArchive("owner")
        assertEquals(2, api.detailCalls)
        assertEquals(setOf("one", "two"), result.requests.map { it.id }.toSet())
        assertTrue(result.requests.all { it.requestType == "returnEquip" })
        assertEquals(setOf("Отклонена", "Выполнена"), result.requests.map { it.state }.toSet())
        assertEquals(result, cache.archives["owner"])
    }

    @Test fun `incomplete XLSX is enriched from details and retains extra columns in exports`() = runBlocking {
        val bytes = makeSimpleOneXlsx(listOf("sys_id", "Номер заявки", "Статус", "Тип заявки", "Служебная колонка"),
            listOf(listOf("one", "REQ1", "Закрыта", "", "сохранить")))
        val api = FakeSimpleOneApi(exportedXlsx = bytes, closedDetail = { it.copy(
            requestType = "returnEquip", state = "Выполнена", registeredAt = "2026-10-27 09:48:00") })
        val sessions = FakeSimpleOneSessionStore().apply { save(StoredSimpleOneSession("owner", "auth", "engineer")) }
        val repo = SimpleOneRepository(api, sessions, FakeSimpleOneCache(), FakeSimpleOneSupplementalCache())
        val result = repo.refreshClosedArchive("owner")
        assertEquals(ClosedArchiveSource.Xlsx, result.source)
        assertEquals("returnEquip", result.requests.single().requestType)
        assertEquals(1, api.detailCalls)
        assertEquals(0, api.archiveCalls)
        val exported = SimpleOneXlsxParser.parse(repo.exportClosedArchive("owner")).single()
        assertEquals("returnEquip", exported.requestType)
        assertEquals("Выполнена", exported.state)
        assertEquals("сохранить", exported.tableFields["Служебная колонка"])
    }

    @Test fun `incomplete XLSX without sys id resolves canonical request number without adding other records`() = runBlocking {
        val bytes = makeSimpleOneXlsx(listOf("Номер заявки", "Статус"), listOf(listOf("REQ1", "Закрыта")))
        val api = FakeSimpleOneApi(exportedXlsx = bytes, closed = listOf(request(), request().copy(sysId = "other", number = "REQ2")))
        val sessions = FakeSimpleOneSessionStore().apply { save(StoredSimpleOneSession("owner", "auth", "engineer")) }
        val result = SimpleOneRepository(api, sessions, FakeSimpleOneCache(), FakeSimpleOneSupplementalCache()).refreshClosedArchive("owner")
        assertEquals(listOf("REQ1"), result.requests.map { it.number })
        assertEquals("request-1", result.requests.single().sysId)
        assertEquals(1, api.archiveCalls)
        assertEquals(1, api.detailCalls)
    }

    @Test fun `failed or still incomplete details preserve previous archive`() = runBlocking {
        for (fails in listOf(true, false)) {
            val old = ClosedSimpleOneArchive(listOf(request().copy(number = "OLD")), isComplete = true, queryRevision = 2)
            val cache = FakeSimpleOneSupplementalCache().apply { archives["owner"] = old }
            val api = FakeSimpleOneApi(closed = listOf(request()), closedDetail = {
                if (fails) throw SimpleOneFailure.Http(500, "Unavailable") else it
            })
            val sessions = FakeSimpleOneSessionStore().apply { save(StoredSimpleOneSession("owner", "auth", "engineer")) }
            val result = runCatching { SimpleOneRepository(api, sessions, FakeSimpleOneCache(), cache).refreshClosedArchive("owner") }
            assertTrue(result.isFailure)
            assertEquals(old, cache.archives["owner"])
            assertEquals(1, api.archiveCalls)
        }
    }

    @Test
    fun `refresh keeps cache scoped to LumaWork user`() = runBlocking {
        val sessionStore = FakeSimpleOneSessionStore().apply {
            save(StoredSimpleOneSession("owner-1", "auth", "engineer"))
        }
        val cache = FakeSimpleOneCache()
        val api = FakeSimpleOneApi()
        val repository = SimpleOneRepository(api, sessionStore, cache) { 1234L }

        val snapshot = repository.refresh("owner-1", cached = null)

        assertEquals(1, snapshot.activeRequests.size)
        assertEquals(1234L, snapshot.updatedAtEpochMillis)
        assertEquals(snapshot, cache.values["owner-1"])
        assertNull(cache.values["owner-2"])
    }

    @Test
    fun `unauthorized refresh removes key but preserves cached snapshot`() = runBlocking {
        val sessionStore = FakeSimpleOneSessionStore().apply {
            save(StoredSimpleOneSession("owner-1", "expired", "engineer"))
        }
        val cached = SimpleOneSnapshot(activeRequests = listOf(request()))
        val cache = FakeSimpleOneCache().apply { values["owner-1"] = cached }
        val repository = SimpleOneRepository(
            FakeSimpleOneApi(error = SimpleOneFailure.Unauthorized),
            sessionStore,
            cache,
        )

        runCatching { repository.refresh("owner-1", cached) }

        assertNull(sessionStore.read("owner-1"))
        assertEquals(cached, cache.values["owner-1"])
    }

    @Test
    fun `successful sign in stores auth key and remembered username`() = runBlocking {
        val sessionStore = FakeSimpleOneSessionStore()
        val repository = SimpleOneRepository(
            FakeSimpleOneApi(),
            sessionStore,
            FakeSimpleOneCache(),
        )

        repository.signIn("owner-1", " engineer ", " password ", cached = null)

        assertEquals("new-auth", sessionStore.read("owner-1")?.authKey)
        assertEquals("engineer", sessionStore.readUsername("owner-1"))
    }

    @Test fun `new authorization cannot restore previous SimpleOne identity or reuse it for filters`() = runBlocking {
        val oldSession = StoredSimpleOneSession("owner-1", "old-auth", "old-user")
        val newSession = StoredSimpleOneSession("owner-1", "new-auth", "new-user")
        val oldSnapshot = SimpleOneSnapshot(SimpleOneUser("old-user-id", "old-user", "", ""), listOf(request()),
            sessionFingerprint = simpleOneSessionFingerprint(oldSession))
        val sessions = FakeSimpleOneSessionStore().apply { save(newSession) }
        val cache = FakeSimpleOneCache().apply { values["owner-1"] = oldSnapshot }
        val api = FakeSimpleOneApi()
        val repository = SimpleOneRepository(api, sessions, cache)
        assertNull(repository.readSnapshot("owner-1"))
        val refreshed = repository.refresh("owner-1", oldSnapshot)
        assertEquals("simple-user", refreshed.currentUser?.sysId)
        assertEquals(simpleOneSessionFingerprint(newSession), refreshed.sessionFingerprint)
        assertEquals(1, api.currentUserCalls)
    }
    @Test fun `coordination identity resolve does not start request lists and stable cache reads are local`() = runBlocking {
        val sessions = FakeSimpleOneSessionStore().apply { save(StoredSimpleOneSession("owner-1", "auth", "user")) }
        val api = FakeSimpleOneApi()
        val repository = SimpleOneRepository(api, sessions, FakeSimpleOneCache())
        val verified = repository.resolveCurrentUser("owner-1")
        assertEquals("simple-user", verified.currentUser?.sysId)
        assertEquals(verified, repository.readSnapshot("owner-1"))
        assertEquals(1, api.currentUserCalls)
        assertEquals(0, api.activeCalls); assertEquals(0, api.archiveCalls); assertEquals(0, api.exportCalls)
    }
    @Test
    fun `fresh detail cache avoids SimpleOne request`() = runBlocking {
        val api = FakeSimpleOneApi()
        val session = StoredSimpleOneSession("owner-1", "auth", "engineer")
        val sessions = FakeSimpleOneSessionStore().apply { save(session) }
        val supplemental = FakeSimpleOneSupplementalCache().apply {
            details["owner-1" to "request-1"] = SimpleOneDetailCacheEntry(
                request().copy(description = "Сохранённые детали"),
                cachedAtEpochMillis = 1_000L,
                sessionFingerprint = simpleOneSessionFingerprint(session),
            )
        }
        val repository = SimpleOneRepository(
            api = api,
            sessionStore = sessions,
            cache = FakeSimpleOneCache(),
            supplementalCache = supplemental,
            nowEpochMillis = { 2_000L },
        )

        val result = repository.requestDetails("owner-1", request())

        assertEquals("Сохранённые детали", result.description)
        assertEquals(0, api.detailCalls)
    }

    @Test fun `coordination detail cache rejects changed and unknown list versions`() = runBlocking {
        val sessions = FakeSimpleOneSessionStore().apply { save(StoredSimpleOneSession("owner-1", "auth", "user")) }
        val supplemental = FakeSimpleOneSupplementalCache().apply {
            details["owner-1" to "request-1"] = SimpleOneDetailCacheEntry(request().copy(sysUpdatedAt = "v1", description = "old"), 1_000L, simpleOneSessionFingerprint(sessions.read("owner-1")!!))
        }
        val api = FakeSimpleOneApi()
        val repository = SimpleOneRepository(api, sessions, FakeSimpleOneCache(), supplemental) { 2_000L }
        assertEquals("old", repository.requestDetails("owner-1", request().copy(sysUpdatedAt = "v1"), requiresCurrentVersion = true).description)
        assertEquals(0, api.detailCalls)
        assertEquals("v2", repository.requestDetails("owner-1", request().copy(sysUpdatedAt = "v2"), requiresCurrentVersion = true).sysUpdatedAt)
        assertEquals(1, api.detailCalls)
        repository.requestDetails("owner-1", request(), requiresCurrentVersion = true)
        assertEquals(2, api.detailCalls)
    }
    @Test fun `coordination network failure cannot replace current list version with stale cached detail`() = runBlocking {
        val sessions = FakeSimpleOneSessionStore().apply { save(StoredSimpleOneSession("owner-1", "auth", "user")) }
        val supplemental = FakeSimpleOneSupplementalCache().apply {
            details["owner-1" to "request-1"] = SimpleOneDetailCacheEntry(request().copy(sysUpdatedAt = "v1"), 1_000L, simpleOneSessionFingerprint(sessions.read("owner-1")!!))
        }
        val repository = SimpleOneRepository(FakeSimpleOneApi(error = SimpleOneFailure.Http(500, "error")), sessions,
            FakeSimpleOneCache(), supplemental) { 2_000L }
        assertTrue(runCatching { repository.requestDetails("owner-1", request().copy(sysUpdatedAt = "v2"), requiresCurrentVersion = true) }.isFailure)
    }
    @Test
    fun `stale detail cache is fallback for transient error`() = runBlocking {
        val sessionStore = FakeSimpleOneSessionStore().apply {
            save(StoredSimpleOneSession("owner-1", "auth", "engineer"))
        }
        val supplemental = FakeSimpleOneSupplementalCache().apply {
            details["owner-1" to "request-1"] = SimpleOneDetailCacheEntry(
                request().copy(description = "Старая копия"),
                cachedAtEpochMillis = 0L,
                sessionFingerprint = simpleOneSessionFingerprint(sessionStore.read("owner-1")!!),
            )
        }
        val repository = SimpleOneRepository(
            api = FakeSimpleOneApi(error = SimpleOneFailure.Http(500, "Ошибка")),
            sessionStore = sessionStore,
            cache = FakeSimpleOneCache(),
            supplementalCache = supplemental,
            nowEpochMillis = { 86_400_001L },
        )

        val result = repository.requestDetails("owner-1", request())

        assertEquals("Старая копия", result.description)
    }

    @Test
    fun `closed archive is cached independently from active snapshot`() = runBlocking {
        val sessionStore = FakeSimpleOneSessionStore().apply {
            save(StoredSimpleOneSession("owner-1", "auth", "engineer"))
        }
        val supplemental = FakeSimpleOneSupplementalCache()
        val repository = SimpleOneRepository(
            api = FakeSimpleOneApi(closed = listOf(request().copy(number = "CLOSED1"))),
            sessionStore = sessionStore,
            cache = FakeSimpleOneCache(),
            supplementalCache = supplemental,
            nowEpochMillis = { 5_000L },
        )
        val active = SimpleOneSnapshot(currentUser = SimpleOneUser("simple-user", "engineer", "", ""))

        val archive = repository.refreshClosedArchive("owner-1", active)

        assertEquals("CLOSED1", archive.requests.single().number)
        assertEquals(5_000L, archive.updatedAtEpochMillis)
        assertEquals(archive, supplemental.archives["owner-1"])
        assertEquals(ClosedArchiveSource.ListApi, archive.source)
    }

    @Test
    fun `closed archive prefers full XLSX export`() = runBlocking {
        val sessionStore = FakeSimpleOneSessionStore().apply {
            save(StoredSimpleOneSession("owner-1", "auth", "engineer"))
        }
        val xlsx = makeSimpleOneXlsx(
            headers = listOf("Номер заявки", "Статус", "Комментарий инженера", "Тип заявки", "Дата выполнения", "Код закрытия"),
            rows = listOf(listOf("XLSX-1", "Закрыта", "Полные данные", "install", "2026-10-27 10:00:00", "Решено с выездом")),
        )
        val repository = SimpleOneRepository(
            api = FakeSimpleOneApi(
                closed = listOf(request().copy(number = "LIST-1")),
                exportedXlsx = xlsx,
            ),
            sessionStore = sessionStore,
            cache = FakeSimpleOneCache(),
            supplementalCache = FakeSimpleOneSupplementalCache(),
        )
        val active = SimpleOneSnapshot(currentUser = SimpleOneUser("simple-user", "engineer", "", ""))

        val archive = repository.refreshClosedArchive("owner-1", active)

        assertEquals(ClosedArchiveSource.Xlsx, archive.source)
        assertEquals("XLSX-1", archive.requests.single().number)
        assertEquals("Полные данные", archive.requests.single().engineerComment)
    }
    @Test fun `late archive response after signout cannot repopulate cache`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val api = FakeSimpleOneApi(beforeExport = { started.complete(Unit); release.await() })
        val store = FakeSimpleOneSessionStore().apply { save(StoredSimpleOneSession("owner", "key", "engineer")) }
        val cache = FakeSimpleOneSupplementalCache()
        val repository = SimpleOneRepository(api, store, FakeSimpleOneCache(), cache)
        val pending = async { runCatching { repository.refreshClosedArchive("owner") } }
        started.await()
        repository.signOut("owner")
        release.complete(Unit)
        assertTrue(pending.await().exceptionOrNull() is kotlinx.coroutines.CancellationException)
        assertNull(cache.archives["owner"])
    }

    @Test fun `late login after signout cannot restore credentials`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val api = FakeSimpleOneApi(beforeLogin = { started.complete(Unit); release.await() })
        val store = FakeSimpleOneSessionStore()
        val repository = SimpleOneRepository(api, store, FakeSimpleOneCache())
        val pending = async { runCatching { repository.authenticate("owner", "engineer", "password") } }
        started.await()
        repository.signOut("owner")
        release.complete(Unit)
        assertTrue(pending.await().exceptionOrNull() is kotlinx.coroutines.CancellationException)
        assertNull(store.read("owner"))
    }

}

private class FakeSimpleOneApi(
    private val error: SimpleOneFailure? = null,
    private val closed: List<SimpleOneRequest> = emptyList(),
    private val exportedXlsx: ByteArray? = null,
    private val closedHeads: Map<ClosedRequestsSyncScope, ClosedRequestsSyncCursor> = emptyMap(),
    private val beforeHead: suspend () -> Unit = {},
    private val beforeExport: suspend () -> Unit = {},
    private val beforeLogin: suspend () -> Unit = {},
    private val beforeHistory: suspend () -> Unit = {},
    private val beforeSync: suspend () -> Unit = {},
    private val beforeActive: suspend () -> Unit = {},
    private val active: () -> List<SimpleOneRequest> = { listOf(request()) },
    private val activeDetail: suspend (SimpleOneRequest) -> SimpleOneRequest = { it },
    private val closedDetail: suspend (SimpleOneRequest) -> SimpleOneRequest = { it.copy(
        requestType = it.requestType.ifBlank { "install" }, resolvedAt = it.resolvedAt.ifBlank { "2026-10-27 10:00:00" },
        closureCode = it.closureCode.ifBlank { "Решено с выездом" },
    ) },
) : SimpleOneApi {
    var detailCalls = 0
    var currentUserCalls = 0
    var activeCalls = 0
    var archiveCalls = 0
    var exportCalls = 0
    var lastUpdatedSince: String? = null
    var syncCalls = 0
    val headCalls = mutableListOf<ClosedRequestsSyncScope>()

    override suspend fun closedSyncHead(userId: String, authKey: String, scope: ClosedRequestsSyncScope): ClosedRequestsSyncCursor? {
        headCalls += scope
        beforeHead()
        return closedHeads[scope]
    }

    override suspend fun closedSyncChanges(userId: String, authKey: String, scope: ClosedRequestsSyncScope,
        since: ClosedRequestsSyncCursor?, knownVersions: Map<String, String>, onProgress: (String) -> Unit): ClosedRequestsSyncBatch {
        beforeSync()
        syncCalls++
        lastUpdatedSince = since?.updatedAt
        val listed = closedRequests(userId, authKey)
        val changes = listed.filter { it.sysUpdatedAt.isBlank() || knownVersions[it.sysId] != it.sysUpdatedAt }.map { listedRecord ->
            val complete = if (listedRecord.requestType.isBlank()) {
                requestDetails(listedRecord.copy(source = ru.lumastack.lumawork.domain.simpleone.SimpleOneRequestSource.Closed), authKey).mergeMissing(listedRecord)
                    .also { if (it.requestType.isBlank()) throw SimpleOneFailure.InvalidResponse }
            } else listedRecord
            ClosedRequestsSyncChange(complete.sysId, complete.number, ClosedRequestsSyncCursor(complete.sysUpdatedAt, complete.sysId), complete)
        }
        val head = listed.filter { closedSyncTimestamp(it.sysUpdatedAt) != null }
            .map { ClosedRequestsSyncCursor(it.sysUpdatedAt, it.sysId) }.maxOrNull()
        return ClosedRequestsSyncBatch(changes, head ?: since)
    }

    override suspend fun closedRequestsSince(userId: String, authKey: String, updatedSince: String?): List<SimpleOneRequest> {
        lastUpdatedSince = updatedSince
        return closedRequests(userId, authKey)
    }

    override suspend fun login(username: String, password: String): String { beforeLogin(); return "new-auth" }

    override suspend fun currentUser(authKey: String): SimpleOneUser {
        currentUserCalls++
        error?.let { throw it }
        return SimpleOneUser("simple-user", "engineer", "Иван", "Инженеров")
    }

    override suspend fun activeRequests(userId: String, authKey: String): List<SimpleOneRequest> {
        activeCalls++
        beforeActive()
        error?.let { throw it }
        return active()
    }

    override suspend fun closedRequests(userId: String, authKey: String): List<SimpleOneRequest> {
        archiveCalls++
        error?.let { throw it }
        return closed
    }

    override suspend fun exportClosedRequests(userId: String, authKey: String): ByteArray {
        exportCalls++
        beforeExport()
        error?.let { throw it }
        return exportedXlsx ?: throw SimpleOneFailure.Http(501, "XLSX недоступен")
    }

    override suspend fun timeReportEntries(authKey: String): List<TimeReportEntry> = emptyList()

    override suspend fun exportTimeReport(userId: String, authKey: String): ByteArray =
        throw SimpleOneFailure.Http(501, "XLSX недоступен")

    override suspend fun backpackItems(authKey: String): List<BackpackItem> = emptyList()

    override suspend fun terminalHistory(terminalId: String, authKey: String): List<SimpleOneRequest> {
        beforeHistory()
        error?.let { throw it }
        return closed
    }

    override suspend fun requestDetails(request: SimpleOneRequest, authKey: String): SimpleOneRequest {
        detailCalls += 1
        error?.let { throw it }
        return if (request.source == ru.lumastack.lumawork.domain.simpleone.SimpleOneRequestSource.Closed) closedDetail(request) else activeDetail(request)
    }

    override suspend fun coordinationRequests(
        region: CoordinationRegion,
        authKey: String,
    ): List<SimpleOneRequest> = emptyList()

    override suspend fun returnEquipmentRequests(
        userId: String,
        authKey: String,
    ): List<SimpleOneRequest> = emptyList()
}

private class FakeSimpleOneSessionStore : SimpleOneSessionStore {
    private var value: StoredSimpleOneSession? = null
    private val usernames = mutableMapOf<String, String>()

    override fun read(ownerUserId: String): StoredSimpleOneSession? = value?.takeIf { it.ownerUserId == ownerUserId }

    override fun save(session: StoredSimpleOneSession) {
        value = session
        saveUsername(session.ownerUserId, session.username)
    }

    override fun clear(ownerUserId: String) {
        if (value?.ownerUserId == ownerUserId) value = null
    }

    override fun readUsername(ownerUserId: String): String = usernames[ownerUserId].orEmpty()

    override fun saveUsername(ownerUserId: String, username: String) {
        usernames[ownerUserId] = username
    }
}

private class FakeSimpleOneCache : SimpleOneCache {
    val values = mutableMapOf<String, SimpleOneSnapshot>()

    override suspend fun read(ownerUserId: String): SimpleOneSnapshot? = values[ownerUserId]

    override suspend fun save(ownerUserId: String, snapshot: SimpleOneSnapshot) {
        values[ownerUserId] = snapshot
    }
}

private class FakeSimpleOneSupplementalCache : SimpleOneSupplementalCache {
    val archives = mutableMapOf<String, ClosedSimpleOneArchive>()
    val details = mutableMapOf<Pair<String, String>, SimpleOneDetailCacheEntry>()

    override suspend fun readClosedArchive(ownerUserId: String): ClosedSimpleOneArchive? = archives[ownerUserId]

    override suspend fun saveClosedArchive(ownerUserId: String, archive: ClosedSimpleOneArchive) {
        archives[ownerUserId] = archive
    }

    override suspend fun readDetail(ownerUserId: String, requestId: String): SimpleOneDetailCacheEntry? =
        details[ownerUserId to requestId]

    override suspend fun saveDetail(ownerUserId: String, entry: SimpleOneDetailCacheEntry) {
        details[ownerUserId to entry.request.id] = entry
    }
}

private fun request() = SimpleOneRequest(
    sysId = "request-1",
    number = "REQ1",
    deadline = "2026-08-12 15:30:00",
)


@OptIn(ExperimentalCoroutinesApi::class)
class RequestsBootstrapTest {
    private val models = mutableListOf<SimpleOneViewModel>()
    private fun model(repository: SimpleOneRepository, offline: Boolean = false) =
        SimpleOneViewModel(repository, "owner", offline).also(models::add)
    private suspend fun settled(model: SimpleOneViewModel, complete: (SimpleOneUiState) -> Boolean = { true }) = withContext(Dispatchers.Default) {
        withTimeout(5_000) { model.state.first { !it.isInitializing && !it.isRestoringClosedArchive && !it.isRefreshingActive && !it.isClosedLoading && complete(it) } }
    }
    private fun session() = FakeSimpleOneSessionStore().apply { save(StoredSimpleOneSession("owner", "key", "engineer")) }

    @Test fun `explicit XLSX import is not dropped during an in flight archive refresh`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val started = CompletableDeferred<Unit>()
            val api = FakeSimpleOneApi(beforeSync = { started.complete(Unit); awaitCancellation() })
            val cache = FakeSimpleOneSupplementalCache().apply {
                archives["owner"] = ClosedSimpleOneArchive(listOf(request()), isComplete = true, queryRevision = 2,
                    syncRevision = 1, syncUserId = "simple-user", wideCursor = ClosedRequestsSyncCursor("2026-10-27 12:00:00", "request-1"))
            }
            val model = model(SimpleOneRepository(api, session(), FakeSimpleOneCache(), cache))
            settled(model)
            model.refreshClosedRequests()
            withContext(Dispatchers.Default) { withTimeout(5_000) { started.await() } }
            model.importArchive(makeSimpleOneXlsx(listOf("Номер заявки", "sys_id"), listOf(listOf("REQ-IMPORTED", "imported"))))
            settled(model) { it.closedArchive.requests.any { request -> request.number == "REQ-IMPORTED" } }
            assertEquals(setOf("REQ1", "REQ-IMPORTED"), model.state.value.closedArchive.requests.map { it.number }.toSet())
        } finally { models.forEach { it.viewModelScope.coroutineContext[Job]?.cancelAndJoin() }; models.clear(); Dispatchers.resetMain() }
    }

    @Test fun `unchanged disk archive refresh keeps prepared index and analytics`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val record = request().copy(requestType = "returnEquip", registeredAt = "2026-10-27 10:00:00", sysUpdatedAt = "2026-10-28 12:00:00",
                source = ru.lumastack.lumawork.domain.simpleone.SimpleOneRequestSource.Closed)
            val directory = java.nio.file.Files.createTempDirectory("closed-refresh-tests").toFile()
            try {
                val cache = FileSimpleOneSupplementalCache(directory, kotlinx.serialization.json.Json)
                cache.saveClosedArchive("owner", ClosedSimpleOneArchive(listOf(record), isComplete = true, queryRevision = 2, wideCursor = ClosedRequestsSyncCursor("2026-10-27 12:00:00", "request-1")))
                val model = model(SimpleOneRepository(FakeSimpleOneApi(closed = listOf(record)), session(), FakeSimpleOneCache(), cache))
                settled(model)
                val items = model.state.value.closedArchiveItems
                val analytics = model.state.value.closedAnalytics
                model.refreshClosedRequests()
                settled(model)
                org.junit.Assert.assertSame(items, model.state.value.closedArchiveItems)
                org.junit.Assert.assertSame(analytics, model.state.value.closedAnalytics)
            } finally { directory.deleteRecursively() }
        } finally { models.forEach { it.viewModelScope.coroutineContext[Job]?.cancelAndJoin() }; models.clear(); Dispatchers.resetMain() }
    }

    @Test fun `stored session and active snapshot are visible before slow closed archive restore`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val storedSession = session()
            val snapshot = SimpleOneSnapshot(activeRequests = listOf(request()),
                sessionFingerprint = simpleOneSessionFingerprint(requireNotNull(storedSession.read("owner"))))
            val activeCache = FakeSimpleOneCache().apply { values["owner"] = snapshot }
            val archiveReadStarted = CompletableDeferred<Unit>()
            val archiveResult = CompletableDeferred<ClosedSimpleOneArchive?>()
            val archiveCache = object : SimpleOneSupplementalCache by FakeSimpleOneSupplementalCache() {
                override suspend fun readClosedArchive(ownerUserId: String): ClosedSimpleOneArchive? {
                    archiveReadStarted.complete(Unit)
                    return archiveResult.await()
                }
            }
            val api = FakeSimpleOneApi()
            val model = model(SimpleOneRepository(api, storedSession, activeCache, archiveCache))
            archiveReadStarted.await()

            assertFalse(model.state.value.isInitializing)
            assertTrue(model.state.value.isAuthorized)
            assertEquals("engineer", model.state.value.username)
            assertEquals(snapshot, model.state.value.snapshot)
            assertEquals(0, api.currentUserCalls + api.activeCalls + api.archiveCalls + api.exportCalls)

            model.refreshActiveRequests()
            withContext(Dispatchers.Default) { withTimeout(5_000) { model.state.first { !it.isRefreshingActive && api.activeCalls == 1 } } }
            assertTrue(model.state.value.isRestoringClosedArchive)
            assertEquals(0, api.archiveCalls + api.exportCalls)
            archiveResult.complete(null)
            settled(model)
            assertTrue(model.state.value.isAuthorized)
        } finally { models.forEach { it.viewModelScope.coroutineContext[Job]?.cancelAndJoin() }; models.clear(); Dispatchers.resetMain() }
    }

    @Test fun `signout during archive restore cannot restore authorization or restart loading`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val archiveReadStarted = CompletableDeferred<Unit>()
            val archiveResult = CompletableDeferred<ClosedSimpleOneArchive?>()
            val cache = object : SimpleOneSupplementalCache by FakeSimpleOneSupplementalCache() {
                override suspend fun readClosedArchive(ownerUserId: String): ClosedSimpleOneArchive? {
                    archiveReadStarted.complete(Unit)
                    return archiveResult.await()
                }
            }
            val api = FakeSimpleOneApi()
            val model = model(SimpleOneRepository(api, session(), FakeSimpleOneCache(), cache))
            archiveReadStarted.await()
            model.signOut()
            archiveResult.complete(ClosedSimpleOneArchive(isComplete = true, queryRevision = 2))
            advanceUntilIdle()

            assertFalse(model.state.value.isInitializing)
            assertFalse(model.state.value.isAuthorized)
            assertFalse(model.state.value.initialArchiveReady)
            assertEquals(0, api.currentUserCalls + api.activeCalls + api.archiveCalls + api.exportCalls)
        } finally { models.forEach { it.viewModelScope.coroutineContext[Job]?.cancelAndJoin() }; models.clear(); Dispatchers.resetMain() }
    }

    @Test fun `first entry with remembered session only reads local cache`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val api = FakeSimpleOneApi()
            val model = model(SimpleOneRepository(api, session(), FakeSimpleOneCache()))
            settled(model)
            assertFalse(model.state.value.isInitializing)
            assertFalse(model.state.value.initialArchiveReady)
            assertEquals(0, api.currentUserCalls)
            assertEquals(0, api.activeCalls)
            assertEquals(0, api.archiveCalls)
            assertEquals(0, api.exportCalls)
        } finally { models.forEach { it.viewModelScope.coroutineContext[Job]?.cancelAndJoin() }; models.clear(); Dispatchers.resetMain() }
    }

    @Test fun `time report login resolves identity without downloading request archives`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val api = FakeSimpleOneApi()
            val model = model(SimpleOneRepository(api, FakeSimpleOneSessionStore(), FakeSimpleOneCache()))
            settled(model)
            model.updateUsername("engineer"); model.updatePassword("password")
            model.signInIdentityOnly()
            settled(model) { it.isAuthorized && it.snapshot.currentUser != null && !it.isResolvingUser }
            assertEquals(1, api.currentUserCalls)
            assertEquals(0, api.activeCalls + api.archiveCalls + api.exportCalls)
            assertFalse(model.state.value.initialArchiveReady)
        } finally { models.forEach { it.viewModelScope.coroutineContext[Job]?.cancelAndJoin() }; models.clear(); Dispatchers.resetMain() }
    }

    @Test fun `closed tab and detail return reuse ready local index without network or blank list`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val api = FakeSimpleOneApi()
            val archive = ClosedSimpleOneArchive(listOf(request().copy(resolvedAt = "2026-10-18 12:00:00")),
                isComplete = true, queryRevision = 2)
            val cache = FakeSimpleOneSupplementalCache().apply { archives["owner"] = archive }
            val model = model(SimpleOneRepository(api, session(), FakeSimpleOneCache(), cache), offline = true)
            settled(model)
            val prepared = model.state.value.closedArchiveItems
            assertEquals(1, prepared.size)
            assertEquals(archive.requests.single(), prepared.single().request)
            repeat(3) {
                model.updateMode(ru.lumastack.lumawork.domain.simpleone.RequestsMode.Closed)
                org.junit.Assert.assertSame(prepared, model.state.value.closedArchiveItems)
                model.openRequest(archive.requests.single()); model.closeRequest()
                org.junit.Assert.assertSame(prepared, model.state.value.closedArchiveItems)
                model.updateMode(ru.lumastack.lumawork.domain.simpleone.RequestsMode.Active)
            }
            assertEquals(0, api.currentUserCalls + api.activeCalls + api.archiveCalls + api.exportCalls)
        } finally { models.forEach { it.viewModelScope.coroutineContext[Job]?.cancelAndJoin() }; models.clear(); Dispatchers.resetMain() }
    }

    @Test fun `failed manual archive refresh preserves ready cached rows`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val api = FakeSimpleOneApi(error = SimpleOneFailure.Http(500, "Unavailable"))
            val archive = ClosedSimpleOneArchive(listOf(request()), isComplete = true, queryRevision = 2)
            val cache = FakeSimpleOneSupplementalCache().apply { archives["owner"] = archive }
            val model = model(SimpleOneRepository(api, session(), FakeSimpleOneCache(), cache))
            settled(model)
            val prepared = model.state.value.closedArchiveItems
            val analytics = model.state.value.closedAnalytics
            assertEquals(1, prepared.size)
            model.refreshClosedRequests()
            assertTrue(model.state.value.isClosedLoading)
            org.junit.Assert.assertSame(prepared, model.state.value.closedArchiveItems)
            settled(model) { it.closedErrorMessage != null }
            assertNull(model.state.value.errorMessage)
            val closedError = model.state.value.closedErrorMessage
            model.refreshActiveRequests()
            settled(model) { it.errorMessage != null }
            assertEquals(closedError, model.state.value.closedErrorMessage)
            org.junit.Assert.assertSame(prepared, model.state.value.closedArchiveItems)
            assertEquals(archive, model.state.value.closedArchive)
            org.junit.Assert.assertSame(analytics, model.state.value.closedAnalytics)
        } finally { models.forEach { it.viewModelScope.coroutineContext[Job]?.cancelAndJoin() }; models.clear(); Dispatchers.resetMain() }
    }

    @Test fun `local archive mutation replaces index and cleanup removes stale cached rows`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val api = FakeSimpleOneApi()
            val cache = FakeSimpleOneSupplementalCache().apply {
                archives["owner"] = ClosedSimpleOneArchive(listOf(request()), isComplete = true, queryRevision = 2)
            }
            val model = model(SimpleOneRepository(api, session(), FakeSimpleOneCache(), cache), offline = true)
            settled(model)
            val previous = model.state.value.closedArchiveItems
            val xlsx = makeSimpleOneXlsx(listOf("Номер заявки", "sys_id"), listOf(listOf("REQ2", "request-2")))
            model.importArchive(xlsx)
            settled(model) { it.closedArchive.requests.size == 2 }
            assertEquals(setOf("request-1", "request-2"), model.state.value.closedArchiveItems.map { it.request.id }.toSet())
            org.junit.Assert.assertNotSame(previous, model.state.value.closedArchiveItems)
            model.cleanupArchive(null)
            settled(model) { it.closedArchive.requests.isEmpty() }
            assertTrue(model.state.value.closedArchiveItems.isEmpty())
            assertEquals(0, api.currentUserCalls + api.activeCalls + api.archiveCalls + api.exportCalls)
        } finally { models.forEach { it.viewModelScope.coroutineContext[Job]?.cancelAndJoin() }; models.clear(); Dispatchers.resetMain() }
    }

    @Test fun `analytics restores and reuses local projection and cleanup removes its statistics`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val api = FakeSimpleOneApi()
            val cache = FakeSimpleOneSupplementalCache().apply {
                archives["owner"] = ClosedSimpleOneArchive(listOf(request().copy(resolvedAt = "2026-10-15 12:00:00",
                    closureCode = "Решено с выездом", requestType = "install")), isComplete = true, queryRevision = 2)
            }
            val model = model(SimpleOneRepository(api, session(), FakeSimpleOneCache(), cache), offline = true)
            settled(model)
            val analytics = model.state.value.closedAnalytics
            assertEquals(1, analytics.completedCount)
            assertEquals("2026-10", analytics.latestMonth!!.id)
            repeat(3) {
                model.updateMode(ru.lumastack.lumawork.domain.simpleone.RequestsMode.Closed)
                model.openRequest(model.state.value.closedArchive.requests.single()); model.closeRequest()
                model.updateSearchQuery("test")
                org.junit.Assert.assertSame(analytics, model.state.value.closedAnalytics)
            }
            model.cleanupArchive(null)
            settled(model) { it.closedArchive.requests.isEmpty() }
            assertEquals(0, model.state.value.closedAnalytics.completedCount)
            assertTrue(model.state.value.closedAnalytics.months.isEmpty())
            assertEquals(0, api.currentUserCalls + api.activeCalls + api.archiveCalls + api.exportCalls)
        } finally { models.forEach { it.viewModelScope.coroutineContext[Job]?.cancelAndJoin() }; models.clear(); Dispatchers.resetMain() }
    }

    @Test fun `analytics refresh downloads closed archive without refreshing active requests`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val done = request().copy(resolvedAt = "2026-10-15 12:00:00", closureCode = "Решено с выездом")
            val api = FakeSimpleOneApi(closed = listOf(done))
            val cache = FakeSimpleOneSupplementalCache().apply {
                archives["owner"] = ClosedSimpleOneArchive(listOf(done), isComplete = true, queryRevision = 2)
            }
            val model = model(SimpleOneRepository(api, session(), FakeSimpleOneCache(), cache))
            settled(model)
            val activeCalls = api.activeCalls
            model.refreshAnalytics()
            settled(model) { api.archiveCalls > 0 }
            assertEquals(activeCalls, api.activeCalls)
            assertEquals(1, model.state.value.closedAnalytics.completedCount)
        } finally { models.forEach { it.viewModelScope.coroutineContext[Job]?.cancelAndJoin() }; models.clear(); Dispatchers.resetMain() }
    }

    @Test fun `explicit initial load refreshes active without downloading closed baseline`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val api = FakeSimpleOneApi(closed = listOf(request()))
            val model = model(SimpleOneRepository(api, session(), FakeSimpleOneCache(), FakeSimpleOneSupplementalCache()))
            settled(model)
            model.initialLoad(); model.initialLoad()
            assertTrue(model.state.value.isLoading)
            settled(model) { it.activeRequests.isNotEmpty() }
            assertEquals(0, api.exportCalls + api.archiveCalls)
            assertEquals(1, api.activeCalls)
            assertFalse(model.state.value.initialArchiveReady)
            model.updateMode(ru.lumastack.lumawork.domain.simpleone.RequestsMode.Closed)
            assertEquals(ru.lumastack.lumawork.domain.simpleone.RequestsMode.Closed, model.state.value.mode)
            model.refreshClosedRequests()
            settled(model) { it.initialArchiveReady }
            assertEquals(1, api.exportCalls)
            assertEquals(1, api.archiveCalls)
            assertEquals(1, api.activeCalls)
        } finally { models.forEach { it.viewModelScope.coroutineContext[Job]?.cancelAndJoin() }; models.clear(); Dispatchers.resetMain() }
    }

    @Test fun `late opened old details cannot replace newly refreshed card or selection`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val release = CompletableDeferred<Unit>()
        try {
            val started = CompletableDeferred<Unit>()
            val old = request().copy(sysUpdatedAt = "2026-10-28 12:00:00", description = "old list")
            val fresh = old.copy(sysUpdatedAt = "2026-10-29 12:00:00", description = "fresh list")
            val api = FakeSimpleOneApi(active = { listOf(fresh) }, activeDetail = {
                if (it.sysUpdatedAt == old.sysUpdatedAt) { started.complete(Unit); release.await(); it.copy(description = "old details") }
                else it.copy(description = "fresh details")
            })
            val sessions = session()
            val snapshot = SimpleOneSnapshot(currentUser = SimpleOneUser("simple-user", "engineer", "Иван", "Инженеров"),
                activeRequests = listOf(old), sessionFingerprint = simpleOneSessionFingerprint(sessions.read("owner")!!))
            val model = model(SimpleOneRepository(api, sessions, FakeSimpleOneCache().apply { values["owner"] = snapshot }))
            settled(model)
            model.openRequest(old)
            started.await()
            model.refreshActiveRequests()
            settled(model) { it.activeRequests.single().description == "fresh details" }
            release.complete(Unit)
            withContext(Dispatchers.Default) { withTimeout(5_000) { model.state.first { !it.isDetailLoading } } }
            assertEquals("fresh details", model.state.value.activeRequests.single().description)
            assertEquals("fresh details", model.state.value.selectedRequest?.description)
        } finally { release.complete(Unit); models.forEach { it.viewModelScope.coroutineContext[Job]?.cancelAndJoin() }; models.clear(); Dispatchers.resetMain() }
    }

    @Test fun `concurrent archive authorization failure releases active loading flags`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val release = CompletableDeferred<Unit>()
        try {
            val sessions = session()
            val started = CompletableDeferred<Unit>()
            val api = FakeSimpleOneApi(beforeActive = { started.complete(Unit); release.await() },
                beforeSync = { throw SimpleOneFailure.Unauthorized })
            val cache = FakeSimpleOneSupplementalCache().apply {
                archives["owner"] = ClosedSimpleOneArchive(listOf(request()), isComplete = true, queryRevision = 2,
                    syncRevision = 1, syncUserId = "simple-user", wideCursor = ClosedRequestsSyncCursor("2026-10-27 12:00:00", "request-1"))
            }
            val model = model(SimpleOneRepository(api, sessions, FakeSimpleOneCache(), cache))
            settled(model)
            model.refreshActiveRequests()
            started.await()
            model.refreshClosedRequests()
            withContext(Dispatchers.Default) { withTimeout(5_000) { model.state.first { !it.isAuthorized } } }
            release.complete(Unit)
            settled(model)
            assertFalse(model.state.value.isLoading)
            assertTrue(model.state.value.hydratingRequestIds.isEmpty())
        } finally { release.complete(Unit); models.forEach { it.viewModelScope.coroutineContext[Job]?.cancelAndJoin() }; models.clear(); Dispatchers.resetMain() }
    }

    @Test fun `ready archive restore and five tab switches do not start any network work`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val api = FakeSimpleOneApi()
            val cache = FakeSimpleOneSupplementalCache().apply {
                archives["owner"] = ClosedSimpleOneArchive(listOf(request()), isComplete = true, queryRevision = 2)
            }
            val model = model(SimpleOneRepository(api, session(), FakeSimpleOneCache(), cache))
            settled(model)
            repeat(5) {
                model.updateMode(ru.lumastack.lumawork.domain.simpleone.RequestsMode.Closed)
                model.updateMode(ru.lumastack.lumawork.domain.simpleone.RequestsMode.Active)
            }
            assertEquals(0, api.currentUserCalls + api.activeCalls + api.archiveCalls + api.exportCalls)
        } finally { models.forEach { it.viewModelScope.coroutineContext[Job]?.cancelAndJoin() }; models.clear(); Dispatchers.resetMain() }
    }

    @Test fun `active refresh completes while manual closed sync continues across tab switches`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val api = FakeSimpleOneApi(beforeSync = { started.complete(Unit); release.await() })
            val cache = FakeSimpleOneSupplementalCache().apply {
                archives["owner"] = ClosedSimpleOneArchive(listOf(request()), isComplete = true, queryRevision = 2,
                    syncRevision = 1, syncUserId = "simple-user", wideCursor = ClosedRequestsSyncCursor("2026-10-27 12:00:00", "request-1"))
            }
            val model = model(SimpleOneRepository(api, session(), FakeSimpleOneCache(), cache))
            settled(model)
            model.refreshClosedRequests()
            started.await()
            model.updateMode(ru.lumastack.lumawork.domain.simpleone.RequestsMode.Closed)
            model.updateMode(ru.lumastack.lumawork.domain.simpleone.RequestsMode.Active)
            model.refreshActiveRequests()
            withContext(Dispatchers.Default) { withTimeout(5_000) { model.state.first { !it.isLoading && it.activeRequests.isNotEmpty() } } }
            assertTrue(model.state.value.isClosedLoading)
            assertEquals(1, api.activeCalls)
            release.complete(Unit)
            settled(model)
            assertEquals(1, api.archiveCalls)
        } finally { models.forEach { it.viewModelScope.coroutineContext[Job]?.cancelAndJoin() }; models.clear(); Dispatchers.resetMain() }
    }

    @Test fun `login starts only after button and active failure does not start an archive baseline`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val api = FakeSimpleOneApi(error = SimpleOneFailure.Http(500, "Unavailable"))
            val model = model(SimpleOneRepository(api, FakeSimpleOneSessionStore(), FakeSimpleOneCache()))
            settled(model)
            assertFalse(model.state.value.showConnection)
            model.initialLoad()
            assertTrue(model.state.value.showConnection)
            model.updateUsername("engineer"); model.updatePassword("password"); model.signIn()
            settled(model) { it.errorMessage != null }
            assertFalse(model.state.value.initialArchiveReady)
            assertFalse(model.state.value.isLoading)
            assertFalse(model.state.value.isClosedLoading)
            assertEquals(0, api.activeCalls)
            assertTrue(model.state.value.errorMessage != null)
        } finally { models.forEach { it.viewModelScope.coroutineContext[Job]?.cancelAndJoin() }; models.clear(); Dispatchers.resetMain() }
    }

    @Test fun `trusted complete empty archive unlocks repeat entry offline without network`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val api = FakeSimpleOneApi()
            val cache = FakeSimpleOneSupplementalCache().apply { archives["owner"] = ClosedSimpleOneArchive(isComplete = true, queryRevision = 2) }
            val model = model(SimpleOneRepository(api, session(), FakeSimpleOneCache(), cache), offline = true)
            settled(model)
            assertTrue(model.state.value.initialArchiveReady)
            model.initialLoad(); model.refreshClosedRequests()
            settled(model)
            assertEquals(0, api.activeCalls + api.exportCalls + api.currentUserCalls)
        } finally { models.forEach { it.viewModelScope.coroutineContext[Job]?.cancelAndJoin() }; models.clear(); Dispatchers.resetMain() }
    }

    @Test fun `old capped archive is not accepted as a complete baseline`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val api = FakeSimpleOneApi()
            val cache = FakeSimpleOneSupplementalCache().apply { archives["owner"] = ClosedSimpleOneArchive(listOf(request()), isComplete = true) }
            val model = model(SimpleOneRepository(api, session(), FakeSimpleOneCache(), cache))
            settled(model)
            assertFalse(model.state.value.initialArchiveReady)
            assertEquals(0, api.activeCalls + api.exportCalls + api.currentUserCalls)
        } finally { models.forEach { it.viewModelScope.coroutineContext[Job]?.cancelAndJoin() }; models.clear(); Dispatchers.resetMain() }
    }

    @Test fun `local import export and inclusive cleanup preserve undated requests and never call API`() = runBlocking {
        val api = FakeSimpleOneApi()
        val repo = SimpleOneRepository(api, FakeSimpleOneSessionStore(), FakeSimpleOneCache(), FakeSimpleOneSupplementalCache())
        val records = listOf(request().copy(number = "OLD", resolvedAt = "12.10.2026 12:00"),
            request().copy(sysId = "2", number = "KEEP", resolvedAt = "17.10.2026 12:00"),
            request().copy(sysId = "3", number = "UNDATED", resolvedAt = ""))
        val imported = repo.importClosedArchive("owner", ClosedRequestsXlsxExport.create(records))
        assertTrue(imported.isReady)
        val roundtrip = SimpleOneXlsxParser.parse(repo.exportClosedArchive("owner"))
        assertEquals(records.map { it.number }.toSet(), roundtrip.map { it.number }.toSet())
        val period = ClosedRequestsPeriod(LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 12))
        val cleaned = repo.cleanupClosedArchive("owner") { parseClosedRequestsDate(it.closedEffectiveTime())?.toLocalDate()?.let { day -> day in period } == true }
        assertEquals(listOf("KEEP", "UNDATED"), cleaned.requests.map { it.number })
        assertTrue(repo.cleanupClosedArchive("owner") { true }.isReady)
        assertEquals(0, api.activeCalls + api.exportCalls + api.currentUserCalls + api.archiveCalls)
        assertNull(repo.readClosedArchive("other-owner"))
    }
}


class TerminalHistoryRepositoryTest {
    @Test fun `history persists per owner session and terminal and a failure preserves cache`() = runBlocking {
        val directory = java.nio.file.Files.createTempDirectory("terminal-history-tests").toFile()
        try {
            val session = StoredSimpleOneSession("owner", "auth", "engineer")
            val sessions = FakeSimpleOneSessionStore().apply { save(session) }
            val cachedFile = FileSimpleOneSupplementalCache(directory, kotlinx.serialization.json.Json)
            val history = listOf(request().copy(terminalId = "00123"))
            val repository = SimpleOneRepository(FakeSimpleOneApi(closed = history), sessions, FakeSimpleOneCache(), cachedFile)
            assertEquals(history, repository.terminalHistory("owner", "00123"))
            val restored = SimpleOneRepository(FakeSimpleOneApi(error = SimpleOneFailure.InvalidResponse), sessions, FakeSimpleOneCache(),
                FileSimpleOneSupplementalCache(directory, kotlinx.serialization.json.Json))
            assertEquals(history, restored.cachedTerminalHistory("owner", "00123"))
            assertTrue(runCatching { restored.terminalHistory("owner", "00123") }.isFailure)
            assertEquals(history, restored.cachedTerminalHistory("owner", "00123"))
            assertNull(restored.cachedTerminalHistory("owner", "DIFFERENT"))
            assertNull(restored.cachedTerminalHistory("other-owner", "00123"))
            assertNull(restored.readClosedArchive("owner"))
            sessions.save(session.copy(authKey = "new-session"))
            assertNull(restored.cachedTerminalHistory("owner", "00123"))
        } finally { directory.deleteRecursively() }
    }

    @Test fun `late history response after session replacement is not saved`() = runBlocking {
        val session = StoredSimpleOneSession("owner", "auth", "engineer")
        val sessions = FakeSimpleOneSessionStore().apply { save(session) }
        val supplemental = FakeSimpleOneSupplementalCache()
        val api = FakeSimpleOneApi(closed = listOf(request().copy(terminalId = "00123")),
            beforeHistory = { sessions.save(session.copy(authKey = "new-auth")) })
        val repository = SimpleOneRepository(api, sessions, FakeSimpleOneCache(), supplemental)
        val error = runCatching { repository.terminalHistory("owner", "00123") }.exceptionOrNull()
        assertTrue(error is kotlinx.coroutines.CancellationException)
        sessions.save(session)
        assertNull(repository.cachedTerminalHistory("owner", "00123"))
    }

    @Test fun `unauthorized history clears only its still current session`() = runBlocking {
        val sessions = FakeSimpleOneSessionStore().apply { save(StoredSimpleOneSession("owner", "auth", "engineer")) }
        val repository = SimpleOneRepository(FakeSimpleOneApi(error = SimpleOneFailure.Unauthorized), sessions, FakeSimpleOneCache())
        assertTrue(runCatching { repository.terminalHistory("owner", "00123") }.exceptionOrNull() is SimpleOneFailure.Unauthorized)
        assertNull(sessions.read("owner"))
    }
}
