package ru.lumastack.lumawork.data.workschedule

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import ru.lumastack.lumawork.data.simpleone.OkHttpSimpleOneApi

class WorkScheduleApiTest {
    @Test fun `journal pagination record discovery and read only widget load follow iOS contract`() = runBlocking {
        val paths = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            assertEquals("auth=key", request.header("Cookie"))
            val path = request.url.encodedPath
            paths += path
            val payload = when (path) {
                "/v1/list/itsm_tchnsrv_accounting_journal" -> {
                    assertEquals("100", request.url.queryParameter("per_page"))
                    val page = request.url.queryParameter("page")!!
                    val id = if (page == "1") "journal" else "journal-two"
                    """{"data":{"total":101,"items":[{"sys_id":"$id","city":{"database_value":"city","display_value":"Алушта"},"year":"2026","month":"10"}]}}"""
                }
                "/v1/record/itsm_tchnsrv_accounting_journal/journal" -> """{"data":{"widget_instance_id":"widget-id","name":"График"}}"""
                "/v1/widget/run-server-script/widget-id" -> {
                    assertEquals("POST", request.method)
                    val buffer = okio.Buffer(); request.body!!.writeTo(buffer)
                    val body = Json.parseToJsonElement(buffer.readUtf8()).jsonObject
                    assertEquals(setOf("recordID","action"), body.keys)
                    assertEquals("\"journal\"", body["recordID"].toString())
                    assertEquals("\"load\"", body["action"].toString())
                    """{"data":{"daysInMonth":31,"scheduleData":{}}}"""
                }
                else -> error("Unexpected endpoint $path")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(payload.toResponseBody()).build()
        }.build()
        val api = OkHttpSimpleOneApi("https://sd.test/v1", client)
        val journals = api.workScheduleJournals("key")
        assertEquals(2, journals.size)
        val schedule = api.workSchedule(journals.first(), "key")
        assertTrue(schedule.employees.isEmpty())
        assertEquals(listOf("/v1/list/itsm_tchnsrv_accounting_journal", "/v1/list/itsm_tchnsrv_accounting_journal", "/v1/record/itsm_tchnsrv_accounting_journal/journal", "/v1/widget/run-server-script/widget-id"), paths)
    }
}
