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
import androidx.compose.foundation.background
import androidx.compose.ui.res.painterResource
import ru.lumastack.lumawork.R
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import ru.lumastack.lumawork.domain.route.RouteWorkType
import ru.lumastack.lumawork.domain.route.RouteStopStatus
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
import ru.lumastack.lumawork.domain.route.mergeRouteArchiveRecords
import ru.lumastack.lumawork.ui.theme.AppButton
import ru.lumastack.lumawork.ui.theme.AppTextField
import ru.lumastack.lumawork.ui.theme.AppSurfaces
import ru.lumastack.lumawork.ui.theme.AppCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RouteArchiveSheet(
    state: RouteUiState, session: AppSession, offline: Boolean, repository: RouteRepository,
    onDismiss: () -> Unit, onOdometerChange: (String) -> Unit, onDeleteDay: suspend (String) -> Unit,
    onDeleteRoute: suspend (String, RouteWorkType) -> Unit,
    embedded: Boolean = false, modifier: Modifier = Modifier, refreshRevision: Int = 0,
    onReportMessage: (String) -> Unit = {},
) {
    var contextDay by remember { mutableStateOf<String?>(null) }
    var confirmDeleteDay by rememberSaveable { mutableStateOf<String?>(null) }
    var contextRoute by remember { mutableStateOf<String?>(null) }
    var confirmDeleteRoute by remember { mutableStateOf<RouteDayRecord?>(null) }
    var deleting by remember { mutableStateOf(false) }
    var monthKey by rememberSaveable { mutableStateOf(state.record.date.take(7)) }
    var selectedDay by rememberSaveable { mutableStateOf<String?>(state.record.date) }
    var records by remember { mutableStateOf(emptyList<RouteDayRecord>()) }
    var remoteRecords by remember { mutableStateOf(emptyList<RouteDayRecord>()) }
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
    LaunchedEffect(monthKey, revision, refreshRevision, state.sentReport) {
        loading = true
        error = null
        records = records.filter { it.date.startsWith(monthKey) }
        remoteRecords = remoteRecords.filter { it.date.startsWith(monthKey) }
        try {
            val local = repository.readMonth(session, monthKey)
            records = (records + local).associateBy { it.workType.storageKey(it.date) }.values.toList()
            if (!offline) {
                val remote = repository.refreshMonth(session, monthKey, state.settings)
                remoteRecords = remote
                records = mergeRouteArchiveRecords(repository.readMonth(session, monthKey).filter { !it.sent }, remote)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error = "Не удалось обновить архив. Показаны сохранённые маршруты." }
        finally { loading = false }
    }
    val displayed = remember(records, remoteRecords, state.record, monthKey) {
        mergeRouteArchiveRecords(
            records + listOf(state.record).filter { it.date.startsWith(monthKey) && (it.hasWorkData() || it.distanceKm != null || it.sent) },
            remoteRecords,
        )
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
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary.copy(alpha = .12f)) {
                            Icon(painterResource(R.drawable.ic_route_speed), null, Modifier.padding(12.dp).size(24.dp), tint = MaterialTheme.colorScheme.primary)
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Одометр месяца", fontWeight = FontWeight.SemiBold)
                            Text(month.format(DateTimeFormatter.ofPattern("LLLL yyyy", Locale.forLanguageTag("ru"))).replaceFirstChar(Char::titlecase),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        val value = if (editable) state.record.periodStartOdometer else displayed.firstNotNullOfOrNull { it.reportedPeriodStartOdometer ?: it.periodStartOdometer }
                        Text(value?.let { "$it км" } ?: "Не задан", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        if (editable) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            val selected = displayed.filter { it.date == selectedDay }.sortedByDescending { it.workType.wireValue }
            if (selected.isEmpty()) item {
                Text(if (displayed.isEmpty()) "Маршрутов нет. За выбранный месяц записей пока нет." else "Выберите день, чтобы увидеть маршрут.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(selected, key = { it.workType.storageKey(it.date) }) { record ->
                val key = record.workType.storageKey(record.date)
                val canDelete = !loading && !deleting && !offline && !state.isSending && !state.isDeletingArchivedDay
                Box {
                    RouteArchiveDayCard(record, distanceFormat, Modifier.combinedClickable(
                        onClick = {}, onLongClickLabel = "Удалить ${record.workType.title}",
                        onLongClick = {
                            if (canDelete) {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                contextRoute = key
                            }
                        }
                    ).semantics {
                        if (canDelete) customActions = listOf(CustomAccessibilityAction("Удалить ${record.workType.title}") {
                            confirmDeleteRoute = record; true
                        })
                    })
                    DropdownMenu(expanded = contextRoute == key, onDismissRequest = { contextRoute = null }) {
                        DropdownMenuItem(text = { Text("Удалить ${record.workType.title}", color = MaterialTheme.colorScheme.error) },
                            enabled = canDelete, onClick = { contextRoute = null; confirmDeleteRoute = record })
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
                        remoteRecords = remoteRecords.filter { it.date != date }
                        if (selectedDay == date) selectedDay = null
                        confirmDeleteDay = null
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { error = failure.message ?: "Не удалось удалить день из архива."; confirmDeleteDay = null }
                    finally { deleting = false }
                }
            }) { Text("Удалить", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(enabled = !deleting, onClick = { confirmDeleteDay = null }) { Text("Отмена") } })
    }
    confirmDeleteRoute?.let { record ->
        AlertDialog(onDismissRequest = { if (!deleting) confirmDeleteRoute = null },
            title = { Text("Удалить ${record.workType.title} из архива?") },
            text = { Text("Будет удалён только маршрут ${record.workType.title} за ${record.date}. Второй тип маршрута за этот день сохранится. Уже отправленные ГСМ-отчёты не изменятся.") },
            confirmButton = { TextButton(enabled = !deleting && !loading && !offline && !state.isSending, onClick = {
                deleting = true
                scope.launch {
                    try {
                        onDeleteRoute(record.date, record.workType)
                        records = records.filterNot { it.date == record.date && it.workType == record.workType }
                        remoteRecords = remoteRecords.filterNot { it.date == record.date && it.workType == record.workType }
                        confirmDeleteRoute = null
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { error = failure.message ?: "Не удалось удалить маршрут из архива."; confirmDeleteRoute = null }
                    finally { deleting = false }
                }
            }) { Text("Удалить", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(enabled = !deleting, onClick = { confirmDeleteRoute = null }) { Text("Отмена") } })
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
private fun RouteArchiveDayCard(record: RouteDayRecord, distanceFormat: NumberFormat, modifier: Modifier = Modifier) {
    val requestNumbers = remember(record) {
        record.requestNumbersSummary?.trim()?.takeIf(String::isNotBlank)?.split(Regex("\\s+"))
            ?: record.stops.map { it.requestNumber.trim() }.filter(String::isNotBlank)
    }
    val stops = remember(record, requestNumbers) {
        val addresses = record.routeSummary.orEmpty().split(" - ").map(String::trim).filter(String::isNotBlank)
        if (addresses.isEmpty()) record.stops.filter { it.address.isNotBlank() } else addresses.mapIndexed { index, address ->
            val matching = record.stops.firstOrNull { it.address.trim() == address }
            (matching ?: ru.lumastack.lumawork.domain.route.RouteStop(id = "summary-$index")).copy(
                address = address, requestNumber = requestNumbers.getOrNull(index) ?: matching?.requestNumber.orEmpty())
        }
    }
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val border = AppSurfaces.border
    AppCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(LocalDate.parse(record.date).format(DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.forLanguageTag("ru"))),
                        style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("Маршрут за день", style = MaterialTheme.typography.bodySmall, color = muted)
                }
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    RouteArchiveBadge(record.workType.title, primary)
                    Text((record.reportedDistanceKm ?: record.distanceKm?.toDouble())?.let { "${distanceFormat.format(it)} км" } ?: "—",
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = primary)
                    if (requestNumbers.isNotEmpty()) RouteArchiveBadge("${requestNumbers.size} заявок", secondary)
                }
            }
            HorizontalDivider(color = border)
            Column {
                stops.forEachIndexed { index, stop ->
                    val isFirst = index == 0
                    val isLast = index == stops.lastIndex
                    val tint = when {
                        stop.status == RouteStopStatus.Declined -> MaterialTheme.colorScheme.error
                        stop.status == RouteStopStatus.Done || isFirst || isLast -> primary
                        else -> muted
                    }
                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.width(30.dp).fillMaxHeight().drawBehind {
                            if (!isLast) drawLine(border, Offset(size.width / 2, 28.dp.toPx()), Offset(size.width / 2, size.height), 1.dp.toPx())
                        }, contentAlignment = Alignment.TopCenter) {
                            Surface(shape = CircleShape, color = tint.copy(alpha = .12f)) {
                                if (isFirst || isLast) {
                                    Icon(painterResource(if (isFirst) R.drawable.ic_route_navigation else R.drawable.ic_route_flag), null,
                                        Modifier.size(28.dp).padding(7.dp), tint = tint)
                                } else {
                                    Box(Modifier.size(28.dp).padding(8.dp).background(tint, CircleShape))
                                }
                            }
                        }
                        Column(Modifier.weight(1f).padding(bottom = if (isLast) 2.dp else 16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(stop.address.trim(), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            if (stop.org.isNotBlank()) Text(stop.org.trim(), style = MaterialTheme.typography.bodySmall, color = muted)
                            if (stop.requestNumber.isNotBlank()) Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(painterResource(R.drawable.ic_route_document), null, Modifier.size(16.dp), tint = secondary)
                                Text(stop.requestNumber.trim(), style = MaterialTheme.typography.labelMedium, color = secondary)
                            }
                        }
                    }
                }
            }
            if (requestNumbers.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(R.drawable.ic_route_document), null, Modifier.size(18.dp), tint = muted)
                    Text("ЗАЯВКИ", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = muted)
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    requestNumbers.chunked(2).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { number ->
                                Surface(Modifier.weight(1f), shape = RoundedCornerShape(10.dp), color = secondary.copy(alpha = .1f)) {
                                    Text(number, Modifier.padding(horizontal = 10.dp, vertical = 8.dp), color = secondary,
                                        style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                                }
                            }
                            if (row.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
            record.fuelLiters?.let { Text("Заправка: $it л · ${record.fuelCostRub ?: "—"} ₽", style = MaterialTheme.typography.bodySmall, color = muted) }
        }
    }
}

@Composable
private fun RouteArchiveBadge(text: String, tint: Color) {
    Surface(shape = RoundedCornerShape(50), color = tint.copy(alpha = .12f)) {
        Text(text, Modifier.padding(horizontal = 10.dp, vertical = 5.dp), color = tint,
            style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
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
