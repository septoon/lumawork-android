package ru.lumastack.lumawork.data.simpleone

import java.time.LocalDate
import java.time.LocalDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SimpleOnePayloadTest {
    @Test fun `detail merchant TIN remains available after merging archived fields`() {
        val detail = parseSimpleOneRequest(Json.parseToJsonElement("""{"sys_id":"one","number":"REQ1","multicard_merchant_tin":"1234567890"}""").jsonObject)
        val archived = ru.lumastack.lumawork.domain.simpleone.SimpleOneRequest("one", "REQ1", tableFields = mapOf("Дополнительная колонка" to "сохранить"))
        val merged = detail.mergeMissing(archived)
        assertEquals("1234567890", ru.lumastack.lumawork.domain.requests.RequestDetailsData(merged).tin)
        assertEquals("сохранить", merged.tableFields["Дополнительная колонка"])
        val index = ru.lumastack.lumawork.domain.requests.ClientPersonalCommentMatchingIndex(
            listOf(ru.lumastack.lumawork.domain.requests.ClientPersonalComment("comment", displayTIN = "1234567890")))
        assertEquals("comment", index.comment(ru.lumastack.lumawork.domain.requests.RequestDetailsData(merged).tin)?.id)
    }
    @Test fun `canonical closure fields preserve display label and resolution`() {
        val record = parseSimpleOneRequest(Json.parseToJsonElement("""
            {"sys_id":"done","number":"REQ-DONE",
             "new_closure_code":{"value":"visit-code","display_value":"Решено с выездом на место"},
             "closure_notes":"Терминал заменён"}
        """).jsonObject)
        assertEquals("Решено с выездом на место", record.closureCode)
        assertEquals("Терминал заменён", record.resolution)
    }

    @Test
    fun `list item resolves wrapped display and nested fields`() {
        val item = Json.parseToJsonElement(
            """
            {
              "sys_id":"123",
              "number":{"value":"REQ001","display_value":"REQ001"},
              "incoming_number":"SUTSPROD-42",
              "state":{"value":"in_progress","display_value":"В работе"},
              "assignment_group":{"display_value":"Сервис Крыма","value":"171"},
              "assigned_user":{"c_full_name":{"display_value":"Иван Инженеров"}},
              "multicard_terminal_address":"Алушта, ул. Ленина, 1",
              "multicard_deadline":"2026-08-12 15:30:00"
            }
            """.trimIndent(),
        ).jsonObject

        val record = parseSimpleOneRequest(item)

        assertEquals("123", record.id)
        assertEquals("SUTSPROD-42", record.displayNumber)
        assertEquals("В работе", record.state)
        assertEquals("Сервис Крыма", record.assignmentGroup)
        assertEquals("Иван Инженеров", record.assignedUser)
        assertEquals("Алушта, ул. Ленина, 1", record.address)
    }

    @Test
    fun `response accepts records nested under data`() {
        val response = Json.parseToJsonElement(
            """{"data":{"records":[{"number":"REQ1"},{"number":"REQ2"}]}}""",
        ).jsonObject

        assertEquals(2, simpleOneListItems(response)?.size)
    }

    @Test
    fun `deadline supports SimpleOne formats and ignores epoch placeholder`() {
        val record = parseSimpleOneRequest(
            Json.parseToJsonElement(
                """{"number":"REQ1","multicard_deadline":"12.08.2026 15:30"}""",
            ).jsonObject,
        )
        val epoch = parseSimpleOneRequest(
            Json.parseToJsonElement(
                """{"number":"REQ2","multicard_deadline":"1970-01-01 00:00:00"}""",
            ).jsonObject,
        )

        assertTrue(record.isDueToday(LocalDate.of(2026, 8, 12)))
        assertFalse(record.isOverdue(LocalDateTime.of(2026, 8, 12, 14, 0)))
        assertEquals(null, epoch.deadlineDateTime())
    }

    @Test
    fun `detail sections are flattened into request fields`() {
        val response = Json.parseToJsonElement(
            """
            {
              "data": {
                "item": {
                  "sys_id": "123",
                  "number": "REQ001",
                  "sections": [
                    {
                      "elements": [
                        {"sys_column_name":"multicard_contact_phone","value":"+7 999 100-20-30"},
                        {"system_name":"multicard_terminal_model","value":{"display_value":"Verifone V240m"}},
                        {"name":"multicard_information","value":"МК Статус: Работа в приложении\nID терминала: T-42"}
                      ]
                    }
                  ]
                }
              }
            }
            """.trimIndent(),
        ).jsonObject

        val item = simpleOneRecordItem(response)!!
        val record = parseSimpleOneRequest(flattenSimpleOneRecord(item))

        assertEquals("+7 999 100-20-30", record.contactPhone)
        assertEquals("Verifone V240m", record.terminalModel)
        assertEquals("T-42", record.terminalId)
        assertEquals("Работа в приложении", record.multicardState)
    }
}
