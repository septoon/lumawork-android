package ru.lumastack.lumawork.data.route

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import ru.lumastack.lumawork.domain.route.RouteSettings
import ru.lumastack.lumawork.domain.route.RouteStopStatus

class RoutePayloadParserTest {
    private val parser = RoutePayloadParser()
    private val settings = RouteSettings("Склад", "Дом")

    @Test
    fun `records response accepts snake case fields and Russian statuses`() {
        val payload = Json.parseToJsonElement(
            """
            {
              "records": [{
                "date": "2026-08-11",
                "distance_km": 28,
                "period_start_odometer": 212085,
                "sent": true,
                "stops": [
                  {"id":"1","address":"Склад","status":"Выполнена"},
                  {"id":"2","address":"Клиент","status":"Отказ","rejectReason":"Закрыто"},
                  {"id":"3","address":"Склад","status":"done"}
                ]
              }]
            }
            """.trimIndent(),
        )

        val day = parser.parseDay(payload, "2026-08-11", settings)!!

        assertEquals(28, day.distanceKm)
        assertEquals(212085, day.periodStartOdometer)
        assertEquals(RouteStopStatus.Declined, day.stops[1].status)
        assertEquals("Закрыто", day.stops[1].declineReason)
        assertEquals(true, day.sent)
    }

    @Test
    fun `keyed days response is normalized to at least three stops`() {
        val payload = Json.parseToJsonElement(
            """
            {"days":{"2026-08-11":{"stops":[
              {"id":"1","address":"Начало"},
              {"id":"2","address":"Конец"}
            ]}}}
            """.trimIndent(),
        )

        val day = parser.parseDay(payload, "2026-08-11", settings)!!

        assertEquals(3, day.stops.size)
        assertEquals(RouteStopStatus.Done, day.stops.first().status)
        assertEquals(RouteStopStatus.Pending, day.stops[1].status)
        assertEquals(RouteStopStatus.Done, day.stops.last().status)
        assertFalse(day.sent)
    }

    @Test
    fun `missing date returns null`() {
        val payload = Json.parseToJsonElement("{\"records\":[]}")

        assertNull(parser.parseDay(payload, "2026-08-11", settings))
    }
}
