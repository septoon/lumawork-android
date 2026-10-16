package ru.lumastack.lumawork.domain.fuel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FuelModelsTest {
    @org.junit.Test fun `home refueling permits a zero cost or zero liters like iOS`() {
        val draft = FuelDraft(fuelType = "АИ-95", liters = "10,5", fuelCost = "0")
        org.junit.Assert.assertNull(validateRefuelingDraft(draft))
        org.junit.Assert.assertNull(validateRefuelingDraft(draft.copy(liters = "0", fuelCost = "800")))
        org.junit.Assert.assertNotNull(validateRefuelingDraft(draft.copy(liters = "0")))
        org.junit.Assert.assertNotNull(validateRefuelingDraft(draft.copy(liters = "NaN")))
        org.junit.Assert.assertNotNull(validateRefuelingDraft(draft.copy(fuelCost = "-1")))
    }

    @Test
    fun `summary matches base norm and compensation rules`() {
        val summary = FuelSummaryCalculator.build(
            listOf(FuelRecord(id = "1", date = "2026-08-12", mileage = 100.0, liters = 8.0, fuelCost = 600.0)),
        )

        assertEquals(100.0, summary.totals.totalMileage, 0.001)
        assertEquals(9.4, summary.totals.fuelNorm, 0.001)
        assertEquals(1.4, summary.totals.adjustedFuelDiff, 0.001)
        assertEquals(500.0, summary.totals.totalCompensation, 0.001)
    }

    @Test
    fun `special month end applies seven percent rate increase`() {
        val summary = FuelSummaryCalculator.build(
            listOf(FuelRecord(date = "2026-03-31", mileage = 100.0)),
        )

        assertEquals(10.058, summary.totals.fuelNorm, 0.001)
    }

    @Test
    fun `debt amount converts to liters and reduces compensation`() {
        val summary = FuelSummaryCalculator.build(
            listOf(
                FuelRecord(date = "2026-08-12", mileage = 100.0, liters = 10.0),
                FuelRecord(
                    date = "2026-08-01",
                    recordType = FuelRecordType.Adjustment,
                    adjustmentKind = FuelAdjustmentKind.DebtDeduction,
                    monthKey = "2026-08",
                    amount = 76.0,
                ),
            ),
        )

        assertEquals(1.0, summary.totals.effectiveDebtDeductionLiters, 0.001)
        assertEquals(76.0, summary.totals.effectiveDebtDeductionAmount, 0.001)
        assertEquals(424.0, summary.totals.netCompensation, 0.001)
        assertEquals(0.4, summary.totals.adjustedFuelDiff, 0.001)
    }

    @Test
    fun `validation requires paired refueling values for excel dates`() {
        assertEquals(
            "Укажите сумму заправки для Excel-отчёта.",
            validateFuelDraft(FuelDraft(date = "2026-04-01", liters = "10")),
        )
        assertNull(validateFuelDraft(FuelDraft(date = "2026-04-01", liters = "10", fuelCost = "760")))
        assertEquals(
            "Для вычета долга укажите сумму или литры.",
            validateFuelDraft(
                FuelDraft(
                    recordType = FuelRecordType.Adjustment,
                    adjustmentKind = FuelAdjustmentKind.DebtDeduction,
                    monthKey = "2026-08",
                ),
            ),
        )
    }
}
