package ru.lumastack.lumawork.ui.requests

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.core.content.edit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import ru.lumastack.lumawork.BuildConfig
import ru.lumastack.lumawork.data.requests.RequestReferenceApi
import ru.lumastack.lumawork.data.simpleone.SimpleOneRepository
import ru.lumastack.lumawork.domain.model.AppSession
import ru.lumastack.lumawork.domain.requests.*
import ru.lumastack.lumawork.domain.simpleone.SimpleOneRequest
import ru.lumastack.lumawork.domain.simpleone.SimpleOneRequestSource
import ru.lumastack.lumawork.ui.home.HomeRouteBrowser
import ru.lumastack.lumawork.ui.simpleone.SimpleOneUiState
import ru.lumastack.lumawork.ui.theme.AppButton
import ru.lumastack.lumawork.ui.theme.AppFormDialog

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun RequestsDetailContent(request: SimpleOneRequest, state: SimpleOneUiState, actions: RequestsActions,
    comments: ClientPersonalCommentsController, session: AppSession, repository: SimpleOneRepository, json: Json,
    onOpenTerminal: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val data = remember(request) { RequestDetailsData(request) }
    val reference = remember(json) { RequestReferenceApi(BuildConfig.API_ORIGIN, json) }
    var notice by remember(request.id) { mutableStateOf<String?>(null) }
    var map by rememberSaveable(request.id) { mutableStateOf<String?>(null) }
    var companyTIN by rememberSaveable(request.id) { mutableStateOf<String?>(null) }
    var browser by remember(request.id) { mutableStateOf<Pair<String, String>?>(null) }
    val copy: (String) -> Unit = { text ->
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Заявка", text))
        notice = "Текст скопирован."
    }
    fun openExternal(primary: String, fallback: String = "") {
        fun launch(url: String): Boolean {
            if (url.isBlank()) return false
            return try { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())); true }
                catch (_: android.content.ActivityNotFoundException) { false }
        }
        if (!launch(primary) && !launch(fallback)) notice = "Не удалось открыть приложение чата."
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Детали", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            if (state.isDetailLoading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item("header") {
                Card {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SelectionContainer(Modifier.weight(1f)) { Text(data.number, style = MaterialTheme.typography.titleLarge) }
                            TextButton(onClick = { copy(data.number) }) { Text("Копировать") }
                        }
                        Text(closedRequestTypeTitle(data.type), style = MaterialTheme.typography.labelLarge)
                        Text(request.displayState, color = MaterialTheme.colorScheme.primary)
                        if (request.source == SimpleOneRequestSource.Active) {
                            request.slaStatusText()?.let { Text(it, style = MaterialTheme.typography.labelMedium,
                                color = if (request.isOverdue()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) }
                            val start = request.registeredDateTime()
                            val end = request.deadlineDateTime()
                            if (start != null && end != null && end.isAfter(start)) {
                                val total = java.time.Duration.between(start, end).seconds.toDouble()
                                val elapsed = java.time.Duration.between(start, java.time.LocalDateTime.now()).seconds.toDouble()
                                LinearProgressIndicator(progress = { (elapsed / total).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                            }
                        }
                        request.deadlineDateTime()?.let { Text("Предельный срок СУТС: ${it.format(java.time.format.DateTimeFormatter.ofPattern("d MMM, HH:mm", java.util.Locale.forLanguageTag("ru")))}", style = MaterialTheme.typography.bodySmall) }
                        if (data.terminal.isNotBlank()) RequestTerminalRow(data.terminal, onOpenTerminal)
                    }
                }
            }
            state.detailErrorMessage?.let { message -> item("detail-error") { Text(message, color = MaterialTheme.colorScheme.error) } }
            notice?.let { message -> item("notice") { Text(message, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium) } }
            item("comment") {
                comments.commentFor(request)?.let { ClientPersonalCommentBlock(it, onEdit = if (comments.canEdit(request)) ({ comments.openEditor(request) }) else null) }
            }
            items(data.sections, key = { it.id }) { section ->
                var expanded by rememberSaveable(request.id, section.id) { mutableStateOf(section.id in listOf("task", "closureCode", "resolution")) }
                Card {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        TextButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth()) {
                            Text(section.title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                            Text(if (expanded) "⌃" else "⌄")
                        }
                        if (expanded) {
                            if (section.fields.isEmpty()) Text("Нет данных", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            section.fields.forEach { field ->
                                Column(Modifier.fillMaxWidth().combinedClickable(
                                    onClick = { when (field.title) {
                                        "Адрес" -> map = "https://yandex.ru/maps/".toHttpUrl().newBuilder().addQueryParameter("text", field.value).addQueryParameter("z", "16").build().toString()
                                        "ИНН ТСП" -> companyTIN = normalizedClientCommentTIN(field.value).takeIf(String::isNotEmpty)
                                        else -> copy(field.value)
                                    } }, onLongClick = { copy(field.value) })) {
                                    if (field.title.isNotBlank()) Text(field.title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    SelectionContainer { Text(field.value, style = MaterialTheme.typography.bodyMedium,
                                        color = if (field.title in listOf("Адрес", "ИНН ТСП")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface) }
                                }
                            }
                        }
                    }
                }
            }
            if (data.showsEvotorDrivers) item("drivers") { EvotorDriverActions(reference, session, state.isOffline, open = { openExternal(it) }) }
            if (data.showsChatQuestions) item("chat") {
                Card { Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text("Вопросы в чат", style = MaterialTheme.typography.titleMedium)
                    Text("Текст заявки скопируется автоматически", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = {
                        if (BuildConfig.DION_CHAT_URL.isBlank()) notice = "Чат Dion не настроен."
                        else { copy(data.kaAndPinMessage); openExternal(BuildConfig.DION_CHAT_URL) }
                    }) { Text("КА и пин") }
                    TextButton(onClick = {
                        if (BuildConfig.DION_CHAT_URL.isBlank()) notice = "Чат Dion не настроен."
                        else { copy(data.ofdMessage); openExternal(BuildConfig.DION_CHAT_URL) }
                    }) { Text("Активация ОФД") }
                    TextButton(onClick = { copy(data.autoregMessage); openExternal(BuildConfig.TELEGRAM_APP_URL, BuildConfig.TELEGRAM_WEB_URL) }) { Text("Код автореги") }
                    Text("После перехода в чат нажмите «Вставить» в поле сообщения и отправьте текст.", style = MaterialTheme.typography.bodySmall)
                } }
            }
            item("manager") { Card { Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text("Вопрос Менеджеру", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { copy(data.managerMessage); notice = "Текст скопирован. Вставьте его в нужный чат менеджера." }) { Text("Скопировать текст для отправки менеджеру") }
            } } }
        }
        if (request.source == SimpleOneRequestSource.Active && request.sysId.isNotBlank()) {
            Surface(tonalElevation = 3.dp) {
                AppButton(onClick = { scope.launch {
                    val auth = actions.browserAuthKey()
                    if (auth == null) notice = "Войдите в SimpleOne, чтобы выдать оборудование."
                    else browser = "https://sd.servionica.ru/record/itsm_request/".toHttpUrl().newBuilder().addPathSegment(request.sysId).build().toString() to auth
                } }, enabled = state.isAuthorized && !state.isOffline, modifier = Modifier.fillMaxWidth().padding(12.dp)) { Text("Выдача оборудования") }
            }
        }
    }
    map?.let { HomeRouteBrowser(it) { map = null } }
    browser?.let { (url, auth) -> RequestsBrowser(url, auth) { browser = null; actions.onOpenRequest(request) } }
    companyTIN?.let { CompanyLookupDialog(it, session, reference, state.isOffline) { companyTIN = null } }
}

@Composable
private fun EvotorDriverActions(api: RequestReferenceApi, session: AppSession, offline: Boolean, open: (String) -> Unit) {
    val context = LocalContext.current
    val cache = remember(session.user.id) { context.getSharedPreferences("request-driver-links", Context.MODE_PRIVATE) }
    val key = remember(session.user.id) { java.security.MessageDigest.getInstance("SHA-256").digest(session.user.id.toByteArray()).joinToString("") { "%02x".format(it) } }
    var links by remember(key) { mutableStateOf(cache.getString("$key-aisino", AISINO_DEFAULT).orEmpty() to cache.getString("$key-pax", PAX_DEFAULT).orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(api, session.token, offline) {
        if (!offline) try {
            links = api.drivers(session.token)
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { cache.edit { putString("$key-aisino", links.first); putString("$key-pax", links.second) } }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error = "Не удалось обновить ссылки драйверов. Показаны сохранённые." }
    }
    Card { Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text("Драйвер Эвотор", style = MaterialTheme.typography.titleMedium)
        TextButton(onClick = { open(links.first) }, enabled = links.first.isNotBlank()) { Text("Драйвер Aisino") }
        TextButton(onClick = { open(links.second) }, enabled = links.second.isNotBlank()) { Text("Драйвер PAX") }
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    } }
}

@Composable
private fun CompanyLookupDialog(tin: String, session: AppSession, api: RequestReferenceApi, offline: Boolean, onDismiss: () -> Unit) {
    var fields by remember(tin) { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var revision by remember { mutableIntStateOf(0) }
    LaunchedEffect(tin, revision) {
        loading = true; error = null
        try { if (offline) error("Для получения данных организации требуется подключение к сети."); fields = api.company(tin, session.token) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message ?: "Не удалось получить данные организации." }
        finally { loading = false }
    }
    AppFormDialog("Организация • $tin", onDismiss, if (error != null) "Повторить" else "Готово",
        { if (error != null) revision++ else onDismiss() }, confirmEnabled = !loading, errorMessage = error) {
        if (loading) item { CircularProgressIndicator() }
        items(fields) { (title, value) -> ReferenceField(title, value) }
    }
}

@Composable
internal fun WarehouseRequestsDialog(terminal: String, repository: SimpleOneRepository, owner: String, offline: Boolean, onDismiss: () -> Unit) {
    var records by remember(terminal) { mutableStateOf<List<SimpleOneRequest>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var revision by remember { mutableIntStateOf(0) }
    LaunchedEffect(terminal, revision) {
        loading = true; error = null
        try { if (offline) error("Войдите в SimpleOne для поиска складских заявок."); records = repository.warehouseRequests(owner, terminal) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message ?: "Не удалось загрузить складские заявки." }
        finally { loading = false }
    }
    AppFormDialog("Складские", onDismiss, if (error != null) "Повторить" else "Готово",
        { if (error != null) revision++ else onDismiss() }, confirmEnabled = !loading, errorMessage = error) {
        item { ReferenceField("ID терминала", terminal) }
        if (loading) item { CircularProgressIndicator() }
        if (!loading && error == null && records.isEmpty()) item { Text("Складская заявка не найдена") }
        records.forEach { request ->
            item(key = request.id) { Card { Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(request.incomingNumber.ifBlank { request.number }, style = MaterialTheme.typography.titleMedium)
                ReferenceField("Статус", request.state)
                ReferenceField("Зарегистрирована", request.registeredAt)
                ReferenceField("Исполнитель", request.assignedUser)
                ReferenceField("Группа", request.assignmentGroup)
                requestInformation(request).forEach { (title, value) -> ReferenceField(title, value) }
            } } }
        }
    }
}
@Composable private fun ReferenceField(title: String, value: String) {
    if (value.isBlank()) return
    Column { Text(title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SelectionContainer { Text(value, style = MaterialTheme.typography.bodyMedium) } }
}
private const val AISINO_DEFAULT = "https://market.evotor.ru/store/apps/43419d26-13cb-4f63-8b52-7ede7dbbc439"
private const val PAX_DEFAULT = "https://market.evotor.ru/store/apps/834b8dbb-2680-40cd-b1ff-98351f5f32e5#discussion-anchor"
