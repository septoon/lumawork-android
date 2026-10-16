package ru.lumastack.lumawork.domain.employees

import org.junit.Assert.assertEquals
import org.junit.Test

class EmployeeModelsTest {
    @Test fun `tenure uses hire date from dossier with Russian inflections`() {
        val employee = Employee("1", detailSections = listOf(EmployeeSection("main", "Кадры", listOf(EmployeeField("hire_date", "Дата приёма", "15.07.2020")))))
        assertEquals("6 лет 3 месяца 2 дня", employee.tenureText(java.time.LocalDate.of(2026, 10, 17)))
        assertEquals(null, employee.tenureText(java.time.LocalDate.of(2019, 1, 1)))
        assertEquals(null, Employee("2").tenureText())
    }

    @Test fun `tenure parses only real dates and accepts ISO and Russian dates`() {
        fun person(date: String) = Employee("1", detailSections = listOf(EmployeeSection("s", "Кадры", listOf(EmployeeField("employment_start", "Начало", date)))))
        val today = java.time.LocalDate.of(2026, 10, 17)
        assertEquals("0 дней", person("17 октября 2026").tenureText(today))
        assertEquals("1 день", person("2026-10-16T10:00:00Z").tenureText(today, java.time.ZoneOffset.UTC))
        assertEquals(null, person("31.02.2026").tenureText(today))
        assertEquals("сотрудников", employeeCountWord(11, "сотрудник", "сотрудника", "сотрудников"))
        assertEquals("сотрудник", employeeCountWord(21, "сотрудник", "сотрудника", "сотрудников"))
    }

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
