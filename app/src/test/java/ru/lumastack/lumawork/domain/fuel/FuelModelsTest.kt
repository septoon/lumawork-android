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
    @Test fun `record norm override takes precedence over the seasonal fallback including zero`() {
        val summary = FuelSummaryCalculator.build(listOf(
            FuelRecord(date = "2026-03-31", mileage = 100.0, fuelConsumptionRate = 12.0),
            FuelRecord(date = "2026-03-31", mileage = 100.0, fuelConsumptionRate = 0.0),
        ))
        assertEquals(12.0, summary.totals.fuelNorm, 0.0001)
        assertEquals(6.0, summary.monthly.single().approvedRate, 0.0001)
    }

    @Test fun `debt conversions respect explicit zeros and both supplied units`() {
        val summary = FuelSummaryCalculator.build(listOf(
            FuelRecord(date = "2026-06-10", mileage = 100.0),
            adjustment("2026-06-01", "2026-06", amount = 0.0, liters = 10.0),
            adjustment("2026-06-02", "2026-06", amount = 152.0),
            adjustment("2026-06-03", "2026-06", liters = 1.0),
        ))
        assertEquals(228.0, summary.totals.effectiveDebtDeductionAmount, 0.0001)
        assertEquals(13.0, summary.totals.effectiveDebtDeductionLiters, 0.0001)
        assertEquals(152.0, summary.totals.totalDebtDeductionAmount, 0.0001)
        assertEquals(11.0, summary.totals.totalDebtDeductionLiters, 0.0001)
        org.junit.Assert.assertTrue(summary.totals.hasEstimatedDebtDeductionAmount)
        org.junit.Assert.assertTrue(summary.totals.hasEstimatedDebtDeductionLiters)
        assertEquals(272.0, summary.monthly.single().projectedPayout, 0.0001)
    }

    @Test fun `monthly carryover retains incoming record order but total uses month and date order`() {
        val summary = FuelSummaryCalculator.build(listOf(
            adjustment("2026-06-30", "2026-06", amount = 0.0, carryover = 200.0),
            adjustment("2026-06-01", "2026-06", amount = 0.0, carryover = 50.0),
            FuelRecord(date = "2026-07-12", mileage = 100.0),
        ))
        assertEquals(200.0, summary.totals.carryoverDebtRub, 0.0001)
        assertEquals(50.0, summary.monthly.last().incomingCarryoverDebtRub, 0.0001)
        assertEquals(450.0, summary.monthly.last().projectedPayout, 0.0001)
        assertEquals("Удержание", summary.monthly.last().compensationStatusLabel)
    }

    @Test fun `carryover does not jump a missing calendar month and paid excess stays negative`() {
        val records = listOf(
            adjustment("2026-05-31", "2026-05", amount = 0.0, carryover = 300.0),
            FuelRecord(date = "2026-07-12", mileage = 100.0),
            FuelRecord(date = "2026-07-01", recordType = FuelRecordType.Adjustment, monthKey = "2026-07",
                adjustmentKind = FuelAdjustmentKind.CompensationPayment, amount = 600.0),
        )
        val july = FuelSummaryCalculator.build(records).monthly.last()
        assertEquals(0.0, july.incomingCarryoverDebtRub, 0.0001)
        assertEquals(-100.0, july.projectedPayout, 0.0001)
        assertEquals(600.0, july.effectiveAppliedCompensation, 0.0001)
        org.junit.Assert.assertTrue(july.isCompensationClosed)
        assertEquals("Закрыто", july.compensationStatusLabel)
    }

    @Test fun `draft validation rejects malformed supplied values and impossible months`() {
        val mileage = FuelDraft(date = "2026-08-01", mileage = "100")
        for (value in listOf("NaN", "Infinity", "-Infinity", "nope")) {
            org.junit.Assert.assertNotNull(validateFuelDraft(mileage.copy(mileage = value)))
            org.junit.Assert.assertNotNull(validateFuelDraft(mileage.copy(liters = value, fuelCost = "100")))
            org.junit.Assert.assertNotNull(validateFuelDraft(FuelDraft(recordType = FuelRecordType.Adjustment,
                monthKey = "2026-08", amount = "100", carryoverDebtRub = value)))
        }
        for (month in listOf("2026-00", "2026-13", "bad")) {
            org.junit.Assert.assertNotNull(validateFuelDraft(FuelDraft(recordType = FuelRecordType.Adjustment,
                monthKey = month, amount = "100")))
        }
        assertNull(validateFuelDraft(mileage.copy(mileage = " 100,5 ")))
        assertNull(validateFuelDraft(FuelDraft(recordType = FuelRecordType.Adjustment, monthKey = "2026-08", amount = "0")))
    }

    @Test fun `fuel month comes from date while adjustment month preserves explicit legacy key`() {
        val fuel = FuelRecord(date = " 2026-06-10 ", monthKey = "2025-12", mileage = 100.0)
        assertEquals("2026-06", fuel.resolvedMonthKey)
        val adjustment = adjustment(" 2026-06-10 ", "2025-12", amount = 50.0)
        assertEquals("2025-12", adjustment.resolvedMonthKey)
        val summary = FuelSummaryCalculator.build(listOf(fuel, adjustment))
        assertEquals(listOf("2025-12", "2026-06"), summary.monthly.map { it.key })
        assertEquals(100.0, summary.monthly.last().totalMileage, 0.0001)
    }

    private fun adjustment(date: String, month: String, amount: Double? = null, liters: Double? = null, carryover: Double? = null) =
        FuelRecord(date = date, recordType = FuelRecordType.Adjustment, adjustmentKind = FuelAdjustmentKind.DebtDeduction,
            monthKey = month, amount = amount, liters = liters, carryoverDebtRub = carryover)

}
