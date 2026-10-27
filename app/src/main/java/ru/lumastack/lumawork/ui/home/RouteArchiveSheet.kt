package ru.lumastack.lumawork.ui.home

import ru.lumastack.lumawork.ui.theme.AppModalSheet
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.text.NumberFormat
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ru.lumastack.lumawork.data.route.RouteFailure
import ru.lumastack.lumawork.data.route.RouteRepository
import ru.lumastack.lumawork.domain.model.AppSession
import ru.lumastack.lumawork.domain.route.RouteDayRecord
import ru.lumastack.lumawork.domain.route.hasWorkData
import ru.lumastack.lumawork.ui.theme.AppButton
import ru.lumastack.lumawork.ui.theme.AppTextField
import ru.lumastack.lumawork.ui.theme.AppSurfaces
import ru.lumastack.lumawork.ui.theme.AppCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RouteArchiveSheet(
    state: RouteUiState, session: AppSession, offline: Boolean, repository: RouteRepository,
    onDismiss: () -> Unit, onOdometerChange: (String) -> Unit, onDeleteDay: suspend (String) -> Unit,
    embedded: Boolean = false, modifier: Modifier = Modifier, refreshRevision: Int = 0,
    onReportMessage: (String) -> Unit = {},
) {
    var contextDay by remember { mutableStateOf<String?>(null) }
    var confirmDeleteDay by rememberSaveable { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf(false) }
    var monthKey by rememberSaveable { mutableStateOf(state.record.date.take(7)) }
    var selectedDay by rememberSaveable { mutableStateOf<String?>(state.record.date) }
    var records by remember { mutableStateOf(emptyList<RouteDayRecord>()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var revision by remember { mutableIntStateOf(0) }
    var odometerOpen by rememberSaveable { mutableStateOf(false) }
    var monthPickerOpen by rememberSaveable { mutableStateOf(false) }
    var gsmOpen by rememberSaveable { mutableStateOf(false) }
    var reportMonth by rememberSaveable { mutableStateOf(monthKey) }
    var sendingReport by remember { mutableStateOf(false) }
    var reportMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val distanceFormat = remember { NumberFormat.getNumberInstance(Locale.forLanguageTag("ru")).apply { maximumFractionDigits = 1; isGroupingUsed = false } }
    val month = YearMonth.parse(monthKey)
    LaunchedEffect(monthKey, revision, refreshRevision) {
        loading = true
        error = null
        records = records.filter { it.date.startsWith(monthKey) }
        try {
            val local = repository.readMonth(session, monthKey)
            records = (records + local).associateBy { it.workType.storageKey(it.date) }.values.toList()
            if (!offline) {
                val remote = repository.refreshMonth(session, monthKey, state.settings)
                records = (remote + repository.readMonth(session, monthKey).filter { !it.sent })
                    .associateBy { it.workType.storageKey(it.date) }.values.toList()
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error = "Не удалось обновить архив. Показаны сохранённые маршруты." }
        finally { loading = false }
    }
    val displayed = remember(records, state.record, monthKey) {
        (records + listOf(state.record).filter { it.date.startsWith(monthKey) && (it.hasWorkData() || it.distanceKm != null || it.sent) })
            .associateBy { it.workType.storageKey(it.date) }.values.toList()
    }
    BackHandler(enabled = deleting) { }
    val archiveContent: @Composable () -> Unit = {
        LazyColumn(modifier.fillMaxWidth(), contentPadding = PaddingValues(if (embedded) 16.dp else 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (embedded) "Архив маршрутов" else "Все маршруты", style = if (embedded) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = { revision++ }, enabled = !loading && !deleting) { Text("Обновить") }
                    if (!embedded) TextButton(enabled = !deleting, onClick = onDismiss) { Text("Готово") }
                }
            }
            if (!embedded) item {
                AppButton(onClick = { reportMonth = monthKey; gsmOpen = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Отправить ГСМ отчет")
                }
                Text("Сформировать отчет за выбранный месяц", Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item {
                AppCard {
                    Column(Modifier.padding(if (embedded) 0.dp else 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { monthKey = month.minusMonths(1).toString(); selectedDay = null }) {
                                Surface(shape = CircleShape, color = AppSurfaces.card.copy(alpha = .72f)) {
                                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Предыдущий месяц", Modifier.size(36.dp).padding(8.dp), tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                                TextButton(onClick = { monthPickerOpen = true }, contentPadding = PaddingValues(horizontal = 2.dp)) {
                                    Text(month.format(DateTimeFormatter.ofPattern("LLLL yyyy", Locale.forLanguageTag("ru"))).replaceFirstChar(Char::titlecase),
                                        style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                                    Icon(Icons.Default.KeyboardArrowDown, null, Modifier.padding(start = 5.dp).size(14.dp), tint = MaterialTheme.colorScheme.onSurface)
                                }
                                val totalKm = displayed.sumOf { it.reportedDistanceKm ?: it.distanceKm?.toDouble() ?: 0.0 }
                                Text("Общий пробег за месяц: ${distanceFormat.format(totalKm)} км", style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                            }
                            IconButton(onClick = { monthKey = month.plusMonths(1).toString(); selectedDay = null }) {
                                Surface(shape = CircleShape, color = AppSurfaces.card.copy(alpha = .72f)) {
                                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Следующий месяц", Modifier.size(36.dp).padding(8.dp), tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("ПН", "ВТ", "СР", "ЧТ", "ПТ", "СБ", "ВС").forEach {
                                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                    Text(it, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        val offset = month.atDay(1).dayOfWeek.value - 1
                        val cells = (List(offset) { null } + (1..month.lengthOfMonth()).map { month.atDay(it) })
                        val recordsByDate = displayed.groupBy { it.date }
                        cells.chunked(7).forEach { week ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                (week + List(7 - week.size) { null }).forEach { date ->
                                    if (date == null) {
                                        Spacer(Modifier.weight(1f).height(54.dp))
                                    } else {
                                        val key = date.toString()
                                        val dayRecords = recordsByDate[key].orEmpty()
                                        val hasRecords = dayRecords.isNotEmpty()
                                        val selected = hasRecords && selectedDay == key
                                        val km = dayRecords.sumOf { it.reportedDistanceKm ?: it.distanceKm?.toDouble() ?: 0.0 }
                                        val distance = if (hasRecords) "${distanceFormat.format(km)} км" else "—"
                                        val shape = RoundedCornerShape(12.dp)
                                        val foreground = when {
                                            selected -> Color.White
                                            hasRecords -> MaterialTheme.colorScheme.onSurface
                                            else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .65f)
                                        }
                                        Box(Modifier.weight(1f)) {
                                        Surface(modifier = Modifier.fillMaxWidth().clip(shape)
                                            .combinedClickable(enabled = hasRecords && !deleting, role = Role.Button,
                                                onClick = {
                                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                    selectedDay = if (selected) null else key
                                                }, onLongClickLabel = "Удалить день из архива", onLongClick = {
                                                    if (!loading && !state.isSending && !state.isDeletingArchivedDay) {
                                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                                        contextDay = key
                                                    }
                                                }).semantics(mergeDescendants = true) {
                                                contentDescription = "${date.format(DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.forLanguageTag("ru")))}, " +
                                                    if (hasRecords) "${dayRecords.joinToString(" и ") { it.workType.title }}, $distance" else "маршрута нет"
                                            }, shape = shape,
                                            color = when {
                                                selected -> MaterialTheme.colorScheme.primary
                                                hasRecords -> MaterialTheme.colorScheme.primary.copy(alpha = .11f)
                                                else -> Color.Transparent
                                            }, contentColor = foreground,
                                            border = BorderStroke(1.dp, when {
                                                date == LocalDate.now() -> MaterialTheme.colorScheme.primary.copy(alpha = .55f)
                                                selected -> MaterialTheme.colorScheme.primary
                                                else -> MaterialTheme.colorScheme.primary.copy(alpha = .18f)
                                            })) {
                                            Column(Modifier.heightIn(min = 54.dp).padding(horizontal = 1.dp, vertical = 6.dp),
                                                verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically), horizontalAlignment = Alignment.CenterHorizontally) {
                                                Text(date.dayOfMonth.toString(), style = MaterialTheme.typography.bodyMedium,
                                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
                                                val distanceStyle = MaterialTheme.typography.labelSmall
                                                BasicText(distance, style = distanceStyle.copy(color = foreground,
                                                    fontWeight = if (hasRecords) FontWeight.SemiBold else FontWeight.Normal),
                                                    maxLines = 1, autoSize = TextAutoSize.StepBased(
                                                        minFontSize = distanceStyle.fontSize * .72f, maxFontSize = distanceStyle.fontSize))
                                            }
                                        }
                                        DropdownMenu(expanded = contextDay == key, onDismissRequest = { contextDay = null }) {
                                            DropdownMenuItem(text = { Text("Удалить день из архива", color = MaterialTheme.colorScheme.error) },
                                                enabled = !loading && !deleting && !offline && !state.isSending,
                                                onClick = { contextDay = null; confirmDeleteDay = key })
                                        }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (loading || deleting) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
            item {
                val editable = monthKey == state.record.date.take(7)
                AppCard(Modifier.fillMaxWidth().clickable(enabled = editable) { odometerOpen = true }) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Одометр на начало месяца", fontWeight = FontWeight.SemiBold)
                        val value = if (editable) state.record.periodStartOdometer else displayed.firstNotNullOfOrNull { it.reportedPeriodStartOdometer ?: it.periodStartOdometer }
                        Text(value?.let { "$it км" } ?: "Не задан", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            val selected = displayed.filter { it.date == selectedDay }.sortedByDescending { it.workType.wireValue }
            if (selected.isEmpty()) item {
                Text(if (displayed.isEmpty()) "Маршрутов нет. За выбранный месяц записей пока нет." else "Выберите день, чтобы увидеть маршрут.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(selected, key = { it.workType.storageKey(it.date) }) { record ->
                AppCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("${record.workType.title} · ${LocalDate.parse(record.date).format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))}", style = MaterialTheme.typography.titleMedium)
                        Text(if (record.sent) "Отправлен" else "Черновик", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Пробег дня: ${record.reportedDistanceKm ?: record.distanceKm ?: "—"} км")
                        Text(record.routeSummary?.takeIf(String::isNotBlank) ?: record.stops.map { it.address }.filter(String::isNotBlank).joinToString(" → "))
                        val numbers = record.requestNumbersSummary?.takeIf(String::isNotBlank)
                            ?: record.stops.map { it.requestNumber }.filter(String::isNotBlank).joinToString(", ")
                        if (numbers.isNotBlank()) Text("Заявки: $numbers")
                        record.fuelLiters?.let { Text("Заправка: $it л · ${record.fuelCostRub ?: "—"} ₽") }
                    }
                }
            }
            if (embedded) item {
                AppButton(onClick = { reportMonth = monthKey; gsmOpen = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Отправить ГСМ отчет")
                }
                Text("Сформировать отчет за выбранный месяц", Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (embedded) archiveContent() else {
        AppModalSheet(onDismiss = onDismiss, dismissEnabled = !deleting, maxWidth = 800.dp) {
            Box(Modifier.fillMaxWidth()) {
                archiveContent()
                RouteReportMessage(reportMessage, onDismiss = { reportMessage = null },
                    modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
            }
        }
    }
    confirmDeleteDay?.let { date ->
        AlertDialog(onDismissRequest = { if (!deleting) confirmDeleteDay = null },
            title = { Text("Удалить день из архива?") },
            text = { Text("День $date будет удалён из архива и исключён из последующих ГСМ-отчётов. Его можно будет добавить заново. Уже отправленные отчёты не изменятся.") },
            confirmButton = { TextButton(enabled = !deleting && !loading && !offline && !state.isSending, onClick = {
                deleting = true
                scope.launch {
                    try {
                        onDeleteDay(date)
                        records = records.filter { it.date != date }
                        if (selectedDay == date) selectedDay = null
                        confirmDeleteDay = null
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { error = failure.message ?: "Не удалось удалить день из архива."; confirmDeleteDay = null }
                    finally { deleting = false }
                }
            }) { Text("Удалить", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(enabled = !deleting, onClick = { confirmDeleteDay = null }) { Text("Отмена") } })
    }
    if (odometerOpen) {
        var draft by rememberSaveable { mutableStateOf(state.odometerText) }
        AlertDialog(onDismissRequest = { odometerOpen = false }, title = { Text("Одометр на начало месяца") },
            text = { AppTextField(draft, { draft = it.filter(Char::isDigit).take(8) }, "Пробег, км", keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)) },
            confirmButton = { TextButton(onClick = { onOdometerChange(draft); odometerOpen = false }) { Text("Сохранить") } },
            dismissButton = { TextButton(onClick = { odometerOpen = false }) { Text("Отмена") } })
    }
    if (monthPickerOpen) RouteMonthPicker(monthKey, onDismiss = { monthPickerOpen = false }, onSelect = {
        monthKey = it; selectedDay = null; monthPickerOpen = false
    })
    if (gsmOpen) {
        var selectingMonth by rememberSaveable { mutableStateOf(false) }
        AlertDialog(onDismissRequest = { if (!sendingReport) gsmOpen = false }, title = { Text("ГСМ отчет") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Выберите месяц и отправьте отчет на почту.")
                    TextButton(onClick = { selectingMonth = true }, enabled = !sendingReport) { Text(reportMonth) }
                    if (offline) Text("Для отправки требуется подключение к интернету.")
                    if (sendingReport) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            },
            confirmButton = { TextButton(enabled = !sendingReport && !offline, onClick = {
                sendingReport = true
                val submittedMonth = reportMonth
                scope.launch {
                    val message = try { repository.sendGsmReport(session, submittedMonth).message }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { (failure as? RouteFailure)?.message ?: "Не удалось отправить ГСМ отчет." }
                    finally { sendingReport = false; gsmOpen = false }
                    if (embedded) onReportMessage(message) else reportMessage = message
                }
            }) { Text(if (sendingReport) "Отправляем…" else "Отправить ГСМ отчет") } },
            dismissButton = { TextButton(onClick = { gsmOpen = false }, enabled = !sendingReport) { Text("Отмена") } })
        if (selectingMonth) RouteMonthPicker(reportMonth, onDismiss = { selectingMonth = false }, onSelect = {
            reportMonth = it; selectingMonth = false
        })
    }
}

@Composable
internal fun RouteReportMessage(message: String?, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    var displayedMessage by remember { mutableStateOf(message.orEmpty()) }
    val dismiss by rememberUpdatedState(onDismiss)
    LaunchedEffect(message) {
        if (message != null) {
            displayedMessage = message
            delay(2000)
            dismiss()
        }
    }
    AnimatedVisibility(visible = message != null,
        modifier = modifier.widthIn(max = 560.dp).fillMaxWidth().padding(16.dp),
        enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
        Snackbar { Text(displayedMessage) }
    }
}

@Composable
private fun RouteMonthPicker(initialMonth: String, onDismiss: () -> Unit, onSelect: (String) -> Unit) {
    var year by rememberSaveable { mutableIntStateOf(YearMonth.parse(initialMonth).year) }
    var month by rememberSaveable { mutableIntStateOf(YearMonth.parse(initialMonth).monthValue) }
    var yearOpen by remember { mutableStateOf(false) }
    var monthOpen by remember { mutableStateOf(false) }
    val locale = Locale.forLanguageTag("ru")
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Выбрать месяц") }, text = {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Box {
                TextButton(onClick = { monthOpen = true }) { Text(java.time.Month.of(month).getDisplayName(java.time.format.TextStyle.FULL_STANDALONE, locale).replaceFirstChar(Char::titlecase)) }
                DropdownMenu(monthOpen, onDismissRequest = { monthOpen = false }) {
                    (1..12).forEach { value -> DropdownMenuItem(text = { Text(java.time.Month.of(value).getDisplayName(java.time.format.TextStyle.FULL_STANDALONE, locale).replaceFirstChar(Char::titlecase)) },
                        onClick = { month = value; monthOpen = false }) }
                }
            }
            Box {
                TextButton(onClick = { yearOpen = true }) { Text(year.toString()) }
                DropdownMenu(yearOpen, onDismissRequest = { yearOpen = false }) {
                    val now = LocalDate.now().year
                    (minOf(year, maxOf(2020, now - 5))..maxOf(year, now + 1)).forEach { value -> DropdownMenuItem(text = { Text(value.toString()) }, onClick = { year = value; yearOpen = false }) }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = { onSelect(YearMonth.of(year, month).toString()) }) { Text("Готово") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } })
}
