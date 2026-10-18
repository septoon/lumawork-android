package ru.lumastack.lumawork.ui.analytics

import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.*
import org.junit.Test
import ru.lumastack.lumawork.domain.requests.*
import ru.lumastack.lumawork.domain.simpleone.SimpleOneRequest

class AnalyticsTest {
    private fun completed(id: String, date: String, type: String = "serviceStd") = SimpleOneRequest(id, id,
        resolvedAt = date, requestType = type, closureCode = "  РЕШЕНО   С ВЫЕЗДОМ на место")
    private fun analytics(vararg requests: SimpleOneRequest) = buildClosedRequestsAnalytics(prepareClosedRequests(requests.toList()))

    @Test fun `completed analytics excludes refusals and returns even when their closure code matches`() {
        val done = completed("done", "15.10.2026 18:53")
        val value = analytics(done, done.copy(sysId = "refused", closureCode = "Не решено", resolvedAt = "15.09.2026 12:00"),
            done.copy(sysId = "return", requestType = "returnEquip", resolvedAt = "01.08.2026 12:00"))
        assertEquals(1, value.completedCount)
        assertEquals(1, value.returnEquipCount)
        assertEquals(listOf("2026-10"), value.months.map { it.id })
        assertEquals(1, value.months.single().count)
        assertEquals(listOf(AnalyticsRequestType("Сервисная", 1)), value.requestTypes)
    }

    @Test fun `all months remain available in descending order across years`() {
        val records = (0L..17L).map { index ->
            completed("$index", YearMonth.of(2025, 1).plusMonths(index).atDay(2).toString() + " 12:00:00")
        }
        val value = buildClosedRequestsAnalytics(prepareClosedRequests(records))
        assertEquals(18, value.months.size)
        assertEquals("2026-06", value.latestMonth!!.id)
        assertEquals("2025-01", value.months.last().id)
        assertEquals(18, value.completedCount)
        assertEquals(18, value.requestTypes.single().count)
    }

    @Test fun `undated visits count in total and types but not in calendars and duplicate archive rows count once`() {
        val done = completed("one", "not a date", "install")
        val value = analytics(done, done, completed("two", "2026-10-15 12:00:00", "install"),
            completed("three", "46310.5", "replacement")) // Excel serial = 15 October 2026 noon.
        assertEquals(3, value.completedCount)
        assertEquals(2, value.months.single().count)
        assertEquals("2026-10", value.months.single().id)
        assertEquals(AnalyticsRequestType("Установка", 2), value.topRequestType)
        assertEquals(LocalDate.of(2026, 10, 15), value.topDay!!.date)
        assertEquals(2, value.topDay!!.count)
    }

    @Test fun `ties prefer latest month and day and request types sort by title`() {
        val value = analytics(completed("1", "2026-09-01 12:00:00", "replacement"),
            completed("2", "2026-10-01 12:00:00", "install"))
        assertEquals("2026-10", value.topMonth!!.id)
        assertEquals(LocalDate.of(2026, 10, 1), value.topDay!!.date)
        assertEquals(listOf("Замена", "Установка"), value.requestTypes.map { it.title })
    }

    @Test fun `calendar aligns Monday includes empty days and leap day with complete weeks`() {
        val value = analytics(completed("leap", "29.02.2024 9:05"))
        val calendar = value.months.single().calendar
        assertEquals(35, calendar.size)
        assertTrue(calendar.take(3).all { it.date == null })
        assertEquals(LocalDate.of(2024, 2, 1), calendar[3].date)
        assertEquals(0, calendar[3].count)
        assertEquals(LocalDate.of(2024, 2, 29), calendar[31].date)
        assertEquals(1, calendar[31].count)
        assertTrue(calendar.takeLast(3).all { it.date == null })
        assertEquals(1, calendar.sumOf { it.count })
    }

    @Test fun `archive table fallbacks and Russian return aliases match the closed screen`() {
        val value = analytics(SimpleOneRequest("table", "R", tableFields = mapOf("Тип заявки" to "install",
            "Код закрытия" to "Решено с выездом", "Время решения" to "15.10.2026 18:00")),
            completed("returned", "15.10.2026 12:00", "Возврат оборудования"),
            completed("unknown", "15.10.2026 12:00", ""))
        assertEquals(2, value.completedCount)
        assertEquals(1, value.returnEquipCount)
        assertEquals(listOf("Не указан", "Установка"), value.requestTypes.map { it.title })
        assertEquals(2, value.months.single().count)
    }

    @Test fun `empty and returns-only archives have no latest top or calendar data`() {
        assertEquals(ClosedRequestsAnalytics(), analytics())
        val value = analytics(completed("return", "2026-10-15 12:00", "return_equip"))
        assertEquals(0, value.completedCount)
        assertEquals(1, value.returnEquipCount)
        assertTrue(value.months.isEmpty())
        assertTrue(value.requestTypes.isEmpty())
        assertNull(value.latestMonth)
        assertNull(value.topMonth)
        assertNull(value.topDay)
    }
}
