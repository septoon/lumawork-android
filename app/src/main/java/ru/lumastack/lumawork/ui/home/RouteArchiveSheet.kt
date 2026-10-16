package ru.lumastack.lumawork.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import ru.lumastack.lumawork.data.route.RouteFailure
import ru.lumastack.lumawork.data.route.RouteRepository
import ru.lumastack.lumawork.domain.model.AppSession
import ru.lumastack.lumawork.domain.route.RouteDayRecord
import ru.lumastack.lumawork.domain.route.hasWorkData
import ru.lumastack.lumawork.ui.theme.AppButton
import ru.lumastack.lumawork.ui.theme.AppTextField

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RouteArchiveSheet(
    state: RouteUiState, session: AppSession, offline: Boolean, repository: RouteRepository,
    onDismiss: () -> Unit, onOdometerChange: (String) -> Unit,
) {
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
    val month = YearMonth.parse(monthKey)
    LaunchedEffect(monthKey, revision) {
        loading = true
        error = null
        records = emptyList()
        try {
            val local = repository.readMonth(session, monthKey)
            records = local
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
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), sheetMaxWidth = 800.dp) {
        LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Все маршруты", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = { revision++ }, enabled = !loading) { Text("Обновить") }
                    TextButton(onClick = onDismiss) { Text("Готово") }
                }
            }
            item {
                AppButton(onClick = { reportMonth = monthKey; gsmOpen = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Отправить ГСМ отчет")
                }
                Text("Сформировать отчет за выбранный месяц", Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            reportMessage?.let { item { Snackbar { Text(it) } } }
            item {
                Card {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { monthKey = month.minusMonths(1).toString(); selectedDay = null }) { Text("‹") }
                            TextButton(onClick = { monthPickerOpen = true }, modifier = Modifier.weight(1f)) {
                                Text(month.format(DateTimeFormatter.ofPattern("LLLL yyyy", Locale.forLanguageTag("ru"))).replaceFirstChar(Char::titlecase))
                            }
                            TextButton(onClick = { monthKey = month.plusMonths(1).toString(); selectedDay = null }) { Text("›") }
                        }
                        Row(Modifier.fillMaxWidth()) {
                            listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс").forEach {
                                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { Text(it, style = MaterialTheme.typography.labelMedium) }
                            }
                        }
                        val offset = month.atDay(1).dayOfWeek.value - 1
                        val cells = (List(offset) { null } + (1..month.lengthOfMonth()).map { month.atDay(it) })
                        cells.chunked(7).forEach { week ->
                            Row(Modifier.fillMaxWidth()) {
                                (week + List(7 - week.size) { null }).forEach { date ->
                                    val dayRecords = displayed.filter { it.date == date?.toString() }
                                    Surface(modifier = Modifier.weight(1f).heightIn(min = 56.dp), shape = MaterialTheme.shapes.medium,
                                        color = if (selectedDay == date?.toString() && date != null) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                                        onClick = { if (date != null) selectedDay = date.toString() }, enabled = date != null) {
                                        Column(Modifier.padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                            Text(date?.dayOfMonth?.toString().orEmpty(), fontWeight = FontWeight.SemiBold)
                                            val km = dayRecords.mapNotNull { it.reportedDistanceKm ?: it.distanceKm?.toDouble() }.sum()
                                            if (dayRecords.isNotEmpty() && dayRecords.any { it.distanceKm != null || it.reportedDistanceKm != null })
                                                Text("${km.toInt()} км", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
            item {
                val editable = monthKey == state.record.date.take(7)
                Card(Modifier.fillMaxWidth().clickable(enabled = editable) { odometerOpen = true }) {
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
                Card(Modifier.fillMaxWidth()) {
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
        }
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
                    try { reportMessage = repository.sendGsmReport(session, submittedMonth).message }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { reportMessage = (failure as? RouteFailure)?.message ?: "Не удалось отправить ГСМ отчет." }
                    finally { sendingReport = false; gsmOpen = false }
                }
            }) { Text(if (sendingReport) "Отправляем…" else "Отправить ГСМ отчет") } },
            dismissButton = { TextButton(onClick = { gsmOpen = false }, enabled = !sendingReport) { Text("Отмена") } })
        if (selectingMonth) RouteMonthPicker(reportMonth, onDismiss = { selectingMonth = false }, onSelect = {
            reportMonth = it; selectingMonth = false
        })
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
