package ru.lumastack.lumawork.domain.requests

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test
import ru.lumastack.lumawork.domain.simpleone.SimpleOneRequest

class ClosedRequestsQueryTest {
    @Test fun `search ignores period and finds old and undated records`() {
        val records = listOf(
            SimpleOneRequest("1", "OLD", resolvedAt = "12.01.2020 12:00", customer = "Иванов"),
            SimpleOneRequest("2", "NEW", resolvedAt = "17.10.2026 12:00", customer = "Петров"),
            SimpleOneRequest("3", "UNDATED", customer = "Иванов"),
        )
        val prepared = prepareClosedRequests(records)
        val period = ClosedRequestsPeriod(LocalDate.of(2026, 10, 17), LocalDate.of(2026, 10, 17))
        assertEquals(setOf("OLD", "UNDATED"), queryClosedRequests(prepared, "Иванов", period).map { it.request.number }.toSet())
        assertEquals(listOf("NEW"), queryClosedRequests(prepared, "", period).map { it.request.number })
    }

    @Test fun `return equipment groups by registration and normal requests by completion`() {
        val request = SimpleOneRequest("1", "R", requestType = "returnEquip", registeredAt = "12.10.2026 12:00", resolvedAt = "17.10.2026 15:00")
        assertEquals(LocalDate.of(2026, 10, 12), prepareClosedRequests(listOf(request)).single().day)
        assertEquals(LocalDate.of(2026, 10, 17), prepareClosedRequests(listOf(request.copy(requestType = "install"))).single().day)
    }

    @Test fun `automatic period uses recent days or most recent archived day`() {
        val today = LocalDate.of(2026, 10, 17)
        val old = prepareClosedRequests(listOf(SimpleOneRequest("1", "R", resolvedAt = "12.10.2026 12:00")))
        assertEquals(ClosedRequestsPeriod(today.minusDays(5), today.minusDays(5)), defaultClosedRequestsPeriod(old, today))
        val recent = old + prepareClosedRequests(listOf(SimpleOneRequest("2", "S", resolvedAt = "16.10.2026 12:00")))
        assertEquals(ClosedRequestsPeriod(today.minusDays(1), today), defaultClosedRequestsPeriod(recent, today))
    }
}
