package ru.lumastack.lumawork.data.salary

/** Prepared once per archive change, independently of the selected year and amount visibility. */
data class SalaryProjection(val months: List<SalaryMonth> = emptyList()) {
    val years: List<String> = months.map { it.key.take(4) }.distinct()
    val total: Double = months.sumOf { it.total }
    val paymentCount: Int = months.sumOf { it.entries.size }
    val average: Double = if (months.isEmpty()) 0.0 else total / months.size
    val averagePaymentCount: Double = if (months.isEmpty()) 0.0 else paymentCount.toDouble() / months.size
    val latest: SalaryMonth? = months.firstOrNull()
    val slices: List<SalaryPaymentSlice> = SalaryKind.entries.map { kind ->
        SalaryPaymentSlice(kind, months.sumOf { month -> month.entries.filter { it.resolvedKind == kind }.sumOf { it.payout } })
    }
    val paymentDates: List<String> = months.flatMap { it.entries }.map { it.date }.sorted()
    fun filteredTotal(excluded: Set<SalaryKind>): Double = slices.filterNot { it.kind in excluded }.sumOf { it.amount.coerceAtLeast(0.0) }
}

data class SalaryPaymentSlice(val kind: SalaryKind, val amount: Double)
