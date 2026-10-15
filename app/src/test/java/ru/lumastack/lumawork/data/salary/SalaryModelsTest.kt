package ru.lumastack.lumawork.data.salary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SalaryModelsTest {
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
