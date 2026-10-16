package ru.lumastack.lumawork.domain.route

import org.junit.Assert.*
import org.junit.Test
import java.net.URI
import java.net.URLDecoder
import ru.lumastack.lumawork.data.route.RoutePayloadParser
import kotlinx.serialization.json.Json
import ru.lumastack.lumawork.domain.simpleone.SimpleOneRequest

class HomeRouteParityTest {
    private val settings = RouteSettings("Склад", "Дом")
    @Test fun `parser selects requested work type for same date`() {
        val payload = Json.parseToJsonElement("""{"records":[{"date":"2026-10-16","workType":"POS","distanceKm":10},{"date":"2026-10-16","workType":"ARM","distanceKm":20}]}""")
        val day = RoutePayloadParser().parseDay(payload, "2026-10-16", settings, RouteWorkType.Arm)!!
        assertEquals(RouteWorkType.Arm, day.workType)
        assertEquals(20, day.distanceKm)
    }
    @Test fun `yandex link preserves order qualifies addresses and avoids bad roads`() {
        val url = makeYandexRouteUrl(listOf("ул. Ленина,1", "", "Ялта, Мира, 2"))!!
        val query = URLDecoder.decode(URI(url).rawQuery, "UTF-8")
        assertTrue(query.contains("rtext=Алушта, ул. Ленина, 1~Ялта, Мира, 2"))
        assertTrue(query.contains("routes[avoid]=unpaved,poor_condition"))
        assertNull(makeYandexRouteUrl(listOf("", "Одна точка")))
    }
    @Test fun `closed template sorts deduplicates consecutive addresses and removes warehouse edges`() {
        fun request(id: String, address: String, time: String) = SimpleOneRequest(id, id, address = address, closedAt = "16.10.2026 $time")
        val records = listOf(request("4", "Склад", "16:00:00"), request("2", "Ялта, Мира, 1", "12:00:00"),
            request("3", "Ялта, Мира, 1", "13:00:00"), request("1", "Склад", "10:00:00"),
            request("5", "Другой день", "14:00:00").copy(closedAt = "15.10.2026 14:00:00"))
        assertEquals(listOf("2"), closedRouteTemplate(records, "2026-10-16", "Склад").map { it.id })
    }
}
