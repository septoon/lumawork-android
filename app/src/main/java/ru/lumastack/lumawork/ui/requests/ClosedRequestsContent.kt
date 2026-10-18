package ru.lumastack.lumawork.ui.requests

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import ru.lumastack.lumawork.domain.requests.ClosedRequestsItem
import ru.lumastack.lumawork.domain.requests.ClosedRequestsPeriod
import ru.lumastack.lumawork.domain.requests.closedAddress
import ru.lumastack.lumawork.domain.requests.closedCustomer
import ru.lumastack.lumawork.domain.requests.closedRequestType
import ru.lumastack.lumawork.domain.requests.closedRequestsDayTitle
import ru.lumastack.lumawork.domain.requests.closedTerminalId
import ru.lumastack.lumawork.domain.requests.defaultClosedRequestsPeriod
import ru.lumastack.lumawork.domain.requests.isClosedReturnEquipment
import ru.lumastack.lumawork.domain.requests.normalizedClosedAddress
import ru.lumastack.lumawork.domain.requests.prepareClosedRequests
import ru.lumastack.lumawork.domain.requests.queryClosedRequests
import ru.lumastack.lumawork.domain.simpleone.SimpleOneRequest
import ru.lumastack.lumawork.ui.theme.AppTextField

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ClosedRequestsContent(
    records: List<SimpleOneRequest>,
    query: String,
    onQueryChange: (String) -> Unit,
    isLoading: Boolean,
    hasArchive: Boolean,
    offline: Boolean,
    onOpenRequest: (SimpleOneRequest) -> Unit,
    onOpenTerminal: (String) -> Unit,
    modifier: Modifier = Modifier,
    personalComment: @Composable (SimpleOneRequest) -> Unit = {},
) {
    val today = LocalDate.now()
    val prepared by androidx.compose.runtime.produceState(emptyList<ClosedRequestsItem>(), records) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { prepareClosedRequests(records) }
    }
    val automaticPeriod = remember(prepared, today) { defaultClosedRequestsPeriod(prepared, today) }
    var startDay by rememberSaveable { mutableStateOf<String?>(null) }
    var endDay by rememberSaveable { mutableStateOf<String?>(null) }
    val chosenPeriod = remember(startDay, endDay) {
        startDay?.let { start -> endDay?.let { end -> ClosedRequestsPeriod.between(LocalDate.parse(start), LocalDate.parse(end)) } }
    }
    val period = chosenPeriod ?: automaticPeriod
    val filtered = remember(prepared, query, period) { queryClosedRequests(prepared, query, period) }
    var page by rememberSaveable { mutableIntStateOf(1) }
    val pages = maxOf(1, (filtered.size + 29) / 30)
    val safePage = page.coerceIn(1, pages)
    val visible = filtered.drop((safePage - 1) * 30).take(30)
    val groups = visible.groupBy { it.dayKey }
    val listState = rememberLazyListState()
    var showPeriodPicker by remember { mutableStateOf(false) }
    LaunchedEffect(query, startDay, endDay, prepared) { page = 1; listState.scrollToItem(0) }
    LaunchedEffect(safePage) { listState.scrollToItem(0) }
    LazyColumn(
        state = listState, modifier = modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "closed-search") {
            AppTextField(value = query, onValueChange = onQueryChange, label = "Поиск по заявкам",
                search = true, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        item(key = "closed-header") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text("Закрытые заявки", style = MaterialTheme.typography.titleLarge)
                    Text("${filtered.size} из ${prepared.size}", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { showPeriodPicker = true }, enabled = prepared.any { it.day != null }) {
                    Text(when {
                        query.isNotBlank() -> "Весь архив"
                        chosenPeriod == null && period == ClosedRequestsPeriod(today.minusDays(1), today) -> "Сегодня и вчера"
                        period == null -> "Выбрать дату"
                        period.start == period.end -> period.start.format(PERIOD_FORMAT)
                        else -> "${period.start.format(PERIOD_FORMAT)}–${period.end.format(PERIOD_FORMAT)}"
                    })
                }
            }
        }
        if (offline && hasArchive) item(key = "closed-offline") {
            Text("Сохранённый архив — войдите в SimpleOne для обновления",
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        if (filtered.isEmpty()) item(key = "closed-empty") {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if ((isLoading && !hasArchive) || (records.isNotEmpty() && prepared.isEmpty())) CircularProgressIndicator()
                    Text(when {
                        records.isNotEmpty() && prepared.isEmpty() -> "Подготовка списка заявок…"
                        isLoading && !hasArchive -> "Загружаем архив…"
                        !hasArchive && offline -> "Войдите в SimpleOne, чтобы загрузить архив."
                        query.isNotBlank() -> "Ничего не найдено"
                        else -> "За выбранную дату заявок нет"
                    }, fontWeight = FontWeight.SemiBold)
                    if (hasArchive) Text(if (query.isNotBlank())
                        "Поиск выполнен по всему архиву. Измените запрос."
                    else "Выберите другой период или воспользуйтесь поиском по всему архиву.",
                        style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        groups.forEach { (dayKey, group) ->
            stickyHeader(key = "closed-day-$dayKey") {
                val day = group.first().day
                Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(12.dp)) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(closedRequestsDayTitle(day, group.first().timeText, today), fontWeight = FontWeight.SemiBold)
                        if (day != null) Text(day.format(DAY_CAPTION_FORMAT), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            items(group, key = { "closed-record-${it.request.id}" }) { item ->
                ClosedRequestCard(item, { onOpenRequest(item.request) }, onOpenTerminal, personalComment)
            }
        }
        if (pages > 1) item(key = "closed-pagination") {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { page = safePage - 1 }, enabled = safePage > 1) { Text("‹") }
                    val first = maxOf(1, minOf(safePage - 2, pages - 4))
                    (first..minOf(pages, first + 4)).forEach { number ->
                        TextButton(onClick = { page = number }) {
                            Text(number.toString(), fontWeight = if (number == safePage) FontWeight.Bold else FontWeight.Normal)
                        }
                    }
                    TextButton(onClick = { page = safePage + 1 }, enabled = safePage < pages) { Text("›") }
                }
                Text("Страница $safePage из $pages • ${visible.size} заявок", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    if (showPeriodPicker) {
        val days = prepared.mapNotNull { it.day }
        if (days.isNotEmpty()) ClosedRequestsPeriodDialog(
            available = ClosedRequestsPeriod(days.min(), days.max()), selected = period,
            onDismiss = { showPeriodPicker = false },
            onApply = { selected ->
                startDay = selected?.start?.toString(); endDay = selected?.end?.toString()
                showPeriodPicker = false
            },
        )
    }
}

@Composable
private fun ClosedRequestCard(
    item: ClosedRequestsItem, onClick: () -> Unit, onOpenTerminal: (String) -> Unit,
    personalComment: @Composable (SimpleOneRequest) -> Unit,
) {
    val request = item.request
    val isReturn = isClosedReturnEquipment(request.closedRequestType())
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(request.incomingNumber.ifBlank { request.number }, fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Surface(shape = RoundedCornerShape(12.dp), color = closedTypeColor(item.typeText)) {
                    Text(item.typeText.ifBlank { "Тип не указан" }, Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        color = Color.White, style = MaterialTheme.typography.labelMedium)
                }
            }
            ClosedCardRow(item.timeTitle, item.timeText.ifBlank { "Информация отсутствует" })
            if (!isReturn) {
                request.closedCustomer().takeIf { it.isNotBlank() }?.let { ClosedCardRow("Заказчик", it) }
                request.closedAddress().takeIf { it.isNotBlank() }?.let {
                    Column {
                        Text("Адрес", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(normalizedClosedAddress(it), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            request.closedTerminalId().takeIf { it.isNotBlank() }?.let { terminal ->
                RequestTerminalRow(terminal, onOpenTerminal)
            }
            if (!isReturn) personalComment(request)
        }
        Surface(color = requestStatusColor(item.statusText, closed = true)) {
            Text(item.statusText, Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp),
                color = requestStatusTextColor(item.statusText), style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun ClosedCardRow(title: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, Modifier.weight(1.4f), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

internal fun closedTypeColor(type: String): Color = when (type) {
    "Установка" -> Color(0xFF248557)
    "Демонтаж" -> Color(0xFFB83F3D)
    "Возврат ТО" -> Color(0xFFD16B21)
    "Сервисная" -> Color(0xFF2E6BC7)
    "Замена" -> Color(0xFF7552B8)
    else -> Color(0xFF57616B)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ClosedRequestsPeriodDialog(
    available: ClosedRequestsPeriod, selected: ClosedRequestsPeriod?, onDismiss: () -> Unit,
    onApply: (ClosedRequestsPeriod?) -> Unit,
    title: String = "Период закрытых заявок",
) {
    val initial = selected ?: ClosedRequestsPeriod(available.end, available.end)
    val selectableDates = remember(available) { object : SelectableDates {
        override fun isSelectableDate(utcTimeMillis: Long): Boolean = utcTimeMillis.closedUtcDay() in available
    } }
    val pickerState = rememberDateRangePickerState(
        initialSelectedStartDateMillis = initial.start.coerceIn(available.start, available.end).closedUtcMillis(),
        initialSelectedEndDateMillis = initial.end.coerceIn(available.start, available.end).closedUtcMillis(),
        yearRange = available.start.year..available.end.year, selectableDates = selectableDates,
    )
    DatePickerDialog(onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                val start = pickerState.selectedStartDateMillis?.closedUtcDay()
                val end = pickerState.selectedEndDateMillis?.closedUtcDay() ?: start
                if (start != null && end != null) onApply(ClosedRequestsPeriod.between(start, end))
            }, enabled = pickerState.selectedStartDateMillis != null) { Text("Применить") }
        }, dismissButton = {
            Row {
                TextButton(onClick = { onApply(null) }) { Text("Сбросить") }
                TextButton(onClick = onDismiss) { Text("Отмена") }
            }
        }) {
        DateRangePicker(state = pickerState, modifier = Modifier.heightIn(max = 480.dp),
            title = { Text(title, Modifier.padding(16.dp)) }, showModeToggle = true)
    }
}

private fun LocalDate.closedUtcMillis(): Long = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
private fun Long.closedUtcDay(): LocalDate = Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()
private val PERIOD_FORMAT = DateTimeFormatter.ofPattern("dd.MM")
private val DAY_CAPTION_FORMAT = DateTimeFormatter.ofPattern("EEEE", Locale.forLanguageTag("ru-RU"))

internal fun requestStatusColor(status: String, closed: Boolean): Color {
    val value = status.lowercase()
    if (listOf("ожидан", "waiting", "pending").any(value::contains)) return Color(0xFFF28C1C)
    if (!closed) return Color(0xFFBD332E)
    if (listOf("выполн", "закрыт", "done", "closed").any(value::contains)) return Color(0xFF1F6EC7)
    if (listOf("работ", "progress", "active").any(value::contains)) return Color(0xFF0A7A30)
    if (listOf("отказ", "отклон", "cancel", "reject").any(value::contains)) return Color(0xFFBD332E)
    return Color(0xFF57616B)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun RequestTerminalRow(terminal: String, onOpenTerminal: (String) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("ID терминала", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(terminal, modifier = Modifier.weight(1f).heightIn(min = 48.dp).then(Modifier.combinedClickable(
            onClick = {
                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("ID терминала", terminal))
                if (android.os.Build.VERSION.SDK_INT < 33) android.widget.Toast.makeText(context, "ID терминала скопирован", android.widget.Toast.LENGTH_SHORT).show()
            }, onClickLabel = "Скопировать ID терминала", onLongClick = { onOpenTerminal(terminal) },
            onLongClickLabel = "Открыть складские заявки")).wrapContentHeight(Alignment.CenterVertically),
            textAlign = androidx.compose.ui.text.style.TextAlign.End, textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
    }
}

internal fun requestStatusTextColor(status: String): Color =
    if (listOf("ожидан", "waiting", "pending").any(status.lowercase()::contains)) Color(0xFF2B1C08) else Color.White
