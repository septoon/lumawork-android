package ru.lumastack.lumawork.ui.timereport

import ru.lumastack.lumawork.ui.theme.AppRefreshContainer
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import ru.lumastack.lumawork.domain.timereport.TimeReportDaySummary
import ru.lumastack.lumawork.domain.timereport.TimeReportEntry
import ru.lumastack.lumawork.domain.timereport.TimeReportMonthGroup
import ru.lumastack.lumawork.domain.timereport.TimeReportSnapshot
import ru.lumastack.lumawork.ui.adaptive.AppWidthClass
import ru.lumastack.lumawork.ui.theme.appScreenBackground
import ru.lumastack.lumawork.ui.theme.AppCard

@Composable
internal fun TimeReportScreen(
    state: TimeReportUiState,
    widthClass: AppWidthClass,
    onRefresh: () -> Unit,
    onOpenMonth: (String) -> Unit,
    onBack: () -> Unit,
    onDeleteAll: () -> Unit,
    onDeleteRange: (LocalDate, LocalDate) -> Unit,
    toolbar: @Composable () -> Unit,
    headerActions: @Composable (() -> Unit, () -> Unit) -> Unit,
    onOpenEntry: (TimeReportEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    val entries = state.snapshot?.entries.orEmpty()
    val monthGroups = state.monthGroups
    val visibleGroups = monthGroups.filter { state.filterMonthId == null || it.monthId == state.filterMonthId }
    val selectedMonth = monthGroups.firstOrNull { it.monthId == state.selectedMonthId }
    var deleteAllPresented by remember { mutableStateOf(false) }
    var deleteRangePresented by remember { mutableStateOf(false) }
    var cleanupPeriod by remember { mutableStateOf<ru.lumastack.lumawork.domain.requests.ClosedRequestsPeriod?>(null) }
    val background = appScreenBackground()

    if (selectedMonth == null) headerActions({ deleteRangePresented = true }, { deleteAllPresented = true })

    AppRefreshContainer(modifier.fillMaxSize().background(background), isRefreshing = state.isLoading, onRefresh = onRefresh,
        enabled = state.isAuthorized && !state.isDeleting && !state.isLoading, contentAlignment = Alignment.TopCenter) {
        if (selectedMonth == null) {
            TimeReportOverview(
                state = state,
                widthClass = widthClass,
                monthGroups = visibleGroups,
                toolbar = toolbar,
                onOpenMonth = onOpenMonth,
            )
        } else {
            TimeReportMonthDetails(selectedMonth, widthClass, onOpenEntry)
        }
    }

    ru.lumastack.lumawork.ui.shell.AppHeaderBackHandler(enabled = selectedMonth != null, onBack = onBack)

    if (deleteAllPresented) {
        AlertDialog(
            onDismissRequest = { deleteAllPresented = false },
            title = { Text("Удалить все трудозатраты?") },
            text = {
                Text("Архив на этом устройстве (${entries.size} записей) будет удалён. Данные в SimpleOne не изменятся.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteAllPresented = false
                        onDeleteAll()
                    },
                ) { Text("Удалить", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteAllPresented = false }) { Text("Отмена") } },
        )
    }

    val oldest = monthGroups.lastOrNull()?.days?.lastOrNull()?.date
    val newest = monthGroups.firstOrNull()?.days?.firstOrNull()?.date
    if (deleteRangePresented && oldest != null && newest != null) {
        ru.lumastack.lumawork.ui.requests.ClosedRequestsPeriodDialog(
            available = ru.lumastack.lumawork.domain.requests.ClosedRequestsPeriod(oldest, newest),
            selected = null, onDismiss = { deleteRangePresented = false }, title = "Период трудозатрат",
            onApply = { period -> deleteRangePresented = false; cleanupPeriod = period },
        )
    }
    cleanupPeriod?.let { period ->
        val count = monthGroups.flatMap { it.days }.filter { it.date in period }.sumOf { it.entries.size }
        AlertDialog(onDismissRequest = { cleanupPeriod = null }, title = { Text("Удалить трудозатраты за период?") },
            text = { Text("${period.start.format(INPUT_DATE_FORMATTER)} — ${period.end.format(INPUT_DATE_FORMATTER)}\nСтрок к удалению: $count. Данные в SimpleOne не изменятся.") },
            confirmButton = { TextButton(enabled = count > 0, onClick = {
                cleanupPeriod = null; onDeleteRange(period.start, period.end)
            }) { Text("Удалить", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { cleanupPeriod = null }) { Text("Отмена") } })
    }

}

@Composable
private fun TimeReportOverview(
    state: TimeReportUiState,
    widthClass: AppWidthClass,
    monthGroups: List<TimeReportMonthGroup>,
    onOpenMonth: (String) -> Unit,
    toolbar: @Composable () -> Unit,
) {
    val entryCount = monthGroups.sumOf { it.entryCount }
    val dayCount = monthGroups.sumOf { it.days.size }
    val workMinutes = monthGroups.sumOf { it.workMinutes }
    val travelMinutes = monthGroups.sumOf { it.travelMinutes }
    val columns = when (widthClass) {
        AppWidthClass.Compact -> 1
        AppWidthClass.Medium -> 2
        AppWidthClass.Expanded -> 3
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        contentPadding = PaddingValues(20.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.widthIn(max = 1100.dp).fillMaxSize(),
    ) {
        item(key = "toolbar", span = { GridItemSpan(maxLineSpan) }) { toolbar() }
        item(key = "summary", span = { GridItemSpan(maxLineSpan) }) {
            TimeReportSummary(
                state = state,
                dayCount = dayCount,
                workMinutes = workMinutes,
                travelMinutes = travelMinutes,
                entryCount = entryCount,
            )
        }
        when {
            entryCount == 0 -> item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                MessageCard(
                    if (state.isLoading) "Загружаю трудозатраты…" else "Трудозатраты не загружены",
                    state.errorMessage ?: if (state.isAuthorized) {
                        "Трудозатрат пока нет."
                    } else {
                        "Войдите в SimpleOne и обновите экран."
                    },
                )
            }
            else -> items(monthGroups, key = TimeReportMonthGroup::monthId) { month ->
                MonthCard(month, onClick = { onOpenMonth(month.monthId) })
            }
        }
    }
}

@Composable
private fun TimeReportSummary(
    state: TimeReportUiState,
    dayCount: Int,
    workMinutes: Int,
    travelMinutes: Int,
    entryCount: Int,
) {
    AppCard(
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("Сводка", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
                    Text(
                        state.snapshot?.summaryCaption().orEmpty().ifBlank { "Трудозатраты не загружены" },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (state.isLoading) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            }
            Text(if (state.filterMonthId == null) "За выбранный период" else "За выбранный месяц", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(formatMinutes(workMinutes + travelMinutes), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            val total = workMinutes + travelMinutes
            val fraction = if (total > 0) workMinutes.toFloat() / total else 0f
            Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp))) {
                if (workMinutes > 0) Box(Modifier.weight(workMinutes.toFloat()).background(MaterialTheme.colorScheme.primary).padding(vertical = 6.dp))
                if (travelMinutes > 0) Box(Modifier.weight(travelMinutes.toFloat()).background(MaterialTheme.colorScheme.secondary).padding(vertical = 6.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SummaryMetric("Работа · ${kotlin.math.round(fraction * 100).toInt()}%", formatMinutes(workMinutes), Modifier.weight(1f))
                SummaryMetric("Дорога · ${if (total > 0) 100 - kotlin.math.round(fraction * 100).toInt() else 0}%", formatMinutes(travelMinutes), Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SummaryMetric("Дней", dayCount.toString(), Modifier.weight(1f))
                SummaryMetric("Записей", entryCount.toString(), Modifier.weight(1f))
                SummaryMetric("В среднем", formatMinutes(if (dayCount > 0) kotlin.math.round(total.toDouble() / dayCount).toInt() else 0), Modifier.weight(1f))
            }
            Text(if (state.snapshot?.source == ru.lumastack.lumawork.domain.timereport.TimeReportSource.Xlsx) "Источник: отчёт XLSX" else "Источник: SimpleOne",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            state.progress?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun SummaryMetric(title: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f), RoundedCornerShape(14.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(value, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
        Text(title, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun MonthCard(month: TimeReportMonthGroup, onClick: () -> Unit) {
    AppCard(
        onClick = onClick,
    ) {
        Column(Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(monthTitle(month.monthId), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            Text(
                "${month.days.size} дней · ${month.entryCount} строк",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            Text("Всего ${formatMinutes(month.workMinutes + month.travelMinutes)}",
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Работа ${formatMinutes(month.workMinutes)}", color = MaterialTheme.colorScheme.primary)
                Text("Дорога ${formatMinutes(month.travelMinutes)}", color = MaterialTheme.colorScheme.secondary)
            }
        }
    }
}

@Composable
private fun TimeReportMonthDetails(
    month: TimeReportMonthGroup,
    widthClass: AppWidthClass,
    onOpenEntry: (TimeReportEntry) -> Unit,
) {
    var expandedDay by androidx.compose.runtime.saveable.rememberSaveable(month.monthId) { mutableStateOf<String?>(null) }
    val columns = if (widthClass == AppWidthClass.Compact) 1 else 2
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        contentPadding = PaddingValues(20.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.widthIn(max = 1100.dp).fillMaxSize(),
    ) {
        item(key = "month-header", span = { GridItemSpan(maxLineSpan) }) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(monthTitle(month.monthId), style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "${month.days.size} дней · ${month.entryCount} строк · ${formatMinutes(month.workMinutes + month.travelMinutes)}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        month.days.forEach { day ->
            val dayId = day.date.toString()
            item(key = "day-$dayId", span = { GridItemSpan(maxLineSpan) }) {
                DayCard(day, expandedDay == dayId) { expandedDay = dayId.takeUnless { expandedDay == it } }
            }
            if (expandedDay == dayId) items(day.entries, key = { "entry-$dayId-${it.stableId}" }) { entry ->
                TimeReportEntryRow(entry) { onOpenEntry(entry) }
            }
        }
    }
}

@Composable
private fun DayCard(day: TimeReportDaySummary, expanded: Boolean, onToggle: () -> Unit) {
    AppCard(
        onClick = onToggle,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(DAY_FORMATTER.format(day.date), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                Text(
                    "Работа ${formatMinutes(day.workMinutes)} · дорога ${formatMinutes(day.travelMinutes)}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(if (expanded) "Свернуть ↑" else "${day.entries.size} записей · раскрыть ↓", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun TimeReportEntryRow(entry: TimeReportEntry, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .then(Modifier.clickable(onClick = onClick))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.52f), RoundedCornerShape(14.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(entry.effectiveWorkDateTime()?.format(DateTimeFormatter.ofPattern("HH:mm")).orEmpty(), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
        Text(entry.activity, fontWeight = FontWeight.SemiBold)
        Text(
            "Работа ${formatMinutes(entry.workMinutes)} · дорога ${formatMinutes(entry.travelMinutes)}",
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.bodySmall,
        )
        entry.executor.takeIf(String::isNotBlank)?.let {
            Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        entry.notes.takeIf(String::isNotBlank)?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun MessageCard(title: String, message: String) {
    AppCard(
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
            Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun timeReportSnapshotCaption(
    updatedAtEpochMillis: Long?,
    entryCount: Int,
): String {
    val updated = updatedAtEpochMillis?.let {
        UPDATED_FORMATTER.format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()))
    } ?: "дата неизвестна"
    return "$entryCount записей · $updated"
}

private fun TimeReportSnapshot.summaryCaption(): String =
    timeReportSnapshotCaption(updatedAtEpochMillis, entries.size)

private fun formatMinutes(minutes: Int): String {
    val safe = minutes.coerceAtLeast(0)
    val hours = safe / 60
    val rest = safe % 60
    return when {
        hours == 0 -> "$rest мин"
        rest == 0 -> "$hours ч"
        else -> "$hours ч $rest мин"
    }
}

private fun monthTitle(monthId: String): String = runCatching {
    val date = LocalDate.parse("$monthId-01")
    MONTH_FORMATTER.format(date).replaceFirstChar { it.titlecase(Locale.forLanguageTag("ru")) }
}.getOrDefault(monthId)

private val UPDATED_FORMATTER = DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.forLanguageTag("ru"))
private val MONTH_FORMATTER = DateTimeFormatter.ofPattern("LLLL yyyy", Locale.forLanguageTag("ru"))
private val DAY_FORMATTER = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.forLanguageTag("ru"))
private val INPUT_DATE_FORMATTER = DateTimeFormatter.ofPattern("dd.MM.yyyy")
