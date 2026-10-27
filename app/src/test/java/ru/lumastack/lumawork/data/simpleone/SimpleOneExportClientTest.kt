package ru.lumastack.lumawork.data.simpleone

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SimpleOneExportClientTest {
    @Test
    fun `export captures old ids includes record identity and downloads only new id`() = runBlocking {
        val requests = CopyOnWriteArrayList<String>()
        val bodies = CopyOnWriteArrayList<String>()
        val xlsx = byteArrayOf(0x50, 0x4b, 0x03, 0x04)
        val client = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val request = chain.request()
                requests += "${request.method} ${request.url.encodedPath}"
                request.body?.let { body ->
                    val buffer = Buffer()
                    body.writeTo(buffer)
                    bodies += buffer.readUtf8()
                }
                val responseBody = when {
                    request.method == "GET" && request.url.encodedPath == "/v1/exports" ->
                        """{"status":"OK","data":{"items":[{"sys_id":"old-id"}]}}""".toByteArray()
                    request.method == "POST" && request.url.encodedPath == "/v1/exports" ->
                        """{"status":"OK","data":{"sys_id":"new-id"}}""".toByteArray()
                    request.url.encodedPath == "/v1/exports/download-url" ->
                        """{"status":"OK","data":{"downloadUrls":["https://download.test/file.xlsx"]}}""".toByteArray()
                    request.url.host == "download.test" -> xlsx
                    else -> error("Unexpected request: ${request.method} ${request.url}")
                }
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(responseBody.toResponseBody())
                    .build()
            })
            .build()
        val exportClient = SimpleOneExportClient(
            origin = "https://sd.test/v1",
            client = client,
            json = Json { ignoreUnknownKeys = true },
            closedArchiveCondition = { ClosedRequestsArchiveQuery("111", "222", "333").condition() },
        )

        val result = exportClient.exportClosedRequests("user-1", "auth-key")

        assertTrue(xlsx.contentEquals(result))
        assertEquals(
            listOf(
                "GET /v1/exports",
                "POST /v1/exports",
                "POST /v1/exports/download-url",
                "GET /file.xlsx",
            ),
            requests,
        )
        val createPayload = Json.parseToJsonElement(bodies[0]).jsonObject
        val columns = createPayload["export"]!!.jsonObject["columns"]!!.jsonArray
        assertEquals(26, columns.size)
        assertTrue(columns.any { it.toString() == "\"sys_id\"" })
        assertTrue(bodies[1].contains("new-id"))
        assertFalse(bodies[1].contains("old-id"))
    }

    @Test
    fun `time report export uses iOS table condition and work date fields`() = runBlocking {
        val bodies = CopyOnWriteArrayList<String>()
        val xlsx = byteArrayOf(0x50, 0x4b, 0x03, 0x04)
        val client = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val request = chain.request()
                request.body?.let { body ->
                    val buffer = Buffer()
                    body.writeTo(buffer)
                    bodies += buffer.readUtf8()
                }
                val responseBody = when {
                    request.method == "GET" && request.url.encodedPath == "/v1/exports" ->
                        """{"status":"OK","data":{"items":[]}}""".toByteArray()
                    request.method == "POST" && request.url.encodedPath == "/v1/exports" ->
                        """{"status":"OK","data":{"sys_id":"time-id"}}""".toByteArray()
                    request.url.encodedPath == "/v1/exports/download-url" ->
                        """{"status":"OK","data":{"downloadUrls":["https://download.test/time.xlsx"]}}""".toByteArray()
                    request.url.host == "download.test" -> xlsx
                    else -> error("Unexpected request: ${request.method} ${request.url}")
                }
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(responseBody.toResponseBody())
                    .build()
            })
            .build()
        val exportClient = SimpleOneExportClient(
            origin = "https://sd.test/v1",
            client = client,
            json = Json { ignoreUnknownKeys = true },
            closedArchiveCondition = { ClosedRequestsArchiveQuery("111", "222", "333").condition() },
        )

        val result = exportClient.exportTimeReport("user-1", "auth-key")

        assertTrue(xlsx.contentEquals(result))
        val payload = Json.parseToJsonElement(bodies[0]).jsonObject
        val export = payload["export"]!!.jsonObject
        assertEquals("itsm_tchnsrv_time_report", export["tableName"].toString().trim('"'))
        assertTrue(export["condition"].toString().contains("personDYNAMIC156957141416138823"))
        assertEquals(16, export["columns"]!!.jsonArray.size)
        assertTrue(export["columns"]!!.jsonArray.any { it.toString().contains("date_of_work") })
        assertTrue(export["columns"]!!.jsonArray.any { it.toString().contains("sys_id") })
        assertTrue(export["columns"]!!.jsonArray.any { it.toString().contains("result") })
    }
}
