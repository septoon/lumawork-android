package ru.lumastack.lumawork.domain.coordination

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test
import ru.lumastack.lumawork.domain.simpleone.SimpleOneRequest

class GroupClosedRequestsQueryTest {
    private val today = LocalDate.of(2026, 10, 17)

    @Test fun `empty search shows only yesterday and today with no nearest-day fallback`() {
        val recent = row("today", "17.10.2026 10:00")
        val yesterday = row("yesterday", "16.10.2026 10:00")
        val old = row("old", "15.10.2026 10:00")
        val future = row("future", "18.10.2026 10:00")
        val undated = row("undated", "")
        assertEquals(setOf("today", "yesterday"), queryGroupClosedRequests(
            prepareGroupClosedRequests(listOf(recent, yesterday, old, future, undated)), "", today = today,
        ).map { it.request.id }.toSet())
        assertTrue(queryGroupClosedRequests(prepareGroupClosedRequests(listOf(old)), "", today = today).isEmpty())
    }

    @Test fun `search includes old undated and formatted date aliases`() {
        val items = prepareGroupClosedRequests(listOf(row("old", "15.10.2026 10:00"), row("undated", "")))
        assertEquals("undated", queryGroupClosedRequests(items, "undated", today = today).single().request.id)
        assertEquals("old", queryGroupClosedRequests(items, "15 октября", today = today).single().request.id)
    }

    @Test fun `type keys use raw first whitespace token and available types span whole archive`() {
        val records = listOf(
            row("a", "17.10.2026 10:00").copy(requestType = "  serviceStd\tСервисная"),
            row("old", "15.10.2026 10:00").copy(requestType = "install"),
            row("none", "17.10.2026 10:00").copy(requestType = "", tableFields = mapOf("Тип заявки" to "returnEquip")),
        )
        assertEquals("servicestd", groupClosedRequestTypeKey(records.first()))
        assertEquals("", groupClosedRequestTypeKey(records.last()))
        assertEquals(setOf("servicestd", "install", ""), groupClosedRequestTypes(records).map { it.first }.toSet())
        assertEquals("none", queryGroupClosedRequests(prepareGroupClosedRequests(records), "", setOf("servicestd"), today).single().request.id)
    }

    @Test fun `group native state ignores personal closure inference and multicard state`() {
        val item = prepareGroupClosedRequests(listOf(row("a", "17.10.2026 10:00").copy(
            state = "Закрыта", stateRaw = "closed", multicardState = "Отменена", closureCode = "Нет решения",
        ))).single()
        assertEquals("Закрыта", item.statusText)
        assertFalse(item.searchText.contains("отказ"))
        assertEquals("Выполнена", prepareGroupClosedRequests(listOf(item.request.copy(state = "completed"))).single().statusText)
        assertEquals("Закрыта", prepareGroupClosedRequests(listOf(item.request.copy(state = "closed"))).single().statusText)
    }

    private fun row(id: String, date: String) = SimpleOneRequest(id, "REQ-$id", resolvedAt = date)
}
