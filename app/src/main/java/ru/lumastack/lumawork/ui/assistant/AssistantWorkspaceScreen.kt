package ru.lumastack.lumawork.ui.assistant

import ru.lumastack.lumawork.ui.theme.AppModalSheet
import ru.lumastack.lumawork.ui.theme.AppContentHorizontalPadding
import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import ru.lumastack.lumawork.data.assistant.*
import ru.lumastack.lumawork.data.backpack.BackpackRepository
import ru.lumastack.lumawork.data.simpleone.SimpleOneRepository
import ru.lumastack.lumawork.data.wiki.WikiRepository
import ru.lumastack.lumawork.domain.model.AppSession
import ru.lumastack.lumawork.ui.adaptive.AppWidthClass
import ru.lumastack.lumawork.ui.theme.AppRefreshContainer
import ru.lumastack.lumawork.ui.shell.AppHeaderSegments
import ru.lumastack.lumawork.ui.theme.AppTextField
import ru.lumastack.lumawork.ui.wiki.WikiRoute
import ru.lumastack.lumawork.ui.wiki.WikiViewModel
import ru.lumastack.lumawork.ui.wiki.WikiCatalogInfoAction
import java.io.File
import ru.lumastack.lumawork.ui.theme.AppSurfaces
import ru.lumastack.lumawork.ui.theme.appScreenBackground
import ru.lumastack.lumawork.ui.theme.AppButton
import ru.lumastack.lumawork.ui.theme.AppCard

@Composable
fun AssistantWorkspaceRoute(session: AppSession, offline: Boolean, wiki: WikiRepository, simpleOne: SimpleOneRepository,
    backpack: BackpackRepository, widthClass: AppWidthClass, models: androidx.lifecycle.ViewModelStoreOwner, section: String, onSection: (String) -> Unit,
) {
    val context = LocalContext.current
    val model: AssistantViewModel = viewModel(viewModelStoreOwner = models, key = "assistant",
        factory = AssistantViewModel.factory(context, session, offline, simpleOne, backpack))
    val state by model.state.collectAsStateWithLifecycle()
    val wikiModel: WikiViewModel? = if (section == "wiki") viewModel(viewModelStoreOwner = models,
        key = "wiki-${session.user.id}-$offline", factory = WikiViewModel.factory(wiki, session.user.id, offline)) else null
    val historyState = rememberSaveableStateHolder()
    val snackbar = remember { SnackbarHostState() }
    var history by rememberSaveable { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(state.notice) { state.notice?.let { message ->
        val result = snackbar.showSnackbar(message, actionLabel = if (state.canRetry) "Повторить" else null)
        model.notice(null)
        if (result == SnackbarResult.ActionPerformed) model.retry()
    } }
    AppHeaderSegments(listOf("Чат", "Вики"), if (section == "wiki") 1 else 0,
        { keyboard?.hide(); onSection(if (it == 0) "chat" else "wiki") },
        actions = { wikiModel?.let { WikiCatalogInfoAction(it) } })
    Scaffold(modifier = Modifier.background(appScreenBackground()), containerColor = androidx.compose.ui.graphics.Color.Transparent, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (section != "wiki") Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.End) {
                if (state.messages.isNotEmpty() || state.conversation != null) TextButton(onClick = { model.newChat() }, enabled = !state.initializing) { Text("Новый чат") }
                TextButton(onClick = { keyboard?.hide(); history = true; model.history() }, enabled = !state.initializing) { Text("Предыдущие чаты") }
            }
            historyState.SaveableStateProvider(if (section == "wiki") "wiki" else "chat") {
                if (wikiModel != null) WikiRoute(wikiModel, widthClass, Modifier.weight(1f))
                else AssistantChat(model, state, offline, Modifier.weight(1f))
            }
        }
    }
    if (history) AssistantHistory(model, state, offline, onClose = { history = false })
}

@Composable
private fun AssistantChat(model: AssistantViewModel, state: AssistantState, offline: Boolean, modifier: Modifier) {
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var listening by remember { mutableStateOf(false) }
    val dictation = remember(model) { AssistantDictation(context,
        { text -> model.draft(listOf(model.state.value.saved.draft, text).filter(String::isNotBlank).joinToString(" ")) },
        { listening = it }, model::notice) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(dictation, lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) dictation.close()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); dictation.close() }
    }
    val audioPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) dictation.start() else model.notice("Для диктовки разрешите доступ к микрофону.")
    }
    var attachmentMenu by remember { mutableStateOf(false) }
    var cameraPath by rememberSaveable(model) { mutableStateOf<String?>(null) }
    val photo = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { it?.let { uri -> model.attach(uri, true) } }
    val file = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let { uri -> model.attach(uri) } }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val captured = cameraPath?.let(::File); cameraPath = null
        if (success && captured != null) model.attach(Uri.fromFile(captured), true) { captured.delete() } else captured?.delete()
    }
    fun takePicture() {
        try {
            val captured = File.createTempFile("assistant-photo-", ".jpg", context.cacheDir); cameraPath = captured.absolutePath
            camera.launch(FileProvider.getUriForFile(context, "${context.packageName}.files", captured))
        } catch (_: Exception) { cameraPath?.let(::File)?.delete(); cameraPath = null; model.notice("Не удалось открыть камеру.") }
    }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) takePicture() else model.notice("Для съёмки фото разрешите доступ к камере.")
    }
    val nearBottom by remember { derivedStateOf { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it >= list.layoutInfo.totalItemsCount - 3 } ?: true } }
    LaunchedEffect(state.messages.size, state.messages.lastOrNull()?.content?.length, state.activity) {
        if (nearBottom && list.layoutInfo.totalItemsCount > 0) list.scrollToItem(list.layoutInfo.totalItemsCount - 1)
    }
    Column(modifier.fillMaxWidth().imePadding(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.weight(1f).widthIn(max = 900.dp).fillMaxWidth()) {
            LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(horizontal = AppContentHorizontalPadding, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (offline) item { Text("Без сети · сохранённые чаты доступны для чтения", style = MaterialTheme.typography.labelMedium) }
                if (state.initializing || state.loadingMessages) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                if (state.messageCursor != null) item { TextButton(onClick = model::moreMessages, enabled = !state.loadingMessages && !state.sending) { Text("Предыдущие сообщения") } }
                if (state.messages.isEmpty() && !state.initializing && !state.loadingMessages) {
                    item { Spacer(Modifier.height(36.dp)); Text("Чем помочь?", style = MaterialTheme.typography.headlineSmall) }
                    item { Column { listOf("Найти инструкцию", "Проверить пробег", "Расходы на топливо").forEach { prompt ->
                        TextButton(onClick = { model.draft(prompt) }) { Text(prompt) }
                    } } }
                }
                items(state.messages, key = { it.id }) { message -> AssistantBubble(message, model, offline) }
                state.activity?.let { activity -> item { Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Text(activity, style = MaterialTheme.typography.bodyMedium)
                } } }
                if (state.full) item { AppCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                    Text("Лимит этого чата исчерпан. Создайте новый чат.")
                    TextButton(onClick = model::newChat) { Text("Новый чат") }
                } } }
            }
            if (!nearBottom && state.messages.isNotEmpty()) TextButton(onClick = { scope.launch { list.animateScrollToItem((list.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)) } },
                modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp)) { Text("К последнему сообщению ↓") }
        }
        HorizontalDivider()
        Column(Modifier.widthIn(max = 900.dp).fillMaxWidth().padding(horizontal = AppContentHorizontalPadding, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (state.preparingAttachment) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Подготовка вложения…", style = MaterialTheme.typography.bodySmall) }
            state.attachment?.let { attachment ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text(attachment.title, style = MaterialTheme.typography.labelLarge)
                        attachment.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
                    TextButton(onClick = model::removeAttachment) { Text("Убрать") }
                }
            }
            AppTextField(state.saved.draft, model::draft, "Сообщение помощнику", placeholder = "Спросите о рабочей задаче…", showLabel = false,
                enabled = !state.initializing && !state.full && !listening && state.saved.conversationId !in state.deleting, minLines = 1, maxLines = 5)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box {
                    IconButton(onClick = { attachmentMenu = true }, enabled = !offline && !state.initializing && !state.sending && !state.preparingAttachment && !state.full && !listening) {
                        Icon(Icons.Default.Add, contentDescription = "Прикрепить файл")
                    }
                    DropdownMenu(attachmentMenu, { attachmentMenu = false }) {
                        DropdownMenuItem(text = { Text("Фото") }, onClick = { attachmentMenu = false; runCatching { photo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }.onFailure { model.notice("Фотогалерея недоступна.") } })
                        DropdownMenuItem(text = { Text("Камера") }, enabled = context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY), onClick = {
                            attachmentMenu = false
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) takePicture() else cameraPermission.launch(Manifest.permission.CAMERA)
                        })
                        DropdownMenuItem(text = { Text("Файл") }, onClick = { attachmentMenu = false; runCatching { file.launch(arrayOf("image/*", "application/pdf", "text/*", "application/json", "application/xml", "application/rtf", "application/msword", "application/vnd.ms-excel", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")) }.onFailure { model.notice("Выбор файлов недоступен.") } })
                    }
                }
                TextButton(onClick = {
                    if (listening) dictation.finish()
                    else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) dictation.start()
                    else audioPermission.launch(Manifest.permission.RECORD_AUDIO)
                }, enabled = !state.sending && !state.initializing && !state.full && !state.preparingAttachment) { Text(if (listening) "Завершить" else "Голос") }
                Spacer(Modifier.weight(1f))
                if (state.sending) TextButton(onClick = model::stop) { Text("Стоп") }
                else AppButton(onClick = { dictation.close(); keyboard?.hide(); model.send() }, enabled = !offline && !state.initializing && !state.loadingMessages && !state.full && !state.preparingAttachment && !listening && state.saved.conversationId !in state.deleting && (state.saved.draft.isNotBlank() || state.attachment != null)) { Text("Отправить") }
            }
            if (state.canRetry) TextButton(onClick = model::retry, enabled = !offline) { Text("Повторить последний запрос") }
        }
    }
}

@Composable
private fun AssistantBubble(message: AssistantMessage, model: AssistantViewModel, offline: Boolean) {
    val context = LocalContext.current
    val user = message.role == "user"
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (user) Alignment.End else Alignment.Start) {
        Surface(Modifier.widthIn(max = 760.dp).fillMaxWidth(if (user) .92f else 1f), shape = MaterialTheme.shapes.large,
            color = if (user) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                message.attachmentMeta?.get("fileName")?.jsonPrimitive?.contentOrNull?.let { Text("Документ: $it", style = MaterialTheme.typography.labelMedium) }
                SelectionContainer { AssistantRichText(message.content) }
                if (message.sources.isNotEmpty()) Text("Источники: ${message.sources.joinToString(", ") { sourceName(it) }}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                message.primaryClaim?.let { claim ->
                    if (claim["state"]?.jsonPrimitive?.contentOrNull in listOf("CANDIDATE", "candidate", "DISPUTED", "disputed"))
                        Text("Сведения требуют проверки", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            TextButton(onClick = { (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Сообщение", message.content)) }) { Text("Копировать") }
            TextButton(onClick = { runCatching { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, message.content), "Поделиться сообщением")) }.onFailure { model.notice("Не удалось открыть экспорт сообщения.") } }) { Text("Поделиться") }
            if (user) TextButton(onClick = { model.draft(message.content) }) { Text("Изменить") }
            else if (message.primaryClaim != null) {
                val feedback = message.attachmentMeta?.get("knowledgeFeedback")?.jsonPrimitive?.contentOrNull
                TextButton(onClick = { model.feedback(message, "support") }, enabled = feedback == null && !offline) { Text(if (feedback == "support") "Полезно ✓" else "Полезно") }
                TextButton(onClick = { model.feedback(message, "contradict") }, enabled = feedback == null && !offline) { Text(if (feedback == "contradict") "Отмечено ✓" else "Есть ошибка") }
            }
        }
    }
}

private fun sourceName(value: String) = when (value.lowercase()) {
    "wiki" -> "Вики"; "routes" -> "Маршруты"; "fuel" -> "Топливо"; "vehicles" -> "Авто"; "maintenance" -> "Обслуживание"
    "active_requests" -> "Активные заявки"; "closed_requests" -> "Закрытые заявки"; "request_details" -> "Заявка"; "backpack" -> "Рюкзак"
    "ftp" -> "FTP"; "document" -> "Документ"; "vision" -> "Изображение"; else -> value
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AssistantHistory(model: AssistantViewModel, state: AssistantState, offline: Boolean, onClose: () -> Unit) {
    var rename by remember { mutableStateOf<AssistantConversation?>(null) }; var title by remember { mutableStateOf("") }
    var delete by remember { mutableStateOf<AssistantConversation?>(null) }
    AppModalSheet(onDismiss = onClose) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.85f).padding(horizontal = 16.dp)) {
            Text("Предыдущие чаты", style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = { model.newChat(); onClose() }) { Text("Новый чат") }
            AppRefreshContainer(Modifier.weight(1f), state.loadingHistory, { model.history() }, enabled = !offline) {
                LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                    if (state.saved.conversations.isEmpty() && !state.loadingHistory) item { Text("Предыдущих чатов пока нет.") }
                    items(state.saved.conversations, key = { it.id }) { conversation -> AppCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            TextButton(onClick = { model.open(conversation.id); onClose() }, enabled = conversation.id !in state.deleting) { Text(conversation.title, fontWeight = FontWeight.SemiBold) }
                            Text(conversation.preview, maxLines = 2, style = MaterialTheme.typography.bodyMedium)
                            Row {
                                TextButton(onClick = { rename = conversation; title = conversation.title }, enabled = !offline && conversation.id !in state.deleting) { Text("Переименовать") }
                                TextButton(onClick = { delete = conversation }, enabled = !offline && conversation.id !in state.deleting && !state.loadingMessages && !(state.sending && conversation.id == state.saved.conversationId)) { Text("Удалить") }
                            }
                        }
                    } }
                    if (state.historyCursor != null) item { TextButton(onClick = { model.history(true) }, enabled = !state.loadingHistory) { Text("Ещё чаты") } }
                }
            }
        }
    }
    rename?.let { conversation -> AlertDialog(onDismissRequest = { rename = null }, title = { Text("Название чата") }, text = {
        AppTextField(title, { title = it.take(80) }, "Название", singleLine = true)
    }, confirmButton = { TextButton(onClick = { model.rename(conversation.id, title); rename = null }, enabled = title.isNotBlank()) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = { rename = null }) { Text("Отмена") } }) }
    delete?.let { conversation -> AlertDialog(onDismissRequest = { delete = null }, title = { Text("Удалить чат?") }, text = { Text("Чат «${conversation.title}» будет удалён из серверной истории.") },
        confirmButton = { TextButton(onClick = { model.delete(conversation.id); delete = null }, enabled = conversation.id !in state.deleting && !state.loadingMessages && !(state.sending && conversation.id == state.saved.conversationId)) { Text("Удалить") } }, dismissButton = { TextButton(onClick = { delete = null }) { Text("Отмена") } }) }
}
