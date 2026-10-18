package ru.lumastack.lumawork.data.simpleone

import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SimpleOneTimeReportApiTest {
    @Test fun `personal archive reads beyond one hundred pages and reports progress`() = runBlocking {
        var pages = 0
        val progress = mutableListOf<Int>()
        val client = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val request = chain.request()
            val page = request.url.queryParameter("page")!!.toInt()
            pages++
            val range = if (page <= 100) ((page - 1) * 100 + 1)..(page * 100) else 10001..10001
            val items = range.joinToString(",") { id ->
                """{"sys_id":"$id","task":"TASK-$id","sys_created_at":"12.08.2026 10:30:00"}"""
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"data":{"items":[$items],"total":10001}}""".toResponseBody()).build()
        }).build()
        val entries = OkHttpSimpleOneApi("https://sd.test/v1", client, Json { ignoreUnknownKeys = true })
            .timeReportEntries("auth", progress::add)
        assertEquals(101, pages)
        assertEquals(10001, entries.size)
        assertEquals(10001, progress.last())
    }

    @Test
    fun `time report list uses iOS condition and maps nested display values`() = runBlocking {
        val requestedUrl = AtomicReference<String>()
        val client = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val request = chain.request()
                requestedUrl.set(request.url.toString())
                val body = """
                    {
                      "status":"OK",
                      "data":{
                        "items":[{
                          "task":{"value":"task-id","display_value":"SUTS001"},
                          "sys_created_at":"12.08.2026 10:30:00",
                          "time_of_work":{"value":"3600000","display_value":"1 час"},
                          "travel_time":"30",
                          "person":{"value":"person-id","display_value":"Иван Инженеров"},
                          "result":"Работы выполнены"
                        }],
                        "total":1
                      }
                    }
                """.trimIndent()
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(body.toResponseBody())
                    .build()
            })
            .build()
        val api = OkHttpSimpleOneApi(
            baseOrigin = "https://sd.test/v1",
            client = client,
            json = Json { ignoreUnknownKeys = true },
        )

        val entry = api.timeReportEntries("auth-key").single()

        assertTrue(requestedUrl.get().contains("/list/itsm_tchnsrv_time_report"))
        assertTrue(requestedUrl.get().contains("personDYNAMIC156957141416138823"))
        assertEquals("SUTS001", entry.activity)
        assertEquals("Иван Инженеров", entry.executor)
        assertEquals(60, entry.workMinutes)
        assertEquals(30, entry.travelMinutes)
        assertEquals("Работы выполнены", entry.notes)
    }
}
