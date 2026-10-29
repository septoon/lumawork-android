package ru.lumastack.lumawork.data.simpleone

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import ru.lumastack.lumawork.domain.requests.buildClosedRequestsAnalytics
import ru.lumastack.lumawork.domain.requests.prepareClosedRequests
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.Rule
import ru.lumastack.lumawork.domain.simpleone.*

class ClosedRequestsArchiveTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun `incremental scan includes entire boundary second and stops before old pages`() = runBlocking {
        var calls = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            calls++
            if (chain.request().url.encodedPath.contains("list-cell-value")) {
                val version = if (chain.request().url.pathSegments.last() == "old") "2026-10-27 12:00:00" else "2026-10-28 12:00:00"
                return@addInterceptor Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body("""{"status":"OK","data":{"value":"$version"}}""".toResponseBody()).build()
            }
            assertEquals("wide^ORDERBYDESCsys_updated_at^ORDERBYDESCsys_id", chain.request().url.queryParameter("condition"))
            val page = chain.request().url.queryParameter("page")!!.toInt()
            // Equal timestamp rows must also be in descending sys_id order.
            val rows = if (page == 1) (20 downTo 1).joinToString(",") { syncItem(it.toString().padStart(3, '0')) }
                else listOf(syncItem("000"), syncItem("old")).joinToString(",")
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"status":"OK","data":{"list":[$rows],"total_count":5000}}""".toResponseBody()).build()
        }.build()
        val records = OkHttpSimpleOneApi("https://sd.test/v1", client, closedArchiveCondition = { "wide" })
            .closedRequestsSince("user", "auth", "2026-10-28 12:00:00")
        assertEquals(21, records.size)
        assertEquals(26, calls) // two pages + 22 cells, followed by the head page and cell
        assertTrue(records.none { it.sysId == "old" })
    }

    @Test fun `incremental scan rejects missing versions instead of silently skipping changes`() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"status":"OK","data":{"list":[{"sys_id":"one","number":"REQ1"}]}}""".toResponseBody()).build()
        }.build()
        val api = OkHttpSimpleOneApi("https://sd.test/v1", client, closedArchiveCondition = { "wide" })
        assertTrue(runCatching { api.closedRequestsSince("user", "auth", "2026-10-28 12:00:00") }.exceptionOrNull() is SimpleOneFailure.InvalidResponse)
    }

    @Test fun `list fallback requests closure fields and supplies completed analytics`() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val columns = chain.request().url.queryParameter("columns").orEmpty().split(",")
            val closure = if ("new_closure_code" in columns) ",\"new_closure_code\":\"Решено с выездом\"" else ""
            val resolution = if ("closure_notes" in columns) ",\"closure_notes\":\"Терминал заменён\"" else ""
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"status":"OK","data":{"list":[{"sys_id":"done","number":"REQ-DONE","state":"closed","multicard_request_type":"replacement","resolved_at":"2026-10-15 12:00:00"$closure$resolution}]}}""".toResponseBody()).build()
        }.build()
        val records = OkHttpSimpleOneApi("https://sd.test/v1", client, closedArchiveCondition = { "wide" }).closedRequests("user", "auth")
        val analytics = buildClosedRequestsAnalytics(prepareClosedRequests(records))
        assertEquals(1, analytics.completedCount)
        assertEquals(listOf("2026-10"), analytics.months.map { it.id })
        assertEquals("Терминал заменён", records.single().resolution)
    }

    @Test fun `archive query matches wide iOS contract without a relative date window`() {
        val condition = ClosedRequestsArchiveQuery("111", "222", "333").condition()
        assertEquals("((multicard_engineerDYNAMIC111^ORassigned_userDYNAMIC222^ORengineer_schedule.employeeDYNAMIC111)^resolved_atNOTONopt:333^stateNOT INon_hold@assigned@in_progress@escalated@returned_to_work@3@6@update_received)", condition)
        assertTrue(runCatching { ClosedRequestsArchiveQuery("", "222", "333").condition() }.isFailure)
    }

    @Test fun `archive query keeps one outer group and balanced assignment subgroup`() {
        val condition = ClosedRequestsArchiveQuery("111", "222", "333").condition()
        var depth = 0
        for (character in condition) {
            if (character == '(') depth++
            if (character == ')') depth--
            assertTrue("Unmatched closing parenthesis in archive condition", depth >= 0)
        }
        assertEquals("Outer archive condition must be balanced", 0, depth)
        val assignmentEnd = condition.indexOf("^resolved_at")
        val prefix = condition.substring(0, assignmentEnd)
        assertEquals("Date and state filters belong inside the outer condition", 1, prefix.count { it == '(' } - prefix.count { it == ')' })
    }
    @Test fun `closed pagination reads beyond fifty pages without totalCount`() = runBlocking {
        var calls = 0
        val client = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val page = chain.request().url.queryParameter("page")!!.toInt()
            calls++
            val rows = if (page <= 51) (1..100).map { index -> val id = (page - 1) * 100 + index; """{"sys_id":"$id","number":"REQ$id"}""" } else emptyList()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"status":"OK","data":{"list":[${rows.joinToString(",")}]}}""".toResponseBody()).build()
        }).build()
        val records = OkHttpSimpleOneApi("https://sd.test/v1", client, closedArchiveCondition = { "wide" }).closedRequests("user", "auth")
        assertEquals(5100, records.size)
        assertEquals(52, calls)
    }

    @Test fun `repeated closed pages fail instead of marking partial data complete`() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val rows = (1..100).joinToString(",") { """{"sys_id":"$it","number":"REQ$it"}""" }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"status":"OK","data":{"list":[$rows]}}""".toResponseBody()).build()
        }).build()
        assertTrue(runCatching { OkHttpSimpleOneApi("https://sd.test/v1", client, closedArchiveCondition = { "wide" }).closedRequests("user", "auth") }.isFailure)
    }

    @Test fun `file archive and XLSX retain more than five thousand rows and leading zeroes`() = runBlocking {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val requests = (1..5001).map { SimpleOneRequest("$it", "REQ$it", terminalId = "000123", customer = "=not a formula & <text>",
            tableFields = mapOf("Дополнительная колонка" to "000045")) }
        val archive = ClosedSimpleOneArchive(requests, isComplete = true, queryRevision = 2,
            syncUpdatedAt = "2026-10-28 12:00:00", syncUserId = "simple-user", syncRevision = 1,
            monthlyCursor = ClosedRequestsSyncCursor("2026-10-28 12:00:00", "1"),
            wideCursor = ClosedRequestsSyncCursor("2026-10-27 12:00:00", "2"),
            syncRecords = mapOf("1" to ClosedRequestsSyncRecord("REQ1", "2026-10-28 12:00:00")))
        val cache = FileSimpleOneSupplementalCache(temporary.newFolder(), json)
        cache.saveClosedArchive("owner", archive)
        val restored = cache.readClosedArchive("owner")!!
        assertEquals(5001, restored.requests.size)
        assertEquals("2026-10-28 12:00:00", restored.syncUpdatedAt)
        assertEquals("simple-user", restored.syncUserId)
        assertEquals(archive.monthlyCursor, restored.monthlyCursor)
        assertEquals(archive.wideCursor, restored.wideCursor)
        assertEquals(archive.syncRecords, restored.syncRecords)
        assertNull(cache.readClosedArchive("other"))
        val parsed = SimpleOneXlsxParser.parse(ClosedRequestsXlsxExport.create(requests))
        assertEquals(5001, parsed.size)
        assertTrue(parsed.all { it.terminalId == "000123" && it.customer == "=not a formula & <text>" && it.tableFields["Дополнительная колонка"] == "000045" })
    }
}
