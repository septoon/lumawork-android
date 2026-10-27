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

    @Test fun `missing closure code preserves source status instead of inventing refusal`() {
        for ((raw, expected) in listOf("closed" to "Закрыта", "completed" to "Выполнена", "rejected" to "Отклонена",
            "Закрыта" to "Закрыта", "Выполнена" to "Выполнена", "Отклонена" to "Отклонена", "" to "Нет данных")) {
            assertEquals(expected, SimpleOneRequest("id", "REQ", state = raw).closedStatusTitle())
        }
        assertEquals("Выполнена", SimpleOneRequest("id", "REQ", state = "closed", closureCode = "Решено с выездом").closedStatusTitle())
        assertEquals("Отказ", SimpleOneRequest("id", "REQ", closureCode = "Отказ ТСП").closedStatusTitle())
    }

    @Test fun `missing completion uses registration with honest date label and no visit analytics`() {
        val prepared = prepareClosedRequests(listOf(SimpleOneRequest("id", "REQ", state = "completed",
            registeredAt = "2026-10-27 09:39:06")))
        assertEquals(LocalDate.of(2026, 10, 27), prepared.single().day)
        assertEquals("Время регистрации", prepared.single().timeTitle)
        assertEquals("Выполнена", prepared.single().statusText)
        assertEquals(0, buildClosedRequestsAnalytics(prepared).completedCount)
    }

    @Test fun `invalid completion does not hide valid alternative dates`() {
        val item = prepareClosedRequests(listOf(SimpleOneRequest("id", "REQ", requestType = "install",
            resolvedAt = "invalid", completedAt = "2026-10-25 10:00:00", registeredAt = "2026-10-20 10:00:00"))).single()
        assertEquals(LocalDate.of(2026, 10, 25), item.day)
        assertEquals("Выполнена", item.timeTitle)
    }
}
