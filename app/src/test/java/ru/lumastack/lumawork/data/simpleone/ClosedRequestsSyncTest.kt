package ru.lumastack.lumawork.data.simpleone

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import ru.lumastack.lumawork.domain.simpleone.*
import okhttp3.HttpUrl.Companion.toHttpUrl

class ClosedRequestsSyncTest {
    @Test fun `serial missing from list and record is fetched from its cell`() = runBlocking {
        for (serial in listOf("00001234", "")) {
            val paths = mutableListOf<String>()
            val incomplete = Json.parseToJsonElement(syncItem("one")).jsonObject.toMutableMap()
                .apply { remove("pb_sn_pos_install") }
            val sync = SimpleOneClosedSyncClient("https://sd.test/v1", { url ->
                val path = url.toHttpUrl().encodedPath
                paths += path
                when {
                    path.endsWith("/list/itsm_request") -> buildJsonObject {
                        put("data", buildJsonObject { put("list", buildJsonArray { add(JsonObject(incomplete)) }) })
                    }
                    path.contains("/record/") -> buildJsonObject { put("data", JsonObject(incomplete)) }
                    path.endsWith("/pb_sn_pos_install/one") -> buildJsonObject {
                        put("data", buildJsonObject { put("read_access", true); put("value", serial) })
                    }
                    path.endsWith("/sys_updated_at/one") -> Json.parseToJsonElement("""{"data":{"value":"2026-10-28 12:00:00"}}""").jsonObject
                    else -> error("Unexpected request: $path")
                }
            }, { "wide" }, { "monthly" })
            val result = sync.changes("user", ClosedRequestsSyncScope.All, null, emptyMap()) {}
            assertEquals(serial, result.changes.single().request!!.tableFields["SN POS установка"])
            assertEquals(1, paths.count { it.endsWith("/pb_sn_pos_install/one") })
            assertEquals(ClosedRequestsSyncCursor("2026-10-28 12:00:00", "one"), result.cursor)
        }
    }

    @Test fun `unreadable serial cell cannot silently erase an archived value`() = runBlocking {
        var cellCalls = 0
        val incomplete = Json.parseToJsonElement(syncItem("one")).jsonObject.toMutableMap()
            .apply { remove("pb_sn_pos_install") }
        val sync = SimpleOneClosedSyncClient("https://sd.test/v1", { url ->
            val path = url.toHttpUrl().encodedPath
            when {
                path.endsWith("/list/itsm_request") -> buildJsonObject {
                    put("data", buildJsonObject { put("list", buildJsonArray { add(JsonObject(incomplete)) }) })
                }
                path.contains("/record/") -> buildJsonObject { put("data", JsonObject(incomplete)) }
                path.endsWith("/pb_sn_pos_install/one") -> {
                    cellCalls++
                    Json.parseToJsonElement("""{"data":{"read_access":false,"value":""}}""").jsonObject
                }
                path.endsWith("/sys_updated_at/one") -> Json.parseToJsonElement("""{"data":{"value":"2026-10-28 12:00:00"}}""").jsonObject
                else -> error("Unexpected request: $path")
            }
        }, { "wide" }, { "monthly" })
        val error = runCatching { sync.changes("user", ClosedRequestsSyncScope.All, null, emptyMap()) {} }.exceptionOrNull()
        assertTrue(error is SimpleOneFailure)
        assertEquals(1, cellCalls)
    }

    @Test fun `list fallback retains equipment serial columns in the canonical export row`() {
        val raw = Json.parseToJsonElement(syncItem("one")).jsonObject.toMutableMap()
            .apply { put("pb_sn_pos_install", JsonPrimitive("00001234")) }
        val record = parseSimpleOneRequest(JsonObject(raw), SimpleOneRequestSource.Closed)
        assertEquals("00001234", record.tableFields["SN POS установка"])
    }

    @Test fun `invalid nonempty merchant TIN is repaired from the detail card`() = runBlocking {
        var details = 0
        val bad = Json.parseToJsonElement(syncItem("one")).jsonObject.toMutableMap()
            .apply { put("multicard_merchant_tin", JsonPrimitive("123...")) }
        val sync = SimpleOneClosedSyncClient("https://sd.test/v1", { url ->
            val path = url.toHttpUrl().encodedPath
            when {
                path.endsWith("/list/itsm_request") -> buildJsonObject { put("data", buildJsonObject { put("list", buildJsonArray { add(JsonObject(bad)) }) }) }
                path.contains("/record/") -> { details++; buildJsonObject { put("data", Json.parseToJsonElement(syncItem("one"))) } }
                else -> Json.parseToJsonElement("""{"data":{"value":"2026-10-28 12:00:00"}}""").jsonObject
            }
        }, { "wide" }, { "monthly" })
        val request = sync.changes("user", ClosedRequestsSyncScope.All, null, emptyMap()) {}.changes.single().request!!
        assertEquals("1234567890", request.tableFields["ИНН ТСП"])
        assertEquals(1, details)
    }

    @Test fun `a changed head is rescanned before committing its new boundary`() = runBlocking {
        var pages = 0
        val sync = SimpleOneClosedSyncClient("https://sd.test/v1", { url ->
            val path = url.toHttpUrl().encodedPath
            val body = when {
                path.endsWith("/list/itsm_request") -> {
                    pages++
                    val rows = if (pages == 1) syncItem("a") else "${syncItem("b")},${syncItem("a")}"
                    """{"data":{"list":[$rows]}}"""
                }
                path.endsWith("/sys_updated_at/b") -> """{"data":{"value":"2026-10-28 12:01:00"}}"""
                else -> """{"data":{"value":"2026-10-28 12:00:00"}}"""
            }
            Json.parseToJsonElement(body).jsonObject
        }, { "wide" }, { "monthly" })
        val result = sync.changes("user", ClosedRequestsSyncScope.All, null, emptyMap()) {}
        assertEquals(listOf("REQ-b", "REQ-a"), result.changes.map { it.number })
        assertEquals(ClosedRequestsSyncCursor("2026-10-28 12:01:00", "b"), result.cursor)
        assertEquals(4, pages)
    }

    @Test fun `excluded rows retain their server version without entering the closed archive`() = runBlocking {
        val excluded = Json.parseToJsonElement(syncItem("cancelled")).jsonObject.toMutableMap()
            .apply { put("state", JsonPrimitive("cancelled")) }
        val sync = SimpleOneClosedSyncClient("https://sd.test/v1", { url ->
            if (url.toHttpUrl().encodedPath.endsWith("/list/itsm_request"))
                buildJsonObject { put("data", buildJsonObject { put("list", buildJsonArray { add(JsonObject(excluded)) }) }) }
            else Json.parseToJsonElement("""{"data":{"value":"2026-10-28 12:00:00"}}""").jsonObject
        }, { "wide" }, { "monthly" })
        val change = sync.changes("user", ClosedRequestsSyncScope.All, null, emptyMap()) {}.changes.single()
        assertNull(change.request)
        assertEquals("REQ-cancelled", change.number)
        assertEquals("2026-10-28 12:00:00", change.cursor.updatedAt)
    }

    @Test fun `monthly sync uses the same employee and month options as iOS without filtering the whole archive`() = runBlocking {
        var condition = ""
        val query = ClosedRequestsArchiveQuery("111", "222", "333", "444", "555")
        val sync = SimpleOneClosedSyncClient("https://sd.test/v1", { url ->
            condition = url.toHttpUrl().queryParameter("condition").orEmpty()
            Json.parseToJsonElement("""{"status":"OK","data":{"list":[]}}""").jsonObject
        }, { "wide" }, query::monthlyCondition)
        assertTrue(sync.changes("999", ClosedRequestsSyncScope.Monthly, null, emptyMap()) {}.changes.isEmpty())
        assertEquals("((multicard_engineer=999^ORassigned_user=999^ORengineer_schedule.employee=999)^(resolved_atONopt:444^ORresolved_atONopt:555))^ORDERBYDESCsys_updated_at^ORDERBYDESCsys_id", condition)
    }

    @Test fun `known versions stop at the boundary without fetching unchanged cards or older pages`() = runBlocking {
        val paths = mutableListOf<String>()
        val sync = SimpleOneClosedSyncClient("https://sd.test/v1", { url ->
            val path = url.toHttpUrl().encodedPath
            paths += path
            val body = when {
                path.endsWith("/list/itsm_request") -> """{"data":{"list":[${syncItem("z")},${syncItem("y")},${syncItem("x")}]}}"""
                path.endsWith("/sys_updated_at/x") -> """{"data":{"value":"2026-10-27 12:00:00"}}"""
                else -> """{"data":{"value":"2026-10-28 12:00:00"}}"""
            }
            Json.parseToJsonElement(body).jsonObject
        }, { "wide" }, { "monthly" })
        val batch = sync.changes("user", ClosedRequestsSyncScope.All, ClosedRequestsSyncCursor("2026-10-28 12:00:00", "z"),
            mapOf("z" to "2026-10-28 12:00:00", "y" to "2026-10-28 12:00:00")) {}
        assertTrue(batch.changes.isEmpty())
        assertEquals(4, paths.size)
        assertEquals(1, paths.count { it.endsWith("/list/itsm_request") })
    }

    @Test fun `missing export field is completed from record independently of user column preset`() = runBlocking {
        var detailCalls = 0
        val missing = Json.parseToJsonElement(syncItem("one")).jsonObject.toMutableMap().apply { remove("multicard_terminal_model") }
        val full = Json.parseToJsonElement(syncItem("one")).jsonObject.toMutableMap().apply { put("multicard_terminal_model", JsonPrimitive("PAX D270")) }
        val sync = SimpleOneClosedSyncClient("https://sd.test/v1", { url ->
            val path = url.toHttpUrl().encodedPath
            when {
                path.endsWith("/list/itsm_request") -> buildJsonObject { put("data", buildJsonObject { put("list", buildJsonArray { add(JsonObject(missing)) }) }) }
                path.contains("/record/") -> { detailCalls++; buildJsonObject { put("data", JsonObject(full)) } }
                else -> Json.parseToJsonElement("""{"data":{"value":"2026-10-28 12:00:00"}}""").jsonObject
            }
        }, { "wide" }, { "monthly" })
        val record = sync.changes("user", ClosedRequestsSyncScope.All, null, emptyMap()) {}.changes.single().request!!
        assertEquals("PAX D270", record.terminalModel)
        assertEquals("PAX D270", record.tableFields["Модель POS-терминала"])
        assertEquals(1, detailCalls)
    }

    @Test fun `sync obtains versions from list cell when user columns omit updated at`() = runBlocking {
        val paths = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val path = chain.request().url.encodedPath
            paths += path
            val body = when {
                path == "/v1/list/itsm_request" -> """{"status":"OK","data":{"list":[${syncItem("new")},${syncItem("old")}]}}"""
                path.endsWith("/sys_updated_at/new") -> """{"status":"OK","data":{"read_access":true,"value":"2026-10-28 12:00:00"}}"""
                path.endsWith("/sys_updated_at/old") -> """{"status":"OK","data":{"value":"2026-10-26 12:00:00"}}"""
                else -> error("Unexpected request: $path")
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody()).build()
        }.build()
        val records = OkHttpSimpleOneApi("https://sd.test/v1", client, closedArchiveCondition = { "wide" })
            .closedRequestsSince("user", "auth", "2026-10-27 12:00:00")
        assertEquals(listOf("REQ-new"), records.map { it.number })
        assertEquals("2026-10-28 12:00:00", records.single().sysUpdatedAt)
        assertTrue(paths.contains("/v1/list-cell-value/itsm_request/sys_updated_at/new"))
        assertFalse(paths.any { "/record/" in it })
    }

    @Test fun `unreadable version fails without returning an incomplete sync`() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val body = if (chain.request().url.encodedPath == "/v1/list/itsm_request")
                """{"status":"OK","data":{"list":[${syncItem("one")}]}}"""
            else """{"status":"OK","data":{"read_access":false,"value":"2026-10-28 12:00:00"}}"""
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody()).build()
        }.build()
        val result = runCatching { OkHttpSimpleOneApi("https://sd.test/v1", client, closedArchiveCondition = { "wide" })
            .closedRequestsSince("user", "auth", "2026-10-27 12:00:00") }
        assertTrue(result.exceptionOrNull() is SimpleOneFailure.InvalidResponse)
    }
}

internal fun syncItem(id: String, version: String? = null): String = buildJsonObject {
    listOf("number", "short_description", "state", "multicard_request_type", "multicard_terminal_address",
        "multicard_name_client", "multicard_terminal_model", "description", "multicard_id_terminal", "multicard_comment_ing",
        "multicard_additional_information", "assigned_user", "multicard_contact_person", "multicard_deadline",
        "pb_sn_pos_install", "pb_sn_pos_uninstall", "multicard_pos", "multicard_return_number_pos", "multicard_pin_pad",
        "multicard_return_number_pin", "multicard_closing_date", "resolved_at", "sys_created_at", "new_closure_code", "closure_notes")
        .forEach { put(it, "") }
    put("sys_id", id); put("number", "REQ-$id"); put("state", "closed"); put("multicard_request_type", "install")
    put("resolved_at", "2026-10-28 10:00:00"); put("sys_created_at", "2026-10-27 10:00:00")
    put("new_closure_code", "Решено с выездом"); put("multicard_merchant_tin", "1234567890")
    version?.let { put("sys_updated_at", it) }
}.toString()
