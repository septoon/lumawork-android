package ru.lumastack.lumawork.domain.fuel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FuelAnalyticsTest {
    @Test fun `current projection excludes archived adjustments by date rather than month`() {
        val archived = FuelRecord(date = " 2026-05-12 ", mileage = 100.0)
        val current = FuelRecord(date = " 2026-05-13 ", mileage = 20.0)
        val archivedAdjustment = FuelRecord(date = "2026-05-01", recordType = FuelRecordType.Adjustment,
            monthKey = "2026-06", adjustmentKind = FuelAdjustmentKind.CompensationPayment, amount = 500.0)
        val projection = FuelProjection.current(listOf(archived, current, archivedAdjustment))
        assertEquals(listOf(current), projection.allRecords)
        assertEquals(20.0, projection.summary.totals.totalMileage, 0.0001)
        assertEquals(0.0, projection.summary.totals.totalPaidCompensation, 0.0001)
        assertTrue(FuelArchivePolicy.isArchived(archivedAdjustment))
        assertFalse(FuelArchivePolicy.isArchived(current))
    }

    @Test fun `archive access requires the configured owner exact normalized email`() {
        assertTrue(FuelArchivePolicy.isAvailable(" Owner@example.com ", " owner@EXAMPLE.com "))
        assertFalse(FuelArchivePolicy.isAvailable("owner@example.com.other", "owner@example.com"))
        assertFalse(FuelArchivePolicy.isAvailable("", null))
        assertFalse(FuelArchivePolicy.isAvailable("", "  "))
    }

    @Test fun `analytics uses weighted price for positive monthly totals and includes archive`() {
        val records = listOf(
            FuelRecord(date = "2025-12-10", liters = 10.0, fuelCost = 600.0, mileage = 100.0),
            FuelRecord(date = "2026-06-10", liters = 30.0, fuelCost = 2400.0, mileage = 400.0),
            FuelRecord(date = "2026-07-10", liters = 20.0, fuelCost = 0.0),
            FuelRecord(date = "2026-08-10", liters = 0.0, fuelCost = 200.0),
        )
        val analytics = FuelAnalytics(records)
        assertEquals(75.0, analytics.overallPricePerLiter, 0.0001)
        assertEquals(12.0, analytics.consumption, 0.0001)
        assertEquals(3200.0, analytics.fuelCost, 0.0001)
        assertEquals(1, analytics.archiveCount)
        assertEquals(4, analytics.recordCount)
        assertEquals(2, analytics.pricePoints.size)
        assertEquals(listOf("2025-12", "2026-06"), analytics.pricePoints.map { it.month.key })
        assertEquals(listOf("2026", "2025"), analytics.projection.years)
        val filtered = FuelAnalytics(records, "2026")
        assertEquals(80.0, filtered.overallPricePerLiter, 0.0001)
        assertEquals(3, filtered.months.size)
        assertEquals(4, filtered.recordCount)
        assertEquals(1, filtered.archiveCount)
    }

    @Test fun `analytics remaining uses applied deductions rather than projected carryover payout`() {
        val records = listOf(
            FuelRecord(date = "2026-06-01", recordType = FuelRecordType.Adjustment, monthKey = "2026-06",
                adjustmentKind = FuelAdjustmentKind.DebtDeduction, amount = 0.0, carryoverDebtRub = 300.0),
            FuelRecord(date = "2026-07-10", mileage = 100.0),
        )
        val july = FuelAnalytics(records, "2026").months.first()
        assertEquals(200.0, july.projectedPayout, 0.0001)
        assertEquals(500.0, FuelAnalytics(records).remaining, 0.0001)
    }
}
