package ru.lumastack.lumawork.data.salary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlinx.serialization.json.Json

class SalaryModelsTest {
    @Test fun `vacation records decode without losing the salary archive and keep their period in the editor`() {
        val entry = Json.decodeFromString<SalaryEntry>("""{"date":"2026-10-15","amount":12345.0,"kind":"vacation","vacationStartDate":"2026-10-19","vacationEndDate":"2026-10-30"}""")
        val draft = SalaryDraft(entry)
        assertEquals("Отпускные", entry.resolvedKind.title)
        assertEquals("2026-10-19", draft.vacationStartDate)
        assertEquals("2026-10-30", draft.vacationEndDate)
        assertNull(validateSalaryDraft(draft))
    }

    @Test fun `vacation validates actual dates and chronological bounds like iOS`() {
        val draft = SalaryDraft(date = "2026-10-15", amount = "100", kind = SalaryKind.Vacation)
        assertEquals("Укажите начало и окончание отпуска.", validateSalaryDraft(draft))
        assertEquals("Укажите начало и окончание отпуска.", validateSalaryDraft(draft.copy(vacationStartDate = "2026-02-30", vacationEndDate = "2026-03-03")))
        assertEquals("Окончание отпуска не может быть раньше начала.", validateSalaryDraft(draft.copy(vacationStartDate = "2026-10-30", vacationEndDate = "2026-10-19")))
        assertNull(validateSalaryDraft(draft.copy(vacationStartDate = "2026-10-19", vacationEndDate = "2026-10-19")))
    }
    @Test fun `legacy payout applies thirteen percent tax rounded down`() {
        val entry = SalaryEntry(date = "2026-05-12", baseSalary = 10_001.0, weekendPay = 999.0)

        assertEquals(9_570.0, entry.payout, 0.001)
    }

    @Test fun `new records use net amount and explicit accrual month`() {
        val entry = SalaryEntry(date = "2026-06-05", periodMonth = "2026-05", amount = 42_500.0, kind = SalaryKind.Advance)

        assertEquals("2026-05", entry.accrualMonthKey)
        assertEquals(42_500.0, entry.payout, 0.001)
        assertEquals(SalaryKind.Advance, entry.resolvedKind)
    }

    @Test fun `draft validation rejects malformed month and accepts valid net payout`() {
        assertEquals("Укажите месяц в формате ГГГГ-ММ.", validateSalaryDraft(SalaryDraft(date = "2026-06-10", periodMonth = "06.2026", amount = "100")))
        assertNull(validateSalaryDraft(SalaryDraft(date = "2026-06-10", periodMonth = "2026-05", amount = "100")))
    }
}
