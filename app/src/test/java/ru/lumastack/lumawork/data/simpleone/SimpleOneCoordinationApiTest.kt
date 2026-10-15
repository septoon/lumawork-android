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
}
