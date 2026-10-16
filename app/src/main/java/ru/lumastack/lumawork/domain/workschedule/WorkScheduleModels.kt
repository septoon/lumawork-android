package ru.lumastack.lumawork.domain.workschedule

import java.text.Collator
import java.text.Normalizer
import java.time.LocalDate
import java.util.Locale
import kotlinx.serialization.Serializable

@Serializable
data class WorkScheduleJournal(val sysId: String, val cityId: String, val cityName: String, val year: Int, val month: Int)
@Serializable
data class WorkScheduleDayValue(val day: Int, val hours: Double, val isActive: Boolean)
@Serializable
data class WorkScheduleEmployee(val id: String, val name: String, val login: String, val totalHours: Double, val dayValues: List<WorkScheduleDayValue>) {
    fun value(day: Int): WorkScheduleDayValue? = dayValues.firstOrNull { it.day == day }
}
@Serializable
data class WorkScheduleDailyTotal(val day: Int, val count: Int)
@Serializable
data class WorkScheduleAuditInfo(
    val createdBy: String? = null,
    val createdAtEpochMillis: Long? = null,
    val updatedBy: String? = null,
    val updatedAtEpochMillis: Long? = null,
) {
    val hasContent: Boolean get() = createdBy != null || createdAtEpochMillis != null || updatedBy != null || updatedAtEpochMillis != null
}
@Serializable
data class WorkSchedule(
    val journal: WorkScheduleJournal,
    val daysInMonth: Int,
    val employees: List<WorkScheduleEmployee>,
    val dailyTotals: List<WorkScheduleDailyTotal>,
    val auditInfo: WorkScheduleAuditInfo? = null,
) {
    fun canReuse(forceRefresh: Boolean): Boolean = !forceRefresh && auditInfo?.hasContent == true
}
@Serializable
data class WorkScheduleSnapshot(
    val simpleOneUserId: String = "",
    val journals: List<WorkScheduleJournal> = emptyList(),
    val schedulesByJournalId: Map<String, WorkSchedule> = emptyMap(),
)

data class WorkScheduleSelectionState(val cityName: String = "", val year: Int = 0, val month: Int = 0)

object WorkScheduleSelection {
    private fun normalized(value: String): String = Normalizer.normalize(value.trim(), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT)

    fun cities(journals: List<WorkScheduleJournal>): List<String> = journals.associate { normalized(it.cityName) to it.cityName }
        .values.sortedWith(Collator.getInstance(Locale.forLanguageTag("ru")))

    fun years(journals: List<WorkScheduleJournal>, city: String): List<Int> = journals
        .filter { normalized(it.cityName) == normalized(city) }.map { it.year }.distinct().sortedDescending()

    fun months(journals: List<WorkScheduleJournal>, city: String, year: Int): List<Int> = journals
        .filter { normalized(it.cityName) == normalized(city) && it.year == year }.map { it.month }.distinct().sorted()

    fun initial(journals: List<WorkScheduleJournal>, profileCity: String?, now: LocalDate): WorkScheduleSelectionState {
        val cities = cities(journals)
        return WorkScheduleSelectionState(cities.firstOrNull { normalized(it) == normalized(profileCity.orEmpty()) }
            ?: cities.firstOrNull().orEmpty(), now.year, now.monthValue)
    }

    fun journal(journals: List<WorkScheduleJournal>, selection: WorkScheduleSelectionState): WorkScheduleJournal? = journals.firstOrNull {
        normalized(it.cityName) == normalized(selection.cityName) && it.year == selection.year && it.month == selection.month
    }

    fun selectCity(journals: List<WorkScheduleJournal>, selection: WorkScheduleSelectionState, city: String): WorkScheduleSelectionState {
        val year = selection.year.takeIf { it in years(journals, city) } ?: years(journals, city).firstOrNull() ?: selection.year
        return selectYear(journals, selection.copy(cityName = city), year)
    }

    fun selectYear(journals: List<WorkScheduleJournal>, selection: WorkScheduleSelectionState, year: Int): WorkScheduleSelectionState {
        val months = months(journals, selection.cityName, year)
        return selection.copy(year = year, month = selection.month.takeIf { it in months } ?: months.lastOrNull() ?: selection.month)
    }
}

object WorkScheduleViewport {
    fun currentDay(journal: WorkScheduleJournal, now: LocalDate): Int? = now.dayOfMonth.takeIf { now.year == journal.year && now.monthValue == journal.month }
    fun initialVisibleDay(journal: WorkScheduleJournal, now: LocalDate): Int = (currentDay(journal, now)?.minus(1) ?: 1).coerceAtLeast(1)
}
