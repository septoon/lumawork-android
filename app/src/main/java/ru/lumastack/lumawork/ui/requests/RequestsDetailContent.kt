package ru.lumastack.lumawork.ui.requests

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import ru.lumastack.lumawork.ui.theme.AppCard
import ru.lumastack.lumawork.ui.theme.AppSurfaces
import ru.lumastack.lumawork.ui.theme.LocalAppDarkTheme
import ru.lumastack.lumawork.ui.shell.AppHeaderActions

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
    }
    fun openExternal(primary: String, fallback: String = "") {
        fun launch(url: String): Boolean {
            if (url.isBlank()) return false
            return try { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())); true }
                catch (_: android.content.ActivityNotFoundException) { false }
        }
        if (!launch(primary) && !launch(fallback)) notice = "Не удалось открыть приложение чата."
    }
    val showsEquipment = request.source == SimpleOneRequestSource.Active && request.sysId.isNotBlank()
    var hidesEquipment by remember(request.id) { mutableStateOf(false) }
    val scrollConnection = remember(request.id) {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && consumed.y != 0f) hidesEquipment = consumed.y < 0
                return Offset.Zero
            }
        }
    }
    AppHeaderActions(title = { Text("Детали") }) {
        if (state.isDetailLoading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
    }
    val personalComment = comments.commentFor(request)
    val commentContent: (@Composable () -> Unit)? = if (personalComment == null) null else {
        { ClientPersonalCommentBlock(personalComment, onEdit = if (comments.canEdit(request)) ({ comments.openEditor(request) }) else null) }
    }
    Box(Modifier.fillMaxSize().nestedScroll(scrollConnection)) {
        RequestDetailsBody(request, data, onCopy = copy, onOpenTerminal = onOpenTerminal,
            bottomPadding = if (showsEquipment) 80.dp else 14.dp,
            personalComment = commentContent, onFieldClick = { field ->
                when (field.title) {
                    "Адрес" -> map = "https://yandex.ru/maps/".toHttpUrl().newBuilder().addQueryParameter("text", field.value).addQueryParameter("z", "16").build().toString()
                    "ИНН ТСП" -> companyTIN = normalizedClientCommentTIN(field.value).takeIf(String::isNotEmpty)
                    else -> copy(field.value)
                }
            }, beforeSections = {
                state.detailErrorMessage?.let { message -> item("detail-error") { Text(message, color = MaterialTheme.colorScheme.error) } }
                notice?.let { message -> item("notice") { Text(message, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium) } }
            }) {
            if (data.showsEvotorDrivers) item("drivers") { EvotorDriverActions(reference, session, state.isOffline, open = { openExternal(it) }) }
            if (data.showsChatQuestions) item("chat") {
                RequestActionsCard("Вопросы в чат", "Текст заявки скопируется автоматически") {
                    RequestActionButton("КА и пин", "Скопировать текст и открыть чат Dion", Icons.Default.Email) {
                        if (BuildConfig.DION_CHAT_URL.isBlank()) notice = "Чат Dion не настроен."
                        else { copy(data.kaAndPinMessage); openExternal(BuildConfig.DION_CHAT_URL) }
                    }
                    RequestActionButton("Активация ОФД", "Скопировать текст и открыть чат Dion", Icons.Default.CheckCircle) {
                        if (BuildConfig.DION_CHAT_URL.isBlank()) notice = "Чат Dion не настроен."
                        else { copy(data.ofdMessage); openExternal(BuildConfig.DION_CHAT_URL) }
                    }
                    RequestActionButton("Код автореги", "Скопировать текст и открыть Telegram", Icons.AutoMirrored.Filled.Send) {
                        copy(data.autoregMessage); openExternal(BuildConfig.TELEGRAM_APP_URL, BuildConfig.TELEGRAM_WEB_URL)
                    }
                    Text("После перехода в чат нажмите «Вставить» в поле сообщения и отправьте текст.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item("manager") {
                RequestActionsCard("Вопрос Менеджеру", "Текст заявки скопируется автоматически") {
                    RequestActionButton("Вопрос Менеджеру", "Скопировать текст для отправки менеджеру", Icons.Default.Person,
                        trailingIcon = RequestDetailIcons.Copy) {
                        copy(data.managerMessage); notice = "Текст скопирован. Вставьте его в нужный чат менеджера."
                    }
                }
            }
        }
        AnimatedVisibility(visible = showsEquipment && !hidesEquipment, modifier = Modifier.align(Alignment.BottomCenter),
            enter = fadeIn() + slideInVertically { it }, exit = fadeOut() + slideOutVertically { it }) {
            Surface(color = AppSurfaces.panel) {
                Column {
                    HorizontalDivider(color = MaterialTheme.colorScheme.primary.copy(alpha = .16f))
                    AppButton(onClick = { scope.launch {
                        val auth = actions.browserAuthKey()
                        if (auth == null) notice = "Войдите в SimpleOne, чтобы выдать оборудование."
                        else browser = "https://sd.servionica.ru/record/itsm_request/".toHttpUrl().newBuilder().addPathSegment(request.sysId).build().toString() to auth
                    } }, enabled = state.isAuthorized && !state.isOffline,
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = Color.White),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp).heightIn(min = 52.dp)) {
                        Icon(RequestDetailIcons.Browser, null, Modifier.size(20.dp))
                        Spacer(Modifier.width(10.dp))
                        Text("Выдача оборудования", style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
    }
    map?.let { HomeRouteBrowser(it) { map = null } }
    browser?.let { (url, auth) -> RequestsBrowser(url, auth) { browser = null; actions.onOpenRequest(request) } }
    companyTIN?.let { CompanyLookupDialog(it, session, reference, state.isOffline) { companyTIN = null } }
}

@Composable
internal fun RequestDetailsBody(
    request: SimpleOneRequest,
    data: RequestDetailsData,
    onCopy: (String) -> Unit,
    onOpenTerminal: ((String) -> Unit)? = null,
    bottomPadding: androidx.compose.ui.unit.Dp = 14.dp,
    personalComment: (@Composable () -> Unit)? = null,
    onFieldClick: (RequestDetailField) -> Unit = { onCopy(it.value) },
    beforeSections: LazyListScope.() -> Unit = {},
    afterSections: LazyListScope.() -> Unit = {},
) {
    var barcode by rememberSaveable(request.id) { mutableStateOf<String?>(null) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 14.dp, bottom = bottomPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item("header") { RequestDetailsHeader(data, onCopy, onOpenTerminal) }
        beforeSections()
        if (personalComment != null) item("comment") { personalComment() }
        items(data.sections, key = { it.id }) { section ->
            RequestDetailsSection(request.id, section, onCopy) { field ->
                if (field.title.startsWith("S/N")) barcode = field.value else onFieldClick(field)
            }
        }
        afterSections()
    }
    barcode?.let { ru.lumastack.lumawork.ui.backpack.SerialBarcodeSheet(it) { barcode = null } }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RequestDetailsHeader(data: RequestDetailsData, onCopy: (String) -> Unit, onOpenTerminal: ((String) -> Unit)?) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val sla = data.sla()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(11.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SelectionContainer(Modifier.weight(1f)) {
                Text(data.number, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            IconButton(onClick = { onCopy(data.number) }, modifier = Modifier.size(32.dp)) {
                Icon(RequestDetailIcons.Copy, "Скопировать номер заявки", Modifier.size(20.dp), tint = muted)
            }
        }
        if (sla != null) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(sla.remainingText, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold,
                color = if (sla.isOverdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            LinearProgressIndicator(progress = { sla.progress }, modifier = Modifier.weight(1f).height(4.dp),
                color = if (sla.isOverdue) MaterialTheme.colorScheme.error else muted.copy(alpha = .95f),
                trackColor = muted.copy(alpha = .18f), gapSize = 0.dp, drawStopIndicator = {})
            Text("SLA", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = muted)
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            RequestHeaderMetric(RequestDetailIcons.Alarm, data.deadlineText)
            RequestHeaderMetric(RequestDetailIcons.Sync, closedRequestTypeTitle(data.type))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                Text("TID", Modifier.width(31.dp), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = muted)
                Text(data.terminal, Modifier.weight(1f).then(if (data.terminal.isNotBlank()) Modifier.combinedClickable(
                    onClick = { onCopy(data.terminal) }, onClickLabel = "Скопировать ID терминала",
                    onLongClick = onOpenTerminal?.let { open -> { open(data.terminal) } }, onLongClickLabel = "Открыть складские заявки") else Modifier),
                    style = MaterialTheme.typography.bodyLarge, fontSize = 19.sp, fontWeight = FontWeight.Medium,
                    color = if (data.terminal.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    textDecoration = if (data.terminal.isNotBlank()) TextDecoration.Underline else null)
            }
        }
    }
}

@Composable
private fun RequestHeaderMetric(icon: ImageVector, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, null, Modifier.size(21.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        SelectionContainer { Text(value, style = MaterialTheme.typography.bodyLarge, fontSize = 19.sp, fontWeight = FontWeight.Medium) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RequestDetailsSection(requestID: String, section: RequestDetailSection, onCopy: (String) -> Unit,
    onFieldClick: (RequestDetailField) -> Unit) {
    var expanded by rememberSaveable(requestID, section.id) { mutableStateOf(section.id in listOf("task", "closureCode", "resolution")) }
    val haptics = LocalHapticFeedback.current
    val fill = if (LocalAppDarkTheme.current) Color(.10f, .12f, .14f, .86f) else Color.White.copy(alpha = .84f)
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), color = fill,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = .16f)), shadowElevation = 2.dp) {
        Column(Modifier.animateContentSize().padding(horizontal = 15.dp, vertical = if (expanded) 16.dp else 15.dp),
            verticalArrangement = Arrangement.spacedBy(if (expanded) 15.dp else 0.dp)) {
            Row(Modifier.fillMaxWidth().clickable {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                expanded = !expanded
            }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(section.title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Icon(if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    if (expanded) "Свернуть ${section.title}" else "Развернуть ${section.title}",
                    Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (expanded) Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (section.fields.isEmpty()) Text("Нет данных", fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurface)
                section.fields.forEach { field ->
                    val link = field.title in listOf("Адрес", "ИНН ТСП", "Телефон") || field.title.startsWith("S/N") || section.id == "equipment"
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (field.title.isNotBlank()) SelectionContainer {
                            Text("${field.title}:", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        val text: @Composable () -> Unit = {
                            Text(field.value, style = MaterialTheme.typography.bodyLarge, fontSize = 18.sp, fontWeight = FontWeight.Medium,
                                modifier = if (link) Modifier.fillMaxWidth().combinedClickable(onClick = { onFieldClick(field) },
                                    onLongClick = { onCopy(field.value) }) else Modifier,
                                textDecoration = if (link) TextDecoration.Underline else null,
                                color = if (link) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                        }
                        if (link) text() else SelectionContainer { text() }
                    }
                }
            }
        }
    }
}

@Composable
private fun RequestActionsCard(title: String, caption: String? = null, content: @Composable ColumnScope.() -> Unit) {
    AppCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                caption?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            content()
        }
    }
}

@Composable
private fun RequestActionButton(title: String, subtitle: String, icon: ImageVector,
    trailingIcon: ImageVector = RequestDetailIcons.External, onClick: () -> Unit) {
    AppButton(onClick, Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(trailingIcon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
        }
    }
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
    RequestActionsCard("Драйвер Эвотор") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf("Драйвер Aisino" to links.first, "Драйвер PAX" to links.second).forEach { (title, url) ->
                AppButton(onClick = { open(url) }, enabled = url.isNotBlank(), modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(16.dp), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 12.dp)) {
                    Column(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Icon(RequestDetailIcons.Download, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                            Icon(RequestDetailIcons.External, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
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

/** Small outline symbols matching the iOS detail controls without an icons dependency. */
private object RequestDetailIcons {
    val Copy = outline("Copy", "M8 5V2H21V17H18 M3 7H16V22H3Z")
    val Alarm = outline("Alarm", "M5 3L2 6 M19 3L22 6 M6 20L4 22 M18 20L20 22 M12 7V12L15 14 M21 12A9 9 0 1 1 3 12A9 9 0 1 1 21 12")
    val Sync = outline("Sync", "M20 8A8 8 0 0 0 6 5L3 8 M3 3V8H8 M4 16A8 8 0 0 0 18 19L21 16 M16 16H21V21")
    val External = outline("External", "M6 18L18 6 M6 6H18V18")
    val Browser = outline("Browser", "M22 12A10 10 0 1 1 2 12A10 10 0 1 1 22 12 M16 8L14 14L8 16L10 10Z")
    val Download = outline("Download", "M12 2V15 M7 10L12 15L17 10 M5 6H2V22H22V6H19")
    private fun outline(name: String, path: String) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        addPath(androidx.compose.ui.graphics.vector.PathParser().parsePathString(path).toNodes(),
            fill = null, stroke = androidx.compose.ui.graphics.SolidColor(Color.Black), strokeLineWidth = 1.8f,
            strokeLineCap = androidx.compose.ui.graphics.StrokeCap.Round, strokeLineJoin = androidx.compose.ui.graphics.StrokeJoin.Round)
    }.build()
}
