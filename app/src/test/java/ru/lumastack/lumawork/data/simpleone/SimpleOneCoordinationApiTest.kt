package ru.lumastack.lumawork.data.simpleone

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.lumastack.lumawork.domain.coordination.CoordinationRegion

class SimpleOneCoordinationApiTest {
    @Test
    fun `coordination and return conditions match iOS contract`() = runBlocking {
        val conditions = CopyOnWriteArrayList<String>()
        val client = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                conditions += chain.request().url.queryParameter("condition").orEmpty()
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("""{"status":"OK","data":{"items":[]}}""".toResponseBody())
                    .build()
            })
            .build()
        val api = OkHttpSimpleOneApi("https://sd.test/v1", client)

        api.coordinationRequests(CoordinationRegion.Alushta, "auth")
        api.returnEquipmentRequests("simple-user", "auth")

        assertEquals(2, conditions.size)
        assertTrue(conditions[0].contains("assignment_group=171872459801458049"))
        assertTrue(conditions[0].contains("related_inquiry.company_location=171878330004108924"))
        assertTrue(conditions[0].contains("client_service_idNOTLIKEЭкспертиза. Сервисные заявки БЧ"))
        assertTrue(conditions[1].contains("assigned_user=simple-user"))
        assertTrue(conditions[1].contains("multicard_request_type=returnEquip"))
        assertTrue(conditions[1].contains("stateNOT INcancelled@closed_by_user@closed@escalated@completed"))
    }
    @Test
    fun `group archive uses fixed independent condition and exact incremental ordering`() = runBlocking {
        val requests = CopyOnWriteArrayList<okhttp3.Request>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests += chain.request()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK")
                .body("""{"status":"OK","data":{"items":[{"sys_id":"1","number":"REQ1","sys_updated_at":"2026-10-17 10:00:00"}],"total_count":101}}""".toResponseBody()).build()
        }.build()
        val api = OkHttpSimpleOneApi("https://sd.test/v1", client)
        val first = api.groupClosedRequestsPage(1, "auth", perPage = 100)
        val second = api.groupClosedRequestsPage(2, "auth", perPage = 100, newestFirst = true)
        val condition = "(company_location=171878330004108924^resolved_atNOTONopt:167653141215593053" +
            "^stateNOT INon_hold@assigned@in_progress@escalated@returned_to_work@3@6@update_received)"
        assertEquals(condition, requests[0].url.queryParameter("condition"))
        assertEquals(condition + "^ORDERBYDESCsys_updated_at^ORDERBYDESCsys_id", requests[1].url.queryParameter("condition"))
        assertEquals("2", requests[1].url.queryParameter("page"))
        assertEquals("100", requests[1].url.queryParameter("per_page"))
        assertTrue(requests[0].url.queryParameter("columns").orEmpty().split(",").contains("sys_updated_at"))
        assertEquals(101, first.totalCount)
        assertTrue(first.hasMore)
        assertTrue(!second.hasMore)
        assertEquals("2026-10-17 10:00:00", first.records.single().sysUpdatedAt)
        assertEquals(ru.lumastack.lumawork.domain.simpleone.SimpleOneRequestSource.Closed, first.records.single().source)
    }

    @Test
    fun `group archive without total continues until short page`() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK")
                .body("""{"status":"OK","data":{"items":[{"sys_id":"1","number":"REQ1"}]}}""".toResponseBody()).build()
        }.build()
        val api = OkHttpSimpleOneApi("https://sd.test/v1", client)
        assertTrue(api.groupClosedRequestsPage(1, "auth", perPage = 1).hasMore)
        assertTrue(!api.groupClosedRequestsPage(1, "auth", perPage = 2).hasMore)
    }

}
