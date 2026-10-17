package ru.lumastack.lumawork.ui.timereport

import ru.lumastack.lumawork.ui.theme.AppRefreshContainer

import androidx.compose.foundation.background
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import ru.lumastack.lumawork.ui.theme.AppButton as Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import ru.lumastack.lumawork.ui.theme.AppOutlinedButton as OutlinedButton
import ru.lumastack.lumawork.ui.theme.AppTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale
import ru.lumastack.lumawork.data.timereport.TimeReportRepository
import ru.lumastack.lumawork.domain.timereport.TimeReportDaySummary
import ru.lumastack.lumawork.domain.timereport.TimeReportEntry
import ru.lumastack.lumawork.domain.timereport.TimeReportMonthGroup
import ru.lumastack.lumawork.domain.timereport.TimeReportSnapshot
import ru.lumastack.lumawork.domain.timereport.timeReportMonthGroups
import ru.lumastack.lumawork.ui.adaptive.AppWidthClass
import ru.lumastack.lumawork.ui.simpleone.SimpleOneUiState
import ru.lumastack.lumawork.ui.theme.LumaBackgroundBottomDark
import ru.lumastack.lumawork.ui.theme.LumaBackgroundBottomLight
import ru.lumastack.lumawork.ui.theme.LumaBackgroundTopDark
import ru.lumastack.lumawork.ui.theme.LumaBackgroundTopLight

@Composable
fun TimeReportRoute(
    ownerUserId: String,
    startOffline: Boolean,
    simpleOneState: SimpleOneUiState,
    repository: TimeReportRepository,
    widthClass: AppWidthClass,
    modifier: Modifier = Modifier,
) {
    val timeReportViewModel: TimeReportViewModel = viewModel(
        key = "time-report-$ownerUserId-$startOffline",
        factory = TimeReportViewModel.factory(repository, ownerUserId, startOffline),
    )
    val state by timeReportViewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(simpleOneState.snapshot.currentUser?.sysId, simpleOneState.isAuthorized) {
        timeReportViewModel.activate(
            simpleOneUserId = simpleOneState.snapshot.currentUser?.sysId.orEmpty(),
            isAuthorized = simpleOneState.isAuthorized,
        )
    }
    TimeReportScreen(
        state = state,
        widthClass = widthClass,
        onRefresh = timeReportViewModel::refresh,
        onOpenMonth = timeReportViewModel::openMonth,
        onBack = timeReportViewModel::closeMonth,
        onDeleteAll = timeReportViewModel::deleteAll,
        onDeleteRange = timeReportViewModel::deleteRange,
        onInputError = timeReportViewModel::reportInputError,
        modifier = modifier,
    )
}

@Composable
private fun TimeReportScreen(
    state: TimeReportUiState,
    widthClass: AppWidthClass,
    onRefresh: () -> Unit,
    onOpenMonth: (String) -> Unit,
    onBack: () -> Unit,
    onDeleteAll: () -> Unit,
    onDeleteRange: (LocalDate, LocalDate) -> Unit,
    onInputError: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val entries = state.snapshot?.entries.orEmpty()
    val monthGroups = remember(entries) { timeReportMonthGroups(entries) }
    val selectedMonth = monthGroups.firstOrNull { it.monthId == state.selectedMonthId }
    var deleteAllPresented by remember { mutableStateOf(false) }
    var deleteRangePresented by remember { mutableStateOf(false) }
    val isDark = MaterialTheme.colorScheme.background == LumaBackgroundTopDark
    val background = remember(isDark) {
        Brush.verticalGradient(
            if (isDark) listOf(LumaBackgroundTopDark, LumaBackgroundBottomDark)
            else listOf(LumaBackgroundTopLight, LumaBackgroundBottomLight),
        )
    }

    AppRefreshContainer(modifier.fillMaxSize().background(background), isRefreshing = state.isLoading, onRefresh = onRefresh,
        enabled = state.isAuthorized && !state.isDeleting, contentAlignment = Alignment.TopCenter) {
        if (selectedMonth == null) {
            TimeReportOverview(
                state = state,
                widthClass = widthClass,
                monthGroups = monthGroups,
                onOpenMonth = onOpenMonth,
                onDeleteRange = { deleteRangePresented = true },
                onDeleteAll = { deleteAllPresented = true },
            )
        } else {
            TimeReportMonthDetails(selectedMonth, widthClass, onBack)
        }
    }

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

    if (deleteRangePresented) {
        DeleteRangeDialog(
            entries = entries,
            onDismiss = { deleteRangePresented = false },
            onConfirm = { start, end ->
                deleteRangePresented = false
                onDeleteRange(start, end)
            },
            onInputError = onInputError,
        )
    }
}

@Composable
private fun TimeReportOverview(
    state: TimeReportUiState,
    widthClass: AppWidthClass,
    monthGroups: List<TimeReportMonthGroup>,
    onOpenMonth: (String) -> Unit,
    onDeleteRange: () -> Unit,
    onDeleteAll: () -> Unit,
) {
    val entries = state.snapshot?.entries.orEmpty()
    val dayCount = monthGroups.sumOf { it.days.size }
    val totals = remember(entries) {
        entries.fold(0 to 0) { total, entry ->
            total.first + entry.workMinutes to total.second + entry.travelMinutes
        }
    }
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
        item(key = "summary", span = { GridItemSpan(maxLineSpan) }) {
            TimeReportSummary(
                state = state,
                widthClass = widthClass,
                dayCount = dayCount,
                workMinutes = totals.first,
                travelMinutes = totals.second,
                onDeleteRange = onDeleteRange,
                onDeleteAll = onDeleteAll,
            )
        }
        when {
            state.simpleOneUserId.isBlank() -> item(key = "not-connected", span = { GridItemSpan(maxLineSpan) }) {
                MessageCard("SimpleOne не подключён", "Войдите в SimpleOne на экране «Заявки».")
            }
            entries.isEmpty() -> item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
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
    widthClass: AppWidthClass,
    dayCount: Int,
    workMinutes: Int,
    travelMinutes: Int,
    onDeleteRange: () -> Unit,
    onDeleteAll: () -> Unit,
) {
    val entries = state.snapshot?.entries.orEmpty()
    Card(
        colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)),
        shape = RoundedCornerShape(22.dp),
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
            if (widthClass == AppWidthClass.Compact) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SummaryMetric("Работа", formatMinutes(workMinutes), Modifier.weight(1f))
                    SummaryMetric("Дорога", formatMinutes(travelMinutes), Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SummaryMetric("Всего", formatMinutes(workMinutes + travelMinutes), Modifier.weight(1f))
                    SummaryMetric("Дней", dayCount.toString(), Modifier.weight(1f))
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SummaryMetric("Работа", formatMinutes(workMinutes), Modifier.weight(1f))
                    SummaryMetric("Дорога", formatMinutes(travelMinutes), Modifier.weight(1f))
                    SummaryMetric("Всего", formatMinutes(workMinutes + travelMinutes), Modifier.weight(1f))
                    SummaryMetric("Дней", dayCount.toString(), Modifier.weight(1f))
                }
            }
            if (entries.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = onDeleteRange, enabled = !state.isDeleting) {
                        Text("Удалить за период")
                    }
                    TextButton(onClick = onDeleteAll, enabled = !state.isDeleting) {
                        Text("Удалить всё", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            state.notice?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            state.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
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
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(monthTitle(month.monthId), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            Text(
                "${month.days.size} дней · ${month.entryCount} строк",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
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
    onBack: () -> Unit,
) {
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
                    OutlinedButton(onClick = onBack) { Text("Назад") }
                    Text(monthTitle(month.monthId), style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "${month.days.size} дней · ${month.entryCount} строк · ${formatMinutes(month.workMinutes + month.travelMinutes)}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        items(month.days, key = { it.date.toString() }) { day -> DayCard(day) }
    }
}

@Composable
private fun DayCard(day: TimeReportDaySummary) {
    Card(
        colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)),
        shape = RoundedCornerShape(20.dp),
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
            day.entries.forEach { entry -> TimeReportEntryRow(entry) }
        }
    }
}

@Composable
private fun TimeReportEntryRow(entry: TimeReportEntry) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.52f), RoundedCornerShape(14.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(entry.activity, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
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
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun DeleteRangeDialog(
    entries: List<TimeReportEntry>,
    onDismiss: () -> Unit,
    onConfirm: (LocalDate, LocalDate) -> Unit,
    onInputError: (String) -> Unit,
) {
    val dates = remember(entries) { entries.mapNotNull { it.createdAtDateTime()?.toLocalDate() } }
    var startText by remember(entries) { mutableStateOf(dates.minOrNull()?.format(INPUT_DATE_FORMATTER).orEmpty()) }
    var endText by remember(entries) { mutableStateOf(dates.maxOrNull()?.format(INPUT_DATE_FORMATTER).orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Удалить трудозатраты за период") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Записи за выбранный период будут удалены только с этого устройства.")
                AppTextField(
                    value = startText,
                    onValueChange = { startText = it },
                    label = "Начало, дд.мм.гггг",
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                AppTextField(
                    value = endText,
                    onValueChange = { endText = it },
                    label = "Конец, дд.мм.гггг",
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val start = parseInputDate(startText)
                val end = parseInputDate(endText)
                if (start == null || end == null) onInputError("Укажите даты в формате дд.мм.гггг.")
                else onConfirm(start, end)
            }) { Text("Удалить", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
private fun MessageCard(title: String, message: String) {
    Card(
        colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)),
        shape = RoundedCornerShape(20.dp),
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

private fun parseInputDate(raw: String): LocalDate? = try {
    LocalDate.parse(raw.trim(), INPUT_DATE_FORMATTER)
} catch (_: DateTimeParseException) {
    null
}

private val UPDATED_FORMATTER = DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.forLanguageTag("ru"))
private val MONTH_FORMATTER = DateTimeFormatter.ofPattern("LLLL yyyy", Locale.forLanguageTag("ru"))
private val DAY_FORMATTER = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.forLanguageTag("ru"))
private val INPUT_DATE_FORMATTER = DateTimeFormatter.ofPattern("dd.MM.yyyy")
