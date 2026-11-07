package ru.lumastack.lumawork.data.analytics

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import ru.lumastack.lumawork.domain.analytics.*

class WorkStatisticsApiTest {
    @Test fun actualPutContainsOnlyAggregateContractAndAllZeroCells() = runTest {
        var captured: Request? = null
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            captured = chain.request()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"accepted":true,"serverRevision":1,"requestId":"00000000-0000-4000-8000-000000000001","sourceSyncedAt":"2026-11-07T09:00:00Z","receivedAt":"2026-11-07T09:00:01Z"}""".toResponseBody("application/json".toMediaType())).build()
        }.build()
        val api = OkHttpWorkStatisticsApi("https://example.test", client)
        val upload = WorkStatisticsUpload(requestId = "00000000-0000-4000-8000-000000000001", expectedRevision = 0,
            sourceUserId = "source", sourceSyncedAt = "2026-11-07T09:00:00Z", months = emptyList(), unknownDate = WorkMetrics())
        api.upload("token", upload)
        val request = captured!!
        assertEquals("Android", request.header("X-LumaWork-Device-Platform"))
        assertEquals("Bearer token", request.header("Authorization"))
        assertEquals("PUT", request.method)
        val buffer = okio.Buffer(); request.body!!.writeTo(buffer)
        val body = Json.parseToJsonElement(buffer.readUtf8()).jsonObject
        assertEquals(setOf("schemaVersion", "rulesVersion", "requestId", "expectedRevision", "sourceUserId", "sourceSyncedAt", "timezone", "months", "unknownDate"), body.keys)
        val metrics = body.getValue("unknownDate").jsonObject
        assertEquals(setOf("closedCount", "completedWithVisitCount", "returnEquipmentCount", "unclassifiedCompletedCount", "byCategory"), metrics.keys)
        assertEquals(workCategories.toSet(), metrics.getValue("byCategory").jsonObject.keys)
        metrics.getValue("byCategory").jsonObject.values.forEach { assertEquals(workOperations.toSet(), it.jsonObject.keys) }
    }
}
