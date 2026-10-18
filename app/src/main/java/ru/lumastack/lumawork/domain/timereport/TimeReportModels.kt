package ru.lumastack.lumawork.domain.timereport

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.DateTimeParseException
import kotlinx.serialization.Serializable

@Serializable
data class TimeReportEntry(
    val activity: String,
    val period: String = "",
    val createdAt: String,
    val createdAtRaw: String = createdAt,
    val workMinutes: Int = 0,
    val travelMinutes: Int = 0,
    val overtimeMinutes: Int = 0,
    val notes: String = "",
    val nonWorkCosts: String = "",
    val isOvertime: Boolean = false,
    val executor: String = "",
    val simpleOneRecordId: String = "",
    val workDate: String = "",
) {
    val stableId: String
        get() = listOf(activity, createdAtDateTime()?.toString().orEmpty(), executor).joinToString("|")

    fun createdAtDateTime(): LocalDateTime? = parseTimeReportDate(createdAt)
    fun effectiveWorkDateTime(): LocalDateTime? = parseTimeReportDate(workDate) ?: createdAtDateTime()
}

@Serializable
data class TimeReportSnapshot(
    val simpleOneUserId: String = "",
    val entries: List<TimeReportEntry> = emptyList(),
    val updatedAtEpochMillis: Long? = null,
    val source: TimeReportSource = TimeReportSource.ListApi,
    val sessionFingerprint: String = "",
)

@Serializable
enum class TimeReportSource {
    Xlsx,
    ListApi,
}

data class TimeReportDaySummary(
    val date: LocalDate,
    val entries: List<TimeReportEntry>,
    val workMinutes: Int,
    val travelMinutes: Int,
    val overtimeMinutes: Int,
) {
    val totalMinutes: Int
        get() = workMinutes + travelMinutes
}

data class TimeReportMonthGroup(
    val monthId: String,
    val days: List<TimeReportDaySummary>,
) {
    val workMinutes: Int
        get() = days.sumOf(TimeReportDaySummary::workMinutes)

    val travelMinutes: Int
        get() = days.sumOf(TimeReportDaySummary::travelMinutes)

    val entryCount: Int
        get() = days.sumOf { it.entries.size }
}

fun timeReportDaySummaries(entries: List<TimeReportEntry>): List<TimeReportDaySummary> = entries
    .mapNotNull { entry -> entry.effectiveWorkDateTime()?.toLocalDate()?.let { it to entry } }
    .groupBy(Pair<LocalDate, TimeReportEntry>::first, Pair<LocalDate, TimeReportEntry>::second)
    .map { (date, dayEntries) ->
        val sorted = dayEntries.sortedWith(
            compareByDescending<TimeReportEntry> { it.effectiveWorkDateTime() }
                .thenBy(TimeReportEntry::activity),
        )
        TimeReportDaySummary(
            date = date,
            entries = sorted,
            workMinutes = sorted.sumOf(TimeReportEntry::workMinutes),
            travelMinutes = sorted.sumOf(TimeReportEntry::travelMinutes),
            overtimeMinutes = sorted.sumOf(TimeReportEntry::overtimeMinutes),
        )
    }
    .sortedByDescending(TimeReportDaySummary::date)

fun timeReportMonthGroups(entries: List<TimeReportEntry>): List<TimeReportMonthGroup> =
    timeReportDaySummaries(entries)
        .groupBy { it.date.format(MONTH_ID_FORMATTER) }
        .map { (monthId, days) -> TimeReportMonthGroup(monthId, days.sortedByDescending { it.date }) }
        .sortedByDescending(TimeReportMonthGroup::monthId)

fun parseTimeReportDate(raw: String): LocalDateTime? {
    val value = raw.trim()
    if (value.isEmpty()) return null
    val yearFirst = value.length > 4 && value[4] == '-'
    return try {
        if (' ' in value) {
            LocalDateTime.parse(value, if (yearFirst) YEAR_FIRST_DATE_TIME else DAY_FIRST_DATE_TIME)
        } else {
            LocalDate.parse(value, if (yearFirst) YEAR_FIRST_DATE else DAY_FIRST_DATE).atStartOfDay()
        }
    } catch (_: DateTimeParseException) {
        null
    }
}

private val YEAR_FIRST_DATE_TIME = optionalSecondsFormatter("yyyy-MM-dd H:mm")
private val DAY_FIRST_DATE_TIME = optionalSecondsFormatter("dd.MM.yyyy H:mm")
private val YEAR_FIRST_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd")
private val DAY_FIRST_DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy")

private fun optionalSecondsFormatter(pattern: String): DateTimeFormatter = DateTimeFormatterBuilder()
    .appendPattern(pattern)
    .optionalStart()
    .appendPattern(":ss")
    .optionalEnd()
    .toFormatter()

private val MONTH_ID_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM")
