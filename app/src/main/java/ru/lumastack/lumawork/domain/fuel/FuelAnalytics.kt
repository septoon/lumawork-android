package ru.lumastack.lumawork.domain.fuel

/** The date boundary applies to every record, including adjustments with another monthKey. */
object FuelArchivePolicy {
    const val CURRENT_RECORDS_START_DATE = "2026-05-13"
    const val ARCHIVED_RECORDS_TITLE = "Архив до 13 мая 2026"

    fun isCurrent(record: FuelRecord): Boolean = record.date.trim() >= CURRENT_RECORDS_START_DATE
    fun isArchived(record: FuelRecord): Boolean = record.date.trim() < CURRENT_RECORDS_START_DATE
    fun isAvailable(userEmail: String, ownerEmail: String?): Boolean =
        !ownerEmail.isNullOrBlank() && userEmail.trim().equals(ownerEmail.trim(), ignoreCase = true)
}

class FuelProjection(records: List<FuelRecord> = emptyList()) {
    val summary: FuelSummary = FuelSummaryCalculator.build(records)
    val years: List<String> = FuelSummaryCalculator.years(summary)
    val allRecords: List<FuelRecord> = records.sortedByDescending(FuelRecord::date)
    fun months(selectedYear: String?): List<FuelSummaryMonth> = FuelSummaryCalculator.months(summary, selectedYear)

    companion object {
        fun current(records: List<FuelRecord> = emptyList()): FuelProjection = FuelProjection(records.filter(FuelArchivePolicy::isCurrent))
    }
}

data class FuelMonthlyPricePoint(val month: FuelSummaryMonth) {
    val liters: Double get() = month.totalLiters
    val cost: Double get() = month.fuelCost
    val pricePerLiter: Double get() = cost / liters
}

/** Analytics intentionally includes the archive; price points require both positive cost and liters. */
class FuelAnalytics(records: List<FuelRecord>, selectedYear: String? = null) {
    val projection = FuelProjection(records)
    val months = projection.months(selectedYear)
    val recordCount = records.size
    val archiveCount = records.count(FuelArchivePolicy::isArchived)
    val pricePoints = months.filter { it.totalLiters > 0 && it.fuelCost > 0 }
        .sortedBy(FuelSummaryMonth::key).map(::FuelMonthlyPricePoint)
    val overallPricePerLiter: Double = pricePoints.sumOf(FuelMonthlyPricePoint::liters).let { liters ->
        if (liters > 0) pricePoints.sumOf(FuelMonthlyPricePoint::cost) / liters else 0.0
    }
    val mileage = months.sumOf(FuelSummaryMonth::totalMileage)
    val liters = months.sumOf(FuelSummaryMonth::totalLiters)
    val consumption = if (mileage > 0) liters / mileage * 100 else 0.0
    val fuelCost = months.sumOf(FuelSummaryMonth::fuelCost)
    val compensation = months.sumOf(FuelSummaryMonth::compensation)
    val paid = months.sumOf(FuelSummaryMonth::paidCompensation)
    val deducted = months.sumOf(FuelSummaryMonth::effectiveDebtDeductionAmount)
    val remaining = compensation - paid - deducted
    val averageNorm = months.sumOf(FuelSummaryMonth::approvedRate) / maxOf(months.size, 1)
    val maximumConsumptionScale = maxOf(months.maxOfOrNull { month ->
        maxOf(if (month.totalMileage > 0) month.totalLiters / month.totalMileage * 100 else 0.0, month.approvedRate)
    }?.times(1.12) ?: 0.0, 1.0)
}
