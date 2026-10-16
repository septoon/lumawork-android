package ru.lumastack.lumawork.data.workschedule

import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test
import ru.lumastack.lumawork.data.simpleone.SimpleOneFailure
import ru.lumastack.lumawork.domain.workschedule.*

/** Payloads ported from iOS scripts/tests/test-work-schedule-parsing.swift. */
class WorkScheduleParserTest {
    private val journal = WorkScheduleJournal("179027737109775240", "164491063496730067", "Алушта", 2026, 10)
    @Test fun `journal references and Russian months retain database and display identities`() {
        val journals = WorkScheduleParser.journals(json("""{"data":{"items":[
          {"sys_id":"179027737109775240","city":{"value":{"database_value":"164491063496730067","display_value":"Алушта"}},"year":{"value":{"database_value":"2026","display_value":"2026"}},"month":{"value":{"database_value":"10","display_value":"Октябрь"}}},
          {"sys_id":{"value":"yalta-september"},"city":{"value":{"database_value":"yalta-id","display_value":"Ялта"}},"year":"2026","month":"Сентябрь"}
        ]}}"""))
        assertEquals(journal, journals.first())
        assertEquals(9, journals[1].month)
        assertEquals("yalta-id", journals[1].cityId)
    }
    @Test fun `active employee count excludes supplied aggregate and inactive shifts`() {
        val schedule = WorkScheduleParser.schedule(json("""{"data":{"daysInMonth":31,"scheduleData":{
          "Алексей Мартынов (Martynov.AV)":{"employeeSysId":"employee-1","totalHours":47,"1":{"hoursWork":9,"isActive":true},"2":{"hoursWork":14.0,"isActive":true},"3":{"hoursWork":24,"isActive":true}},
          "Антон Малышев (Malyshev.AG)":{"employeeSysId":"employee-2","totalHours":"9","1":{"hoursWork":"9","isActive":false}},
          "Всего работающих":{"1":{"hoursWork":99,"isActive":true}}
        }}}"""), journal)
        assertEquals(2, schedule.employees.size)
        val employee = schedule.employees.first { it.id == "employee-1" }
        assertEquals("Алексей Мартынов", employee.name)
        assertEquals("Martynov.AV", employee.login)
        assertEquals(listOf(9.0,14.0,24.0), employee.dayValues.map { it.hours })
        assertEquals(47.0, employee.totalHours, 0.0)
        assertEquals(listOf(1,1,1,0), schedule.dailyTotals.take(4).map { it.count })
        assertFalse(schedule.canReuse(false))
    }
    @Test fun `wrapped widget JSON handles comma hours leap month and malformed shifts`() {
        val schedule = WorkScheduleParser.schedule(json("""{"data":{"daysInMonth":{"value":"99"},"scheduleData":{"value":"{\"Иван Иванов (Ivanov.II)\":{\"employeeSysId\":\"employee\",\"1\":{\"hoursWork\":\"не число\",\"isActive\":true},\"2\":{\"hoursWork\":\"9,5\",\"isActive\":\"yes\"}}}"}}}"""), journal.copy(year = 2024, month = 2))
        assertEquals(29, schedule.daysInMonth)
        assertEquals(listOf(2), schedule.employees.single().dayValues.map { it.day })
        assertEquals(9.5, schedule.employees.single().totalHours, 0.0)
    }
    @Test fun `schedule widget chosen among generic widgets and audit date interpreted as UTC`() {
        val response = json("""{"data":{"sections":[{"elements":[
          {"widget_instance_id":"generic-widget","client_script":"data.title = 'Карточка'"},
          {"widget_instance_id":{"value":"schedule-widget"},"client_script":"data.scheduleData = input.scheduleData; data.daysInMonth = 31"},
          {"sys_column_name":"sys_created_by","value":{"database_value":"created-by-id","display_value":"Admin  System (admin)"}},
          {"sys_column_name":"sys_created_at","value":"2026-08-24 18:06:26"},
          {"sys_column_name":"sys_updated_by","value":{"display_value":"Евгений Рубцов (Rubtsov.ES)"}},
          {"sys_column_name":"sys_updated_at","value":"2026-09-25 12:32:47"}
        ]}]}}""")
        assertEquals("schedule-widget", WorkScheduleParser.widgetInstanceId(response))
        val info = WorkScheduleParser.auditInfo(response)!!
        assertEquals("Admin System (admin)", info.createdBy)
        assertEquals(Instant.parse("2026-08-24T18:06:26Z").toEpochMilli(), info.createdAtEpochMillis)
        assertEquals(Instant.parse("2026-09-25T12:32:47Z").toEpochMilli(), info.updatedAtEpochMillis)
        val schedule = WorkScheduleParser.schedule(json("""{"data":{"scheduleData":{"Всего работающих":{}}}}"""), journal, info)
        assertTrue(schedule.employees.isEmpty())
        assertTrue(schedule.canReuse(false))
        assertFalse(schedule.canReuse(true))
    }
    @Test(expected = SimpleOneFailure.InvalidResponse::class)
    fun `missing schedule is failure rather than empty schedule`() {
        WorkScheduleParser.schedule(json("""{"data":{"unrelated":true}}"""), journal)
    }
    @Test(expected = SimpleOneFailure.InvalidResponse::class)
    fun `ambiguous generic widgets cannot select arbitrary widget`() {
        WorkScheduleParser.widgetInstanceId(json("""{"data":[{"widget_instance_id":"one"},{"widget_instance_id":"two"}]}"""))
    }
    private fun json(value: String) = Json.parseToJsonElement(value).jsonObject
}
