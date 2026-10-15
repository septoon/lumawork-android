package ru.lumastack.lumawork.data.timereport

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.lumastack.lumawork.data.simpleone.makeSimpleOneXlsx

class TimeReportParserTest {
    @Test
    fun `xlsx parser maps dates durations and work details`() {
        val dateSerial = LocalDate.of(2026, 8, 12).toEpochDay() -
            LocalDate.of(1899, 12, 30).toEpochDay() + 0.5
        val xlsx = makeSimpleOneXlsx(
            headers = listOf(
                "Задача",
                "Когда создано",
                "Время работ",
                "Время в дороге",
                "Внеурочные работы (ч.)",
                "Внеурочные работы (м)",
                "Рабочие заметки",
                "Исполнитель",
            ),
            rows = listOf(
                listOf(
                    "SUTS001",
                    dateSerial.toString(),
                    "3600000",
                    "1 hour 15 minutes",
                    "1",
                    "30",
                    "Работы выполнены",
                    "Иван Инженеров",
                ),
            ),
        )

        val entry = TimeReportParser.parseXlsx(xlsx).single()

        assertEquals("SUTS001", entry.activity)
        assertEquals("2026-08-12 12:00:00", entry.createdAt)
        assertEquals(60, entry.workMinutes)
        assertEquals(75, entry.travelMinutes)
        assertEquals(90, entry.overtimeMinutes)
        assertEquals("Работы выполнены", entry.notes)
        assertEquals("Иван Инженеров", entry.executor)
    }

    @Test
    fun `duration parser matches iOS numeric and localized formats`() {
        assertEquals(90, TimeReportParser.parseDurationMinutes("90"))
        assertEquals(60, TimeReportParser.parseDurationMinutes("3600000"))
        assertEquals(95, TimeReportParser.parseDurationMinutes("1 час 35 минут"))
        assertEquals(90, TimeReportParser.parseDurationMinutes("1.5 h"))
        assertTrue(TimeReportParser.parseDurationMinutes("") == 0)
    }
}
