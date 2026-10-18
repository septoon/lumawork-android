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
    fun `list fields and day grouping match actual work date in iOS`() {
        val record = kotlinx.serialization.json.Json.parseToJsonElement("""{
          "sys_id":"record-1", "acticvity":"SUTS001", "month":"Август",
          "sys_created_at":"12.08.2026 10:30:00", "date_of_work":"31.07.2026 09:00:00",
          "time_of_work_hours":"2", "time_of_work_minutes":"15",
          "travel_time_hours":"1", "travel_time_minutes":"5",
          "extracurricular_activities_hours":"3", "extracurricular_work":"1"
        }""") as kotlinx.serialization.json.JsonObject
        val entry = requireNotNull(TimeReportParser.parseRecord(record))
        assertEquals(135, entry.workMinutes)
        assertEquals(65, entry.travelMinutes)
        assertEquals(180, entry.overtimeMinutes)
        assertTrue(entry.isOvertime)
        assertEquals("Август", entry.period)
        assertEquals(LocalDate.of(2026, 7, 31), ru.lumastack.lumawork.domain.timereport.timeReportDaySummaries(listOf(entry)).single().date)
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
