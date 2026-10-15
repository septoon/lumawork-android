package ru.lumastack.lumawork.data.simpleone

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmployeeParsingTest {
    @Test
    fun `employee parser keeps c tgr database id and display title`() {
        val item = json("""
            {
              "sys_id": "employee-1",
              "first_name": "Иван",
              "last_name": "Петров",
              "c_tgr": {"database_value": "city-1", "display_value": "Алушта"},
              "active": {"value": "1"}
            }
        """)

        val employee = parseEmployee(item)

        assertEquals("employee-1", employee.sysId)
        assertEquals("Алушта", employee.address?.title)
        assertEquals("city-1", employee.address?.sysId)
        assertEquals(true, employee.isActive)
    }

    @Test
    fun `detail parser excludes password and hidden fields`() {
        val item = json("""
            {
              "sections": [{
                "name": "Учётная запись",
                "elements": [
                  {"system_name":"username","sys_column_name":"username","name":"Логин","value":"ivan"},
                  {"system_name":"password_hash","sys_column_name":"password_hash","name":"Пароль","value":"secret"},
                  {"system_name":"internal","sys_column_name":"internal","name":"Скрытое","hidden":true,"value":"secret"}
                ]
              }]
            }
        """)

        val sections = parseEmployeeSections(item)

        assertEquals(1, sections.size)
        assertEquals(listOf("Логин"), sections.single().fields.map { it.title })
        assertFalse(sections.single().fields.any { it.value == "secret" })
    }

    @Test
    fun `address parser rejects incomplete reference records`() {
        assertTrue(parseEmployeeAddress(json("""{"sys_id":"city"}""")) == null)
    }

    private fun json(value: String) = Json.parseToJsonElement(value).jsonObject
}
