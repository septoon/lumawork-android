package ru.lumastack.lumawork.domain.analytics

import java.util.TimeZone
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import ru.lumastack.lumawork.domain.simpleone.*

class WorkStatisticsTest {
    @Test fun sharedFixturesInEveryDeviceTimezone() {
        val fixtures = Json.parseToJsonElement(javaClass.getResource("/work-statistics-fixtures.json")!!.readText()).jsonObject["cases"]!!.jsonArray
        val previous = TimeZone.getDefault()
        try {
            listOf("UTC", "America/Los_Angeles", "Asia/Tokyo").forEach { zone ->
                TimeZone.setDefault(TimeZone.getTimeZone(zone))
                fixtures.forEach { fixture ->
                    val f = fixture.jsonObject
                    val row = f["row"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content }
                    val record = SimpleOneRequest("", row.getValue("Номер заявки"), source = SimpleOneRequestSource.Closed,
                        requestType = row["Тип заявки"].orEmpty(), closureCode = row["Код закрытия"].orEmpty(),
                        additionalInformation = row["Информация"].orEmpty(), tableFields = row,
                        rankingDates = RankingDates(RankingDateContext.MoscowDisplay))
                    val c = workContribution(record)
                    val name = "${f["name"]} in $zone"
                    assertEquals(name, f["month"]!!.jsonPrimitive.contentOrNull, c.monthKey)
                    assertEquals(name, f["completed"]!!.jsonPrimitive.int, c.metrics.completedWithVisitCount)
                    assertEquals(name, f["returned"]!!.jsonPrimitive.int, c.metrics.returnEquipmentCount)
                    val category = f["category"]!!.jsonPrimitive.contentOrNull
                    val operation = f["operation"]!!.jsonPrimitive.contentOrNull
                    assertEquals(name, if (category != null) 1 else 0, c.metrics.byCategory.values.sumOf { it.values.sum() })
                    if (category != null) assertEquals(name, 1, c.metrics.byCategory[category]!![operation])
                }
            }
        } finally { TimeZone.setDefault(previous) }
    }

    @Test fun apiNaiveDatesUseUtcAndCompletionPrecedesResolution() {
        val record = SimpleOneRequest("id", "REQ1", rankingDates = RankingDates(RankingDateContext.ApiUtc,
            completedAt = "2026-10-31 22:00:00", resolvedAt = "2026-10-30 10:00:00"))
        assertEquals("2026-11", workContribution(record).monthKey)
    }

    @Test fun legacyArchiveCannotBecomeTrustedSource() {
        assertThrows(IllegalArgumentException::class.java) { workContribution(SimpleOneRequest("id", "REQ1")) }
    }

    @Test fun unparseableCompletionFallsThroughButRegistrationNeverUsed() {
        val r = SimpleOneRequest("id", "REQ1", registeredAt = "01.11.2026 10:00",
            rankingDates = RankingDates(RankingDateContext.MoscowDisplay, completedAt = "broken"),
            tableFields = mapOf("Время решения" to "31.10.2026 23:30"))
        assertEquals("2026-10", workContribution(r).monthKey)
        assertNull(workContribution(r.copy(tableFields = emptyMap())).monthKey)
    }
}
