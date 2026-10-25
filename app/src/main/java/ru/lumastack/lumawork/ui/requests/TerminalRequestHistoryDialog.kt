package ru.lumastack.lumawork.ui.requests

import ru.lumastack.lumawork.ui.theme.AppContentHorizontalPadding
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CancellationException
import ru.lumastack.lumawork.data.simpleone.SimpleOneRepository
import ru.lumastack.lumawork.domain.requests.*
import ru.lumastack.lumawork.domain.simpleone.SimpleOneRequest
import ru.lumastack.lumawork.ui.theme.AppSurfaces

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TerminalRequestHistoryDialog(
    terminalId: String,
    ownerUserId: String,
    sessionFingerprint: String,
    repository: SimpleOneRepository,
    offline: Boolean,
    authorized: Boolean,
    onDismiss: () -> Unit,
) {
    val preferences = LocalContext.current.getSharedPreferences("terminal-request-history", Context.MODE_PRIVATE)
    var showsReturns by remember { mutableStateOf(preferences.getBoolean("shows-returns", true)) }
    var showsExpertise by remember { mutableStateOf(preferences.getBoolean("shows-expertise", true)) }
    var filtersExpanded by remember { mutableStateOf(false) }
    var revision by rememberSaveable { mutableIntStateOf(0) }
    val scope = "$ownerUserId|$sessionFingerprint|$terminalId|$authorized"
    var records by remember(scope) { mutableStateOf<List<SimpleOneRequest>>(emptyList()) }
    var hasSnapshot by remember(scope) { mutableStateOf(false) }
    var loading by remember(scope) { mutableStateOf(true) }
    var error by remember(scope) { mutableStateOf<String?>(null) }
    val visible = remember(records, terminalId, showsReturns, showsExpertise) {
        terminalRequestHistory(records, terminalId, showsReturns, showsExpertise)
    }
    fun saveFilters(returns: Boolean, expertise: Boolean) {
        showsReturns = returns
        showsExpertise = expertise
        preferences.edit { putBoolean("shows-returns", returns); putBoolean("shows-expertise", expertise) }
    }
    LaunchedEffect(scope, revision, offline) {
        loading = true
        error = null
        try {
            if (!authorized) error("SimpleOne не подключён. Войдите в разделе «Заявки».")
            if (!hasSnapshot) repository.cachedTerminalHistory(ownerUserId, terminalId)?.let {
                records = it
                hasSnapshot = true
            }
            if (offline) error("Нет подключения к сети. Показаны сохранённые данные, если они доступны.")
            records = repository.terminalHistory(ownerUserId, terminalId)
            hasSnapshot = true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = failure.message ?: "Не удалось загрузить историю выездов."
        } finally { loading = false }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = AppSurfaces.panel,
    ) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.94f)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box {
                    IconButton(onClick = { filtersExpanded = true }) {
                        Icon(RequestDetailIcons.Filter, "Фильтр истории",
                            tint = if (showsReturns && showsExpertise) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary)
                    }
                    DropdownMenu(expanded = filtersExpanded, onDismissRequest = { filtersExpanded = false }) {
                        DropdownMenuItem(text = { Text("Показать все") }, onClick = { saveFilters(true, true); filtersExpanded = false })
                        DropdownMenuItem(text = { Text("Возврат ТО") }, leadingIcon = { Checkbox(showsReturns, onCheckedChange = null) },
                            onClick = { saveFilters(!showsReturns, showsExpertise) })
                        DropdownMenuItem(text = { Text("Экспертиза") }, leadingIcon = { Checkbox(showsExpertise, onCheckedChange = null) },
                            onClick = { saveFilters(showsReturns, !showsExpertise) })
                    }
                }
                RequestLoadingTitle(loading, Modifier.weight(1f),
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold), text = "История выездов")
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Закрыть историю") }
            }
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = AppContentHorizontalPadding, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item("terminal") { HistoryField("ID терминала", terminalId) }
                error?.let { message -> item("error") {
                    Column {
                        Text(message, color = MaterialTheme.colorScheme.error)
                        if (!offline && authorized) TextButton(onClick = { revision++ }) { Text("Повторить") }
                    }
                } }
                if (hasSnapshot && visible.isEmpty() && !loading) item("empty") {
                    Text(if (showsReturns && showsExpertise) "Закрытых заявок с этим ID терминала нет."
                        else "С выбранными фильтрами заявок нет.")
                }
                items(visible, key = { it.id }) { request ->
                    SelectionContainer {
                        RequestStatusCard(terminalHistoryStatus(request), closed = true, compact = false, compactStatus = true) {
                            val type = terminalHistoryType(request)
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(request.displayNumber, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                Surface(color = closedTypeColor(type), shape = RoundedCornerShape(12.dp)) {
                                    Text(type, Modifier.widthIn(max = 150.dp).padding(horizontal = 10.dp, vertical = 6.dp),
                                        color = Color.White, style = MaterialTheme.typography.labelMedium)
                                }
                            }
                            HistoryField("Дата", terminalHistoryDate(request)?.format(HISTORY_DATE_FORMAT) ?: "Не указана")
                            HistoryField("Исполнитель", request.assignedUser.ifBlank { "Не указан" })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryField(title: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        Text(title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SelectionContainer(Modifier.weight(1f)) {
            Text(value, Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.End,
                fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

private val HISTORY_DATE_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm", Locale.forLanguageTag("ru-RU"))
