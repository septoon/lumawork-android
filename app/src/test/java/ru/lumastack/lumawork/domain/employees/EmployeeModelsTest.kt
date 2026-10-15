package ru.lumastack.lumawork.domain.employees

import org.junit.Assert.assertEquals
import org.junit.Test

class EmployeeModelsTest {
    @Test
    fun `search handles Russian case and yo`() {
        val employee = Employee(
            sysId = "1",
            firstName = "Фёдор",
            lastName = "Инженеров",
            login = "engineer",
        )

        assertEquals(listOf(employee), filterEmployees(listOf(employee), "ФЕДОР"))
    }

    @Test
    fun `condition value strips SimpleOne operators`() {
        assertEquals("Алушта Иван", sanitizeEmployeeConditionValue("^(Алушта)\nИван"))
    }

    @Test
    fun `full name and initials follow iOS order`() {
        val employee = Employee(sysId = "1", firstName = "Иван", lastName = "Петров", middleName = "Сергеевич")

        assertEquals("Петров Иван Сергеевич", employee.fullName)
        assertEquals("ИП", employee.initials)
    }
}
