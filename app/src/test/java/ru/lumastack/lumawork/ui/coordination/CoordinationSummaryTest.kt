package ru.lumastack.lumawork.ui.coordination

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class CoordinationSummaryTest {
    private val crimea = ZoneId.of("Europe/Simferopol")
    private val now = Instant.parse("2026-10-27T21:26:00Z")

    @Test fun `update on current local date says today`() {
        assertEquals("сегодня, 00:22", updated("2026-10-27T21:22:00Z"))
    }

    @Test fun `update before local midnight says yesterday even when less than one hour old`() {
        assertEquals("вчера, 23:58", updated("2026-10-27T20:58:00Z"))
    }

    @Test fun `older and future dates keep explicit calendar date`() {
        assertEquals("26 окт. 2026, 23:58", updated("2026-10-26T20:58:00Z"))
        assertEquals("29 окт. 2026, 00:22", updated("2026-10-28T21:22:00Z"))
    }

    @Test fun `relative date uses requested time zone for both update and current date`() {
        assertEquals("сегодня, 21:22", coordinationUpdatedText(
            Instant.parse("2026-10-27T21:22:00Z").toEpochMilli(), now, ZoneId.of("UTC"),
        ))
    }

    @Test fun `yesterday crosses month and year boundaries`() {
        assertEquals("вчера, 23:58", coordinationUpdatedText(
            Instant.parse("2026-12-31T20:58:00Z").toEpochMilli(),
            Instant.parse("2026-12-31T21:26:00Z"), crimea,
        ))
    }

    @Test fun `metric captions follow Russian count rules`() {
        val cases = listOf(
            Triple(0, "инженеров", "активных заявок"),
            Triple(1, "инженер", "активная заявка"),
            Triple(2, "инженера", "активные заявки"),
            Triple(5, "инженеров", "активных заявок"),
            Triple(11, "инженеров", "активных заявок"),
            Triple(14, "инженеров", "активных заявок"),
            Triple(21, "инженер", "активная заявка"),
            Triple(22, "инженера", "активные заявки"),
            Triple(111, "инженеров", "активных заявок"),
        )
        cases.forEach { (count, engineers, requests) ->
            assertEquals("Engineers: $count", engineers, coordinationEngineerCountTitle(count))
            assertEquals("Requests: $count", requests, coordinationRequestCountTitle(count))
        }
    }

    private fun updated(value: String) = coordinationUpdatedText(Instant.parse(value).toEpochMilli(), now, crimea)
}
