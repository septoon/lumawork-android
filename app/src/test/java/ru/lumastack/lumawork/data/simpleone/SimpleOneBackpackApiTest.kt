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
    @Test fun `fast backpack list does not request record details`() = runBlocking {
        val paths = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            paths += chain.request().url.encodedPath
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"data":{"items":[{"sys_id":"balance1","zip":{"name":"Pax","s_n":"000ABC"},"quantity":"1"}]}}""".toResponseBody()).build()
        }.build()
        val api = OkHttpSimpleOneApi("https://sd.test/v1", client = client)
        val listed = api.backpackList("key")
        assertEquals("000ABC", listed.single().serialNumber)
        assertTrue(paths.none { it.contains("/record/") })
    }

    @Test fun `office equipment uses exact owner dynamic condition and maps detail sections`() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val body = if (request.url.encodedPath.contains("/list/")) {
                assertEquals("(ownerDYNAMIC156957141416138823)", request.url.queryParameter("condition"))
                """{"data":{"items":[{"sys_id":"office1","name":"Ноутбук","serial_number":"0000123","c_model":"Model","c_vendor":{"display_value":"Vendor"}}]}}"""
            } else {
                """{"data":{"record":{"sections":[{"name":"Основное","elements":[{"sys_column_name":"serial_number","name":"Серийный номер","value":"0000123"},{"sys_column_name":"c_alternative_name","name":"Наименование","value":"Рабочий ноутбук"}]}]}}}"""
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body.toResponseBody()).build()
        }.build()
        val api = OkHttpSimpleOneApi("https://sd.test/v1", client = client)
        val listed = api.officeEquipment("key").single()
        assertEquals("Vendor", listed.vendor)
        assertEquals("0000123", listed.serialNumber)
        val detailed = api.officeEquipmentDetails(listed, "key")
        assertEquals("Рабочий ноутбук", detailed.name)
        assertEquals("0000123", detailed.serialNumber)
        assertEquals("Основное", detailed.detailSections.single().title)
        assertEquals("Серийный номер", detailed.detailSections.single().fields.first().title)
    }

    @Test fun `office details preserve sections and skip hidden fields and widgets`() {
        val raw = Json.parseToJsonElement("""{"sys_id":"1","sections":[{"name":"Основное","elements":[
            {"sys_column_name":"sys_updated_at","name":"Когда изменено","value":"2026-10-16 10:00:00"},
            {"sys_column_name":"active","name":"Активно","column_type":"boolean","value":true},
            {"name":"Скрыто","value":"secret","hidden":true},
            {"name":"Разделитель","value":"ignored","split":true},
            {"name":"Виджет","value":"ignored","widget_instance_id":"widget"}
        ]},{"name":"Другое","elements":[{"name":"Активно","value":"Другой раздел"}]}]}""") as kotlinx.serialization.json.JsonObject
        val sections = parseOfficeEquipment(raw).detailSections
        assertEquals(2, sections.size)
        assertEquals(listOf("Получено", "Активно"), sections.first().fields.map { it.title })
        assertEquals("Да", sections.first().fields.last().value)
        assertEquals("Другой раздел", sections.last().fields.single().value)
    }

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
