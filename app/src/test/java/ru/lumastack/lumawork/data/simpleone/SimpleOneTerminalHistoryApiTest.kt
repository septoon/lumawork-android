package ru.lumastack.lumawork.data.simpleone

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import ru.lumastack.lumawork.domain.requests.*
import ru.lumastack.lumawork.domain.simpleone.SimpleOneRequestSource
import java.io.File

class SimpleOneTerminalHistoryApiTest {
    @Test fun `all history pages use supplied field condition Default view and closure columns`() = runBlocking {
        val pages = mutableListOf<Int>()
        val api = api { request ->
            val url = request.url
            assertEquals("Default", url.queryParameter("view"))
            assertEquals("auth=test-auth", request.header("Cookie"))
            assertEquals("(resolved_atNOTONopt:167653141215593053^stateNOT INon_hold@assigned@in_progress@escalated@returned_to_work@3@6@update_received^multicard_id_terminalLIKE00123)^ORDERBYDESCresolved_at^ORDERBYDESCsys_id", url.queryParameter("condition"))
            assertTrue(url.queryParameter("columns")!!.contains("new_closure_code"))
            val page = url.queryParameter("page")!!.toInt().also { pages += it }
            val rows = (if (page == 1) 1..100 else 101..102).joinToString(",") { i ->
                """{"sys_id":"$i","number":"REQ$i","multicard_id_terminal":"${if (i == 102) "001234" else "00123"}"}"""
            }
            """{"status":"OK","data":{"items":[$rows],"pagination":{"total":102}}}"""
        }
        assertEquals(101, api.terminalHistory("00123", "test-auth").size)
        assertEquals(listOf(1, 2), pages)
    }

    @Test fun `repeated final page fails rather than caching a truncated history`() = runBlocking {
        val api = api { """{"status":"OK","data":{"items":[{"sys_id":"same","number":"REQsame","multicard_id_terminal":"00123"}],"total":101}}""" }
        assertTrue(runCatching { api.terminalHistory("00123", "test-auth") }.isFailure)
    }

    @Test fun `wrapped canonical fields preserve type executor and closure result`() {
        val parsed = parseSimpleOneRequest(Json.parseToJsonElement("""
            {"sys_id":"record","number":{"value":"REQ1"},"multicard_request_type":{"value":"serviceStd"},
             "multicard_id_terminal":{"value":"Y0310530"},
             "assigned_user":{"value":{"database_value":"engineer","display_value":"Инженер А"}},
             "new_closure_code":{"value":{"database_value":"closure","display_value":"Решено без выезда"}}}
        """).jsonObject, SimpleOneRequestSource.Closed)
        assertEquals("Сервисная", terminalHistoryType(parsed))
        assertEquals("Инженер А", parsed.assignedUser)
        assertEquals("Выполнена", terminalHistoryStatus(parsed))
        val empty = parseSimpleOneRequest(Json.parseToJsonElement("""{"number":"REQ2","multicard_request_type":{"value":""},"itsm_request_type":"reservationSP"}""").jsonObject)
        assertEquals("", empty.requestType)
    }

    @Test fun `real Y0310530 response matches verified iOS mapping when supplied locally`() {
        val path = System.getenv("SIMPLEONE_HISTORY_RESPONSE_FIXTURE")
        org.junit.Assume.assumeTrue("Local authenticated response fixture not provided", path != null)
        val response = Json.parseToJsonElement(File(requireNotNull(path)).readText()).jsonObject
        val records = terminalRequestHistory(simpleOneListItems(response)!!.map { parseSimpleOneRequest(it, SimpleOneRequestSource.Closed) }, "Y0310530")
        assertEquals(13, records.size)
        assertEquals(12, records.count { terminalHistoryStatus(it) == "Выполнена" })
        assertEquals(1, records.count { terminalHistoryStatus(it) == "Отказ" })
        assertEquals("Сервисная", terminalHistoryType(records.first { it.number == "REQ4180388" }))
        assertEquals("Не указан", terminalHistoryType(records.first { it.number == "REQ4067482" }))
        println("Real Y0310530 response: 13 records, 12 completed, 1 refusal; iOS parity verified")
    }

    private fun api(body: (okhttp3.Request) -> String) = OkHttpSimpleOneApi("https://sd.test/v1", OkHttpClient.Builder().addInterceptor { chain ->
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body(chain.request()).toResponseBody()).build()
    }.build())
}
