package ru.lumastack.lumawork.domain.requests

import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The iOS Analytics screen uses completed visits from the full local closed archive. */
data class ClosedRequestsAnalytics(
    val completedCount: Int = 0,
    val returnEquipCount: Int = 0,
    val months: List<AnalyticsMonth> = emptyList(),
    val requestTypes: List<AnalyticsRequestType> = emptyList(),
    val topMonth: AnalyticsMonth? = null,
    val topDay: AnalyticsDay? = null,
) {
    val latestMonth: AnalyticsMonth? get() = months.firstOrNull()
    val topRequestType: AnalyticsRequestType? get() = requestTypes.firstOrNull()
    val maxMonthCount: Int get() = topMonth?.count ?: 0
}

data class AnalyticsMonth(
    val id: String,
    val title: String,
    val shortTitle: String,
    val count: Int,
    val calendar: List<AnalyticsCalendarDay>,
)
data class AnalyticsRequestType(val title: String, val count: Int)
data class AnalyticsDay(val date: LocalDate, val title: String, val count: Int)
data class AnalyticsCalendarDay(val date: LocalDate?, val count: Int = 0) {
    val dayNumber: Int? get() = date?.dayOfMonth
}

fun buildClosedRequestsAnalytics(items: List<ClosedRequestsItem>): ClosedRequestsAnalytics {
    var completed = 0
    var returns = 0
    val dayCounts = mutableMapOf<LocalDate, Int>()
    val typeCounts = mutableMapOf<String, Int>()
    for (item in items) {
        if (isClosedReturnEquipment(item.request.closedRequestType())) {
            returns++
            continue
        }
        if (item.statusText != "Выполнена") continue
        completed++
        val type = item.typeText.ifBlank { "Не указан" }
        typeCounts[type] = (typeCounts[type] ?: 0) + 1
        item.day?.let { dayCounts[it] = (dayCounts[it] ?: 0) + 1 }
    }
    val months = dayCounts.entries.groupBy { YearMonth.from(it.key) }.map { (month, days) ->
        AnalyticsMonth(month.toString(), month.format(MONTH_TITLE), month.format(SHORT_MONTH_TITLE)
            .replaceFirstChar { it.titlecase(ANALYTICS_LOCALE) }, days.sumOf { it.value }, calendar(month, days.associate { it.key to it.value }))
    }.sortedByDescending { it.id }
    val topMonth = months.maxWithOrNull(compareBy<AnalyticsMonth> { it.count }.thenBy { it.id })
    val topDay = dayCounts.entries.maxWithOrNull(compareBy<Map.Entry<LocalDate, Int>> { it.value }.thenBy { it.key })
        ?.let { AnalyticsDay(it.key, it.key.format(DAY_TITLE), it.value) }
    val types = typeCounts.map { AnalyticsRequestType(it.key, it.value) }
        .sortedWith(compareByDescending<AnalyticsRequestType> { it.count }.thenBy { it.title.lowercase(ANALYTICS_LOCALE) })
    return ClosedRequestsAnalytics(completed, returns, months, types, topMonth, topDay)
}

private fun calendar(month: YearMonth, counts: Map<LocalDate, Int>): List<AnalyticsCalendarDay> {
    val leading = month.atDay(1).dayOfWeek.value - 1 // Monday first, including complete empty weeks' cells.
    val size = ((leading + month.lengthOfMonth() + 6) / 7) * 7
    return List(size) { index ->
        val number = index - leading + 1
        val date = number.takeIf { it in 1..month.lengthOfMonth() }?.let(month::atDay)
        AnalyticsCalendarDay(date, counts[date] ?: 0)
    }
}

private val ANALYTICS_LOCALE = Locale.forLanguageTag("ru-RU")
private val MONTH_TITLE = DateTimeFormatter.ofPattern("LLLL yyyy", ANALYTICS_LOCALE)
private val SHORT_MONTH_TITLE = DateTimeFormatter.ofPattern("LLL", ANALYTICS_LOCALE)
private val DAY_TITLE = DateTimeFormatter.ofPattern("d MMMM yyyy", ANALYTICS_LOCALE)
