package ru.lumastack.lumawork.data.salary

import org.junit.Assert.assertEquals
import org.junit.Test

class SalaryProjectionTest {
    @Test fun `analytics uses all months while kind filters affect only selected total`() {
        val records = (1..7).map { month -> SalaryEntry(date = "2026-0$month-20", periodMonth = "2026-0$month", amount = 100.0, kind = SalaryKind.Salary) } +
            SalaryEntry(date = "2026-07-25", amount = 70.0, kind = SalaryKind.Gsm)
        val projection = SalaryProjection(salaryMonths(records))
        assertEquals("2026-07", projection.latest?.key)
        assertEquals(110.0, projection.average, .001)
        assertEquals(8.0 / 7, projection.averagePaymentCount, .001)
        assertEquals(700.0, projection.filteredTotal(setOf(SalaryKind.Gsm)), .001)
        assertEquals(770.0, projection.total, .001)
        assertEquals(0.0, projection.filteredTotal(SalaryKind.entries.toSet()), .001)
    }
}
