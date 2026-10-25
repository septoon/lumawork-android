package ru.lumastack.lumawork.ui.coordination

import ru.lumastack.lumawork.ui.theme.AppContentHorizontalPadding
import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import ru.lumastack.lumawork.data.coordination.GroupClosedRequestsState
import ru.lumastack.lumawork.domain.coordination.CoordinationRegion
import ru.lumastack.lumawork.domain.coordination.queryGroupClosedRequests
import ru.lumastack.lumawork.domain.requests.*
import ru.lumastack.lumawork.domain.simpleone.SimpleOneRequest
import ru.lumastack.lumawork.ui.requests.closedTypeColor
import ru.lumastack.lumawork.ui.requests.requestStatusColor
import ru.lumastack.lumawork.ui.requests.requestStatusTextColor
import ru.lumastack.lumawork.ui.theme.AppRefreshContainer
import ru.lumastack.lumawork.ui.theme.AppTextField
import ru.lumastack.lumawork.ui.theme.AppCard

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun CoordinationGroupClosedContent(scopeKey: String, state: GroupClosedRequestsState, authorized: Boolean,
    offline: Boolean, region: CoordinationRegion?, onRefresh: () -> Unit, onOpen: (SimpleOneRequest) -> Unit, copy: (String) -> Unit,
) {
    val context = LocalContext.current
    val preferences = remember(context) { context.getSharedPreferences("coordination-group-closed-filters", Context.MODE_PRIVATE) }
    var excluded by remember(scopeKey) { mutableStateOf(preferences.getStringSet(scopeKey, emptySet())?.toSet().orEmpty()) }
    var query by rememberSaveable(scopeKey) { mutableStateOf("") }
    var showTypes by remember { mutableStateOf(false) }
    var today by remember { mutableStateOf(LocalDate.now()) }
    LaunchedEffect(Unit) { while (true) { delay(60_000); today = LocalDate.now() } }
    // The repository retains the prepared index across navigation. The first projection can use it immediately.
    val key = GroupProjectionKey(state.revision, query, excluded, today)
    val initial = remember(scopeKey) { GroupProjection(key, queryGroupClosedRequests(state.preparedItems, query, excluded, today)) }
    val projection by produceState(initial, key) {
        value = withContext(Dispatchers.Default) { GroupProjection(key, queryGroupClosedRequests(state.preparedItems, query, excluded, today)) }
    }
    val filtered = projection.items
    val preparing = projection.key != key
    val days = remember(filtered) { filtered.groupBy { it.dayKey } }
    val listState = rememberLazyListState()
    LaunchedEffect(query, excluded) { listState.scrollToItem(0) }
    fun setExcluded(value: Set<String>) { excluded = value; preferences.edit().putStringSet(scopeKey, value).apply() }
    Column(Modifier.widthIn(max = 960.dp).fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Закрытые заявки группы", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Box {
                TextButton(onClick = { showTypes = true }, enabled = authorized && region != null) { Text("Типы") }
                DropdownMenu(showTypes, { showTypes = false }) {
                    DropdownMenuItem({ Text("Показать все") }, { setExcluded(emptySet()) })
                    state.availableTypes.forEach { (key, title) -> DropdownMenuItem({ Text(title) }, {
                        setExcluded(if (key in excluded) excluded - key else excluded + key)
                    }, trailingIcon = { if (key !in excluded) Icon(Icons.Default.Check, null) }) }
                }
            }
        }
        if (!authorized) { CoordinationMessage("SimpleOne не подключён", "Войдите в SimpleOne на экране «Заявки».", Modifier.padding(horizontal = AppContentHorizontalPadding, vertical = 16.dp)); return }
        if (region == null) {
            CoordinationMessage("Укажите город в профиле", "Для закрытых заявок группы нужен город инженера из списка поддерживаемых городов.", Modifier.padding(horizontal = AppContentHorizontalPadding, vertical = 16.dp))
            return
        }
        Text(region.title, Modifier.padding(horizontal = AppContentHorizontalPadding), style = MaterialTheme.typography.titleSmall)
        AppTextField(query, { query = it }, "Поиск по всему архиву", search = true, singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = AppContentHorizontalPadding, vertical = 8.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = AppContentHorizontalPadding, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("${filtered.size} из ${state.totalCount ?: state.records.size}", style = MaterialTheme.typography.bodySmall)
            Text(if (query.isBlank()) "Сегодня и вчера" else "Весь архив", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
        AppRefreshContainer(Modifier.weight(1f), state.isLoading, onRefresh, enabled = !offline) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = AppContentHorizontalPadding, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (offline) item("offline") { Text("Сохранённый архив. Обновление доступно при подключении к сети.", style = MaterialTheme.typography.bodySmall) }
                state.errorMessage?.let { error -> item("error") { Text(error, color = MaterialTheme.colorScheme.error) } }
                if (state.isLoading) item("progress") { Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text("Загружено ${state.loadedCount} из ${state.totalCount ?: "…"}", style = MaterialTheme.typography.bodySmall)
                } }
                if (!state.didLoadCache || (state.records.isEmpty() && state.isLoading) || (preparing && filtered.isEmpty())) item("initial") {
                    CoordinationMessage("Загружаю закрытые заявки группы", "Сохранённый архив появится сразу после чтения с устройства.")
                } else if (filtered.isEmpty()) item("empty") {
                    CoordinationMessage("Заявок нет", when {
                        state.records.isEmpty() -> "В сохранённом архиве нет закрытых заявок группы."
                        query.isNotBlank() -> "Поиск выполнен по всему архиву. Измените запрос или фильтр типов."
                        else -> "За сегодня и вчера нет заявок с выбранными типами."
                    })
                }
                days.forEach { (key, items) ->
                    stickyHeader("day-$key") {
                        val first = items.first()
                        Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface) {
                            Row(Modifier.padding(vertical = 10.dp, horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(closedRequestsDayTitle(first.day, first.timeText, today), fontWeight = FontWeight.SemiBold)
                                first.day?.let { Text(it.format(GROUP_DAY_CAPTION), style = MaterialTheme.typography.bodySmall) }
                            }
                        }
                    }
                    items(items, key = { "request-${it.request.id}" }) { item -> GroupClosedRequestCard(item, { onOpen(item.request) }, copy) }
                }
            }
        }
    }
}

@Composable
private fun GroupClosedRequestCard(item: ClosedRequestsItem, open: () -> Unit, copy: (String) -> Unit) {
    val record = item.request
    val returning = isClosedReturnEquipment(record.closedRequestType())
    AppCard(onClick = open, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(record.incomingNumber.ifBlank { record.number }, Modifier.weight(1f), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                Surface(shape = MaterialTheme.shapes.small, color = closedTypeColor(item.typeText)) {
                    Text(item.typeText.ifBlank { "Тип не указан" }, Modifier.padding(8.dp), color = androidx.compose.ui.graphics.Color.White, style = MaterialTheme.typography.labelSmall)
                }
            }
            GroupClosedField(item.timeTitle, item.timeText.ifBlank { "Дата не указана" })
            GroupClosedField("Исполнитель", record.assignedUser.ifBlank { "Не назначен" })
            if (!returning) {
                record.closedCustomer().takeIf(String::isNotBlank)?.let { GroupClosedField("Заказчик", it) }
                record.closedAddress().takeIf(String::isNotBlank)?.let { address -> Column {
                    Text("Адрес", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(normalizedClosedAddress(address), style = MaterialTheme.typography.bodyMedium)
                } }
            }
            record.closedTerminalId().takeIf(String::isNotBlank)?.let { terminal ->
                Row(verticalAlignment = Alignment.CenterVertically) { Text("ID терминала", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { copy(terminal) }) { Text(terminal) }
                }
            }
        }
        Surface(color = requestStatusColor(item.statusText, closed = true)) { Text(item.statusText, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), color = requestStatusTextColor(item.statusText), style = MaterialTheme.typography.labelMedium) }
    }
}
@Composable
private fun GroupClosedField(title: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, Modifier.weight(1.4f), style = MaterialTheme.typography.bodyMedium)
    }
}
private val GROUP_DAY_CAPTION = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Locale.forLanguageTag("ru"))

private data class GroupProjectionKey(val revision: Long, val query: String, val excluded: Set<String>, val today: LocalDate)
private data class GroupProjection(val key: GroupProjectionKey, val items: List<ClosedRequestsItem>)
