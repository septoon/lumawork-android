package ru.lumastack.lumawork.domain.fuel

import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class FuelRecordType {
    @SerialName("fuel") Fuel,
    @SerialName("adjustment") Adjustment,
}

@Serializable
enum class FuelAdjustmentKind {
    @SerialName("compensation_payment") CompensationPayment,
    @SerialName("debt_deduction") DebtDeduction,
}

@Serializable
data class FuelRecord(
    val id: String? = null,
    val recordType: FuelRecordType = FuelRecordType.Fuel,
    val adjustmentKind: FuelAdjustmentKind? = null,
    val monthKey: String? = null,
    val amount: Double? = null,
    val carryoverDebtRub: Double? = null,
    val comment: String? = null,
    val date: String,
    val mileage: Double? = null,
    val liters: Double? = null,
    val fuelCost: Double? = null,
    val fuelType: String? = null,
    val fuelConsumptionRate: Double? = null,
    val source: String? = null,
    val sourceImportId: String? = null,
) {
    val stableId: String
        get() = id ?: "${recordType.name}|${adjustmentKind?.name}|$monthKey|$date|$mileage|$liters|$fuelCost|$fuelType|$amount"

    val resolvedMonthKey: String
        get() = monthKey?.takeIf { recordType == FuelRecordType.Adjustment && it.length == 7 } ?: date.trim().take(7)
}

@Serializable
data class FuelDraft(
    val recordType: FuelRecordType = FuelRecordType.Fuel,
    val adjustmentKind: FuelAdjustmentKind = FuelAdjustmentKind.CompensationPayment,
    val date: String = LocalDate.now().toString(),
    val mileage: String = "",
    val liters: String = "",
    val fuelCost: String = "",
    val monthKey: String = YearMonth.now().toString(),
    val amount: String = "",
    val carryoverDebtRub: String = "",
    val comment: String = "",
    val fuelType: String = "",
) {
    constructor(record: FuelRecord) : this(
        recordType = record.recordType,
        adjustmentKind = record.adjustmentKind ?: FuelAdjustmentKind.CompensationPayment,
        date = record.date,
        mileage = record.mileage.numberString(),
        liters = record.liters.numberString(),
        fuelCost = record.fuelCost.numberString(),
        monthKey = record.monthKey ?: record.date.take(7),
        amount = record.amount.numberString(),
        carryoverDebtRub = record.carryoverDebtRub.numberString(),
        comment = record.comment.orEmpty(),
        fuelType = record.fuelType.orEmpty(),
    )

    val mileageValue: Double? get() = mileage.number()
    val litersValue: Double? get() = liters.number()
    val fuelCostValue: Double? get() = fuelCost.number()
    val amountValue: Double? get() = amount.number()
    val carryoverDebtValue: Double? get() = carryoverDebtRub.number()
}

@Serializable
data class FuelSnapshot(
    val records: List<FuelRecord> = emptyList(),
    val updatedAtEpochMillis: Long? = null,
    val fuelTypes: List<String> = emptyList(),
    val pendingMileageMonths: Set<String> = emptySet(),
)

data class FuelSummaryMonth(
    val key: String,
    val label: String,
    val totalMileage: Double,
    val totalLiters: Double,
    val fuelNorm: Double,
    val fuelCost: Double,
    val fuelDiff: Double,
    val approvedRate: Double,
    val compensation: Double,
    val paidCompensation: Double,
    val effectiveDebtDeductionAmount: Double,
    val effectiveDebtDeductionLiters: Double,
    val incomingCarryoverDebtRub: Double,
    val monthCarryoverDebtRub: Double,
    val projectedPayout: Double,
    val compensationStatusLabel: String,
    val adjustments: List<FuelRecord>,
    val debtDeductionAmount: Double = 0.0,
    val debtDeductionLiters: Double = 0.0,
    val effectiveAppliedCompensation: Double = paidCompensation + effectiveDebtDeductionAmount,
    val remainingCompensation: Double = projectedPayout,
    val incomingCarryoverDebtLiters: Double = incomingCarryoverDebtRub / FuelSummaryCalculator.APPROXIMATE_FUEL_PRICE,
    val monthCarryoverDebtLiters: Double = monthCarryoverDebtRub / FuelSummaryCalculator.APPROXIMATE_FUEL_PRICE,
    val projectedDebtDeductionFromCarryover: Double = compensation - paidCompensation - projectedPayout,
    val isCompensationClosed: Boolean = effectiveAppliedCompensation >= compensation && compensation > 0,
) {
    val diffLabel: String get() = fuelDiff.diffLabel()
}

data class FuelSummaryTotals(
    val totalMileage: Double = 0.0,
    val totalLiters: Double = 0.0,
    val fuelNorm: Double = 0.0,
    val totalFuelCost: Double = 0.0,
    val totalCompensation: Double = 0.0,
    val totalPaidCompensation: Double = 0.0,
    val effectiveDebtDeductionAmount: Double = 0.0,
    val effectiveDebtDeductionLiters: Double = 0.0,
    val carryoverDebtRub: Double = 0.0,
    val netCompensation: Double = 0.0,
    val fuelDiff: Double = 0.0,
    val adjustedFuelDiff: Double = 0.0,
    val totalDebtDeductionAmount: Double = 0.0,
    val totalDebtDeductionLiters: Double = 0.0,
    val hasEstimatedDebtDeductionAmount: Boolean = false,
    val hasEstimatedDebtDeductionLiters: Boolean = false,
    val carryoverDebtLiters: Double = carryoverDebtRub / FuelSummaryCalculator.APPROXIMATE_FUEL_PRICE,
) {
    val diffLabel: String get() = fuelDiff.diffLabel()
    val adjustedDiffLabel: String get() = adjustedFuelDiff.diffLabel()
}

data class FuelSummary(
    val monthly: List<FuelSummaryMonth> = emptyList(),
    val totals: FuelSummaryTotals = FuelSummaryTotals(),
    val explanation: String = "",
    val hasData: Boolean = false,
)

object FuelSummaryCalculator {
    const val FUEL_CONSUMPTION_RATE = 9.4
    const val FUEL_CONSUMPTION_RATE_INCREASE = 0.07
    const val APPROXIMATE_FUEL_PRICE = 76.0
    private val rateIncreaseDates = setOf("2025-12-31", "2026-01-31", "2026-02-28", "2026-03-31")

    fun build(records: List<FuelRecord>): FuelSummary {
        if (records.isEmpty()) return FuelSummary()
        val fuelRecords = records.filter { it.recordType == FuelRecordType.Fuel }
        val adjustments = records.filter { it.recordType == FuelRecordType.Adjustment }
        val monthly = mutableMapOf<String, MutableFuelMonth>()

        fuelRecords.sortedBy(FuelRecord::date).forEach { record ->
            val parts = record.date.trim().split("-").filter(String::isNotEmpty)
            val key = if (parts.size >= 2) "${parts[0]}-${parts[1]}" else "unknown"
            val month = monthly.getOrPut(key) { MutableFuelMonth(key.monthLabel()) }
            val mileage = record.mileage ?: 0.0
            month.mileage += mileage
            month.liters += record.liters ?: 0.0
            month.norm += if (mileage > 0) mileage * (record.fuelConsumptionRate ?: fuelRate(record.date)) / 100 else 0.0
            month.cost += record.fuelCost ?: 0.0
        }
        adjustments.forEach { record -> monthly.getOrPut(record.resolvedMonthKey) { MutableFuelMonth(record.resolvedMonthKey.monthLabel()) } }

        val result = monthly.keys.sorted().map { key ->
            val month = requireNotNull(monthly[key])
            val monthAdjustments = adjustments.filter { it.resolvedMonthKey == key }
            val previousCarryover = previousMonthKey(key)?.let { previous ->
                adjustments.filter {
                    it.resolvedMonthKey == previous && it.adjustmentKind == FuelAdjustmentKind.DebtDeduction
                }.mapNotNull(FuelRecord::carryoverDebtRub).lastOrNull()
            } ?: 0.0
            val paid = monthAdjustments.filter { it.adjustmentKind == FuelAdjustmentKind.CompensationPayment }
                .sumOf { it.amount ?: 0.0 }
            val debt = monthAdjustments.filter { it.adjustmentKind == FuelAdjustmentKind.DebtDeduction }
            val debtAmount = debt.sumOf(::debtAmount)
            val debtLiters = debt.sumOf(::debtLiters)
            val carryover = debt.mapNotNull(FuelRecord::carryoverDebtRub).lastOrNull() ?: 0.0
            val diff = month.norm - month.liters
            val compensation = month.mileage * 5
            val effectiveApplied = paid + debtAmount
            val projectedDeduction = if (debtAmount > 0) debtAmount
                else minOf(maxOf(compensation - paid, 0.0), previousCarryover)
            val payout = compensation - paid - projectedDeduction
            val status = when {
                effectiveApplied >= compensation && compensation > 0 -> "Закрыто"
                effectiveApplied > 0 -> "Частично закрыто"
                previousCarryover > 0 -> "Удержание"
                else -> "К выплате"
            }
            FuelSummaryMonth(
                key = key,
                label = month.label,
                totalMileage = month.mileage,
                totalLiters = month.liters,
                fuelNorm = month.norm,
                fuelCost = month.cost,
                fuelDiff = diff,
                approvedRate = if (month.mileage > 0) month.norm / month.mileage * 100 else 0.0,
                compensation = compensation,
                paidCompensation = paid,
                effectiveDebtDeductionAmount = debtAmount,
                effectiveDebtDeductionLiters = debtLiters,
                incomingCarryoverDebtRub = previousCarryover,
                monthCarryoverDebtRub = carryover,
                projectedPayout = payout,
                compensationStatusLabel = status,
                adjustments = monthAdjustments,
                debtDeductionAmount = debt.sumOf { it.amount ?: 0.0 },
                debtDeductionLiters = debt.sumOf { it.liters ?: 0.0 },
            )
        }

        val totalDebtAmount = adjustments.filter { it.adjustmentKind == FuelAdjustmentKind.DebtDeduction }.sumOf(::debtAmount)
        val totalDebtLiters = adjustments.filter { it.adjustmentKind == FuelAdjustmentKind.DebtDeduction }.sumOf(::debtLiters)
        val totalPaid = adjustments.filter { it.adjustmentKind == FuelAdjustmentKind.CompensationPayment }.sumOf { it.amount ?: 0.0 }
        val carryover = adjustments.filter { it.adjustmentKind == FuelAdjustmentKind.DebtDeduction }
            .sortedWith(compareBy(FuelRecord::resolvedMonthKey, FuelRecord::date))
            .mapNotNull(FuelRecord::carryoverDebtRub).lastOrNull() ?: 0.0
        val norm = result.sumOf(FuelSummaryMonth::fuelNorm)
        val liters = result.sumOf(FuelSummaryMonth::totalLiters)
        val rawDiff = norm - liters
        val adjustedDiff = rawDiff + totalDebtLiters
        val explanation = when {
            adjustedDiff < 0 -> "Перерасход топлива ${formatNumber(-adjustedDiff)} л (с учётом вычетов долга)."
            adjustedDiff > 0 -> "Остаток по норме ${formatNumber(adjustedDiff)} л (с учётом вычетов долга)."
            else -> "Расход соответствует норме."
        }
        val totalCompensation = result.sumOf(FuelSummaryMonth::compensation)
        return FuelSummary(
            monthly = result,
            totals = FuelSummaryTotals(
                totalMileage = result.sumOf(FuelSummaryMonth::totalMileage),
                totalLiters = liters,
                fuelNorm = norm,
                totalFuelCost = result.sumOf(FuelSummaryMonth::fuelCost),
                totalCompensation = totalCompensation,
                totalPaidCompensation = totalPaid,
                effectiveDebtDeductionAmount = totalDebtAmount,
                effectiveDebtDeductionLiters = totalDebtLiters,
                carryoverDebtRub = carryover,
                netCompensation = totalCompensation - totalPaid - totalDebtAmount,
                fuelDiff = rawDiff,
                adjustedFuelDiff = adjustedDiff,
                totalDebtDeductionAmount = adjustments.filter { it.adjustmentKind == FuelAdjustmentKind.DebtDeduction }.sumOf { it.amount ?: 0.0 },
                totalDebtDeductionLiters = adjustments.filter { it.adjustmentKind == FuelAdjustmentKind.DebtDeduction }.sumOf { it.liters ?: 0.0 },
                hasEstimatedDebtDeductionAmount = adjustments.any { it.adjustmentKind == FuelAdjustmentKind.DebtDeduction && it.amount == null && it.liters != null },
                hasEstimatedDebtDeductionLiters = adjustments.any { it.adjustmentKind == FuelAdjustmentKind.DebtDeduction && it.liters == null && it.amount != null },
            ),
            explanation = explanation,
            hasData = result.isNotEmpty() || adjustments.isNotEmpty(),
        )
    }

    fun years(summary: FuelSummary): List<String> = summary.monthly.map { it.key.take(4) }.distinct().sortedDescending()
    fun months(summary: FuelSummary, year: String?): List<FuelSummaryMonth> = summary.monthly
        .filter { year == null || it.key.startsWith(year) }.asReversed()

    private fun fuelRate(date: String): Double = if (date.trim() in rateIncreaseDates) {
        FUEL_CONSUMPTION_RATE * (1 + FUEL_CONSUMPTION_RATE_INCREASE)
    } else FUEL_CONSUMPTION_RATE

    private fun debtAmount(record: FuelRecord): Double = record.amount ?: (record.liters ?: 0.0) * APPROXIMATE_FUEL_PRICE
    private fun debtLiters(record: FuelRecord): Double = record.liters ?: (record.amount ?: 0.0) / APPROXIMATE_FUEL_PRICE
}

fun validateRefuelingDraft(draft: FuelDraft): String? {
    if (draft.fuelType.isBlank()) return "Выберите тип топлива в ГСМ профиле."
    val liters = draft.litersValue
    val cost = draft.fuelCostValue
    if (liters == null || !liters.isFinite() || liters < 0) return "Укажите корректное количество литров."
    if (cost == null || !cost.isFinite() || cost < 0) return "Укажите корректную сумму заправки."
    if (liters == 0.0 && cost == 0.0) return "Заполните литры и сумму заправки."
    return validateFuelDraft(draft)
}

fun validateFuelDraft(draft: FuelDraft): String? {
    if (draft.recordType == FuelRecordType.Fuel) {
        if (runCatching { LocalDate.parse(draft.date.trim()) }.isFailure) return "Укажите дату в формате ГГГГ-ММ-ДД."
        if (listOf(draft.mileage, draft.liters, draft.fuelCost).any { it.isNotBlank() && it.number()?.isFinite() != true }) {
            return "Укажите корректные числовые значения."
        }
        if (draft.mileageValue == null && draft.litersValue == null && draft.fuelCostValue == null) {
            return "Укажите пробег, бензин или стоимость."
        }
        if (listOf(draft.mileageValue, draft.litersValue, draft.fuelCostValue).filterNotNull().any { it < 0 }) {
            return "Числовые значения должны быть неотрицательными."
        }
        val hasRefueling = draft.litersValue != null || draft.fuelCostValue != null
        if (draft.date >= "2026-04-01" && hasRefueling && draft.litersValue == null) return "Укажите литры для Excel-отчёта."
        if (draft.date >= "2026-04-01" && hasRefueling && draft.fuelCostValue == null) return "Укажите сумму заправки для Excel-отчёта."
        return null
    }
    if (!MONTH_KEY_REGEX.matches(draft.monthKey) || runCatching { YearMonth.parse(draft.monthKey) }.isFailure) return "Укажите месяц в формате ГГГГ-ММ."
    if (listOf(draft.amount, draft.liters, draft.carryoverDebtRub).any { it.isNotBlank() && it.number()?.isFinite() != true }) {
        return "Укажите корректные числовые значения."
    }
    if (listOf(draft.amountValue, draft.litersValue, draft.carryoverDebtValue).filterNotNull().any { it < 0 }) {
        return "Числовые значения должны быть неотрицательными."
    }
    return when (draft.adjustmentKind) {
        FuelAdjustmentKind.CompensationPayment -> if (draft.amountValue == null) "Для выплаты укажите сумму." else null
        FuelAdjustmentKind.DebtDeduction -> if (draft.amountValue == null && draft.litersValue == null) "Для вычета долга укажите сумму или литры." else null
    }
}

private data class MutableFuelMonth(
    val label: String,
    var mileage: Double = 0.0,
    var liters: Double = 0.0,
    var norm: Double = 0.0,
    var cost: Double = 0.0,
)

private fun previousMonthKey(key: String): String? = runCatching { YearMonth.parse(key).minusMonths(1).toString() }.getOrNull()
private fun String.monthLabel(): String = runCatching {
    val month = YearMonth.parse(this)
    val name = month.month.getDisplayName(TextStyle.FULL_STANDALONE, RUSSIAN_LOCALE)
    "${name.replaceFirstChar { it.titlecase(RUSSIAN_LOCALE) }} ${month.year}"
}.getOrDefault("Без даты")
private fun Double.diffLabel(): String = "${if (this > 0) "+" else if (this < 0) "-" else ""}${formatNumber(kotlin.math.abs(this))} л"
private fun String.number(): Double? = trim().replace(',', '.').takeIf(String::isNotEmpty)?.toDoubleOrNull()
private fun Double?.numberString(): String = this?.let { if (it % 1.0 == 0.0) it.toLong().toString() else it.toString() }.orEmpty()
private fun formatNumber(value: Double): String = NUMBER_FORMATTER.format(value)

private val MONTH_KEY_REGEX = Regex("^\\d{4}-\\d{2}$")
private val RUSSIAN_LOCALE = Locale.forLanguageTag("ru-RU")
private val NUMBER_FORMATTER = java.text.NumberFormat.getNumberInstance(RUSSIAN_LOCALE).apply { maximumFractionDigits = 2 }
