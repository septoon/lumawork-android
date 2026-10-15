package ru.lumastack.lumawork.data.simpleone

import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
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

class SimpleOneBackpackApiTest {
    @Test
    fun `backpack uses iOS condition and hydrates date from record details`() = runBlocking {
        val urls = CopyOnWriteArrayList<String>()
        val client = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val request = chain.request()
                urls += request.url.toString()
                val body = if (request.url.encodedPath.contains("/record/")) {
                    """
                        {"status":"OK","data":{"record":{"sections":[{"elements":[
                          {"sys_column_name":"sys_updated_at","value":"2026-08-12 10:30:00"}
                        ]}]}}}
                    """.trimIndent()
                } else {
                    """
                        {"status":"OK","data":{"items":[{
                          "sys_id":"balance-1",
                          "zip":{"name":{"display_value":"Pax D200"},"s_n":"SERIAL-1"},
                          "warehouse":{"responsible_for_write_off":[{"display_value":"Иван Инженеров"}]},
                          "quantity":"2"
                        }]}}
                    """.trimIndent()
                }
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

        val item = api.backpackItems("auth-key").single()

        assertTrue(urls.first().contains("/list/itsm_tchnsrv_balances_in_warehouses"))
        assertTrue(urls.first().contains("quantity%3E0"))
        assertTrue(urls.first().contains("responsible_for_write_offCONTAINS_DYNAMIC161312729414608208"))
        assertTrue(urls.any { it.contains("/record/itsm_tchnsrv_balances_in_warehouses/balance-1") })
        assertEquals("Pax D200", item.name)
        assertEquals("SERIAL-1", item.serialNumber)
        assertEquals("Иван Инженеров", item.responsible)
        assertEquals(2, item.quantity)
        assertEquals(
            LocalDateTime.of(2026, 8, 12, 10, 30).toInstant(ZoneOffset.UTC).toEpochMilli(),
            item.receivedAtEpochMillis,
        )
    }
}
