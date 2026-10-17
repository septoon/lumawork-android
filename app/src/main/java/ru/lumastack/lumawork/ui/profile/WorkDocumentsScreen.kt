package ru.lumastack.lumawork.ui.profile

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.lumastack.lumawork.data.profile.WorkDocumentsFailure
import ru.lumastack.lumawork.data.profile.WorkDocumentsRepository
import ru.lumastack.lumawork.domain.model.AppSession
import ru.lumastack.lumawork.domain.profile.*
import ru.lumastack.lumawork.ui.theme.AppFormDialog
import ru.lumastack.lumawork.ui.theme.AppOutlinedButton
import ru.lumastack.lumawork.ui.theme.AppTextField

@Composable
fun WorkDocumentsScreen(
    session: AppSession,
    offline: Boolean,
    canManage: Boolean,
    onDismiss: () -> Unit,
    repository: WorkDocumentsRepository,
) {
    // Disposing this scope cancels IO, pickers and pending uploads on account/session changes.
    key(session.user.id, session.token, offline, canManage) {
        WorkDocumentsContent(session, offline, canManage, onDismiss, repository)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WorkDocumentsContent(session: AppSession, offline: Boolean, canManage: Boolean, onDismiss: () -> Unit, repository: WorkDocumentsRepository) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val messages = remember { SnackbarHostState() }
    var documents by remember { mutableStateOf<List<WorkDocument>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var busyDocument by remember { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf(false) }
    var uploading by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<Double?>(null) }
    val operationScope = remember(session.user.id, session.token) {
        MessageDigest.getInstance("SHA-256").digest("${session.user.id}\n${session.token}".toByteArray()).joinToString("") { "%02x".format(it) }
    }
    val documentSaver = remember(operationScope) {
        Saver<WorkDocument?, String>(
            save = { document -> document?.let { "$operationScope:${Json.encodeToString(it)}" } },
            restore = { raw -> raw.takeIf { it.startsWith("$operationScope:") }?.substringAfter(':')
                ?.let { runCatching { Json.decodeFromString<WorkDocument>(it) }.getOrNull() } },
        )
    }
    var sourceDialog by rememberSaveable { mutableStateOf(false) }
    var replacementPending by rememberSaveable { mutableStateOf(false) }
    var replacement by rememberSaveable(stateSaver = documentSaver) { mutableStateOf<WorkDocument?>(null) }
    var selection by remember { mutableStateOf<WorkDocumentInput?>(null) }
    var selectionUri by rememberSaveable { mutableStateOf<String?>(null) }
    var editing by rememberSaveable(stateSaver = documentSaver) { mutableStateOf<WorkDocument?>(null) }
    var deleting by remember { mutableStateOf<WorkDocument?>(null) }
    var saveDocument by rememberSaveable(stateSaver = documentSaver) { mutableStateOf<WorkDocument?>(null) }
    var formError by remember { mutableStateOf<String?>(null) }
    val busy = busyDocument != null || importing || uploading || saveDocument != null

    fun errorMessage(error: Exception, fallback: String): String {
        if (error is CancellationException) throw error
        return if (error is WorkDocumentsFailure || error is IllegalArgumentException) error.message ?: fallback else fallback
    }
    fun report(error: Exception, fallback: String) {
        val message = errorMessage(error, fallback)
        scope.launch { messages.showSnackbar(message) }
    }
    fun refresh() {
        if (loading || offline || busy) return
        loading = true
        scope.launch {
            try { documents = repository.refresh(session); loadError = null }
            catch (error: Exception) { loadError = errorMessage(error, "Не удалось загрузить рабочие документы."); report(error, "Не удалось загрузить рабочие документы.") }
            finally { loading = false }
        }
    }
    LaunchedEffect(repository) {
        try {
            documents = repository.read(session.user.id)
            if (!offline) documents = repository.refresh(session)
        } catch (error: Exception) {
            loadError = errorMessage(error, "Не удалось загрузить рабочие документы.")
            report(error, "Не удалось загрузить рабочие документы.")
        } finally { loading = false }
    }
    LaunchedEffect(repository, session.user.id) {
        repository.changes.collect { (owner, items) -> if (owner == session.user.id) documents = items }
    }

    fun upload(input: WorkDocumentInput, category: WorkDocumentCategory, title: String, replacing: WorkDocument? = null) {
        if (offline || !canManage || busyDocument != null || importing || uploading || saveDocument != null) return
        uploading = true
        progress = 0.0
        formError = null
        scope.launch {
            try {
                documents = repository.upload(session, input, replacing, category, title, offline) { value ->
                    scope.launch { if (uploading) progress = value }
                }
                selection = null
                selectionUri = null
                messages.showSnackbar(if (replacing == null) "Документ прикреплён." else "Документ заменён.")
            } catch (error: Exception) {
                val message = errorMessage(error, "Не удалось прикрепить документ.")
                if (selection != null) formError = message else messages.showSnackbar(message)
            } finally { uploading = false; progress = null }
        }
    }
    fun importDocument(uri: Uri) {
        if (offline || !canManage || busyDocument != null || importing || uploading || saveDocument != null) return
        val replacing = replacement
        if (replacementPending && replacing == null) {
            replacementPending = false
            report(IllegalArgumentException("Не удалось восстановить цель замены. Выберите документ ещё раз."), "Не удалось заменить документ.")
            return
        }
        replacement = null
        replacementPending = false
        importing = true
        scope.launch {
            try {
                val input = readWorkDocument(context, uri)
                importing = false
                if (replacing != null) upload(input, replacing.category, replacing.title, replacing)
                else { selection = input; selectionUri = "$operationScope:$uri"; formError = null }
            } catch (error: Exception) { report(error, "Не удалось прочитать выбранный документ.") }
            finally { importing = false }
        }
    }
    LaunchedEffect(selectionUri) {
        val savedUri = selectionUri ?: return@LaunchedEffect
        if (!savedUri.startsWith("$operationScope:")) { selectionUri = null; return@LaunchedEffect }
        val uri = savedUri.substringAfter(':')
        if (selection == null && !offline && canManage) {
            importing = true
            try { selection = readWorkDocument(context, Uri.parse(uri)) }
            catch (error: Exception) { selectionUri = null; report(error, "Выберите документ ещё раз.") }
            finally { importing = false }
        }
    }
    fun open(document: WorkDocument, share: Boolean) {
        if (offline || busy) return
        busyDocument = document.id
        scope.launch {
            try {
                val bytes = repository.download(session, document.id, offline)
                val file = withContext(Dispatchers.IO) { prepareWorkDocument(context, session.user.id, document, bytes) }
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                val mime = normalizedWorkDocumentMime(document.mimeType, document.fileName).ifBlank { "application/octet-stream" }
                val intent = Intent(if (share) Intent.ACTION_SEND else Intent.ACTION_VIEW).apply {
                    if (share) { type = mime; putExtra(Intent.EXTRA_STREAM, uri) } else setDataAndType(uri, mime)
                    clipData = ClipData.newRawUri(document.fileName, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(intent, if (share) "Поделиться документом" else "Открыть документ"))
            } catch (error: Exception) { report(error, "Не удалось открыть документ. Выберите другое приложение или сохраните файл.") }
            finally { busyDocument = null }
        }
    }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri == null) { replacement = null; replacementPending = false } else importDocument(uri) }
    val photos = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri == null) { replacement = null; replacementPending = false } else importDocument(uri) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val document = saveDocument
        saveDocument = null
        if (uri != null && document == null) {
            report(IllegalArgumentException("Не удалось восстановить документ. Повторите сохранение."), "Не удалось сохранить документ.")
        }
        if (uri != null && document != null && !offline) {
            busyDocument = document.id
            scope.launch {
                try {
                    val bytes = repository.download(session, document.id, offline)
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri, "wt")?.use { stream -> stream.write(bytes) }
                            ?: error("Не удалось сохранить документ.")
                    }
                    messages.showSnackbar("Документ сохранён.")
                } catch (error: Exception) { report(error, "Не удалось сохранить документ.") }
                finally { busyDocument = null }
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { TopAppBar(title = { Text("Документы для работы") }, navigationIcon = {
            IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") }
        }, actions = {
            IconButton(onClick = ::refresh, enabled = !offline && !loading && !busy) { Icon(Icons.Default.Refresh, "Обновить документы") }
        }) },
        snackbarHost = { SnackbarHost(messages) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            LazyColumn(Modifier.widthIn(max = 760.dp).fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (offline) item { Text("Сохранённый список документов. Для открытия и изменений подключитесь к интернету.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (loading) item {
                    if (documents.isEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                        Text("Загружаю документы")
                    } else LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                if (documents.isEmpty() && !loading) item {
                    Surface(shape = RoundedCornerShape(18.dp)) {
                        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(if (loadError != null) "Не удалось загрузить документы" else "Документов пока нет", fontWeight = FontWeight.SemiBold)
                            Text(loadError ?: if (canManage) "Добавьте договоры, справки, положения и другие рабочие файлы." else "Документы можно добавить в настройках профиля.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (loadError != null && !offline) TextButton(onClick = ::refresh, enabled = !busy) { Text("Повторить") }
                        }
                    }
                }
                WorkDocumentCategory.entries.forEach { category ->
                    val categoryDocuments = documents.filter { it.category == category }
                    if (categoryDocuments.isNotEmpty()) {
                        item(key = "category-${category.wireValue}") { Text(category.title, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        items(categoryDocuments, key = { it.id }) { document ->
                            WorkDocumentRow(context, document, busyDocument == document.id, !offline && !busy,
                                onOpen = { open(document, false) },
                                onShare = { open(document, true) },
                                onSave = { saveDocument = document; save.launch(safeWorkDocumentName(document.fileName)) },
                                onEdit = if (canManage) ({ editing = document; formError = null }) else null,
                                onReplace = if (canManage) ({ replacement = document; replacementPending = true; sourceDialog = true }) else null,
                                onDelete = if (canManage) ({ deleting = document }) else null)
                        }
                    }
                }
                if (canManage) {
                    item {
                        AppOutlinedButton(onClick = { replacement = null; replacementPending = false; sourceDialog = true }, enabled = !offline && !busy && !loading && documents.size < WorkDocumentInput.MAX_DOCUMENTS, modifier = Modifier.fillMaxWidth()) {
                            Text(if (importing) "Подготовка документа…" else "Добавить документ")
                        }
                    }
                    item { Text("PDF, изображения, Word, Excel, PowerPoint, RTF и TXT · до 20 МБ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    if (documents.size >= WorkDocumentInput.MAX_DOCUMENTS) item { Text("Можно сохранить не более 100 рабочих документов.", style = MaterialTheme.typography.bodySmall) }
                }
                if (uploading && selection == null) item {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Загрузка на сервер")
                        LinearProgressIndicator(progress = { (progress ?: 0.0).toFloat() }, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
    if (sourceDialog && canManage) AlertDialog(onDismissRequest = { sourceDialog = false; replacement = null; replacementPending = false }, title = { Text(if (replacement == null) "Добавить документ" else "Заменить файл") }, text = {
        Column {
            TextButton(onClick = { sourceDialog = false; photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) { Text("Выбрать фото") }
            TextButton(onClick = { sourceDialog = false; files.launch(WorkDocumentInput.ALLOWED_MIME_TYPES.toTypedArray()) }) { Text("Выбрать файл") }
        }
    }, confirmButton = { TextButton(onClick = { sourceDialog = false; replacement = null; replacementPending = false }) { Text("Отмена") } })
    selection?.let { input ->
        WorkDocumentForm("Новый документ", input.title, input.category, input, uploading, progress, formError,
            onDismiss = { if (!uploading) { selection = null; selectionUri = null; formError = null } },
            onConfirm = { category, title -> upload(input, category, title) })
    }
    editing?.let { document ->
        WorkDocumentForm("Документ", document.title, document.category, null, busyDocument != null, null, formError,
            onDismiss = { if (busyDocument == null) { editing = null; formError = null } },
            onConfirm = { category, title ->
                busyDocument = document.id
                formError = null
                scope.launch {
                    try { documents = repository.update(session, document.copy(category = category, title = title), offline); editing = null }
                    catch (error: Exception) { formError = errorMessage(error, "Не удалось изменить документ.") }
                    finally { busyDocument = null }
                }
            })
    }
    deleting?.let { document ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Удалить документ?") }, text = { Text(document.title) },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Отмена") } },
            confirmButton = { TextButton(onClick = {
                deleting = null
                busyDocument = document.id
                scope.launch {
                    try {
                        documents = repository.delete(session, document.id, offline)
                        withContext(Dispatchers.IO) { workDocumentDirectory(context, session.user.id, document.id).deleteRecursively() }
                        messages.showSnackbar("Документ удалён.")
                    } catch (error: Exception) { report(error, "Не удалось удалить документ.") }
                    finally { busyDocument = null }
                }
            }) { Text("Удалить", color = MaterialTheme.colorScheme.error) } })
    }
}

@Composable
private fun WorkDocumentRow(context: Context, document: WorkDocument, busy: Boolean, enabled: Boolean,
    onOpen: () -> Unit, onShare: () -> Unit, onSave: () -> Unit,
    onEdit: (() -> Unit)?, onReplace: (() -> Unit)?, onDelete: (() -> Unit)?) {
    var menu by remember(document.id) { mutableStateOf(false) }
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).clickable(enabled = enabled, onClick = onOpen).padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(document.title, fontWeight = FontWeight.SemiBold)
                Text("${document.fileName} · ${Formatter.formatShortFileSize(context, document.sizeBytes.toLong())}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (busy) CircularProgressIndicator(Modifier.padding(12.dp).size(24.dp), strokeWidth = 2.dp)
            else Box {
                IconButton(onClick = { menu = true }, enabled = enabled) { Icon(Icons.Default.MoreVert, "Действия с документом ${document.title}") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Открыть") }, onClick = { menu = false; onOpen() })
                    DropdownMenuItem(text = { Text("Сохранить") }, onClick = { menu = false; onSave() })
                    DropdownMenuItem(text = { Text("Поделиться") }, onClick = { menu = false; onShare() })
                    onEdit?.let { action -> DropdownMenuItem(text = { Text("Изменить") }, onClick = { menu = false; action() }) }
                    onReplace?.let { action -> DropdownMenuItem(text = { Text("Заменить файл") }, onClick = { menu = false; action() }) }
                    onDelete?.let { action -> DropdownMenuItem(text = { Text("Удалить", color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; action() }) }
                }
            }
        }
    }
}

@Composable
private fun WorkDocumentForm(title: String, initialTitle: String, initialCategory: WorkDocumentCategory, input: WorkDocumentInput?,
    busy: Boolean, progress: Double?, error: String?, onDismiss: () -> Unit, onConfirm: (WorkDocumentCategory, String) -> Unit) {
    var name by rememberSaveable(initialTitle) { mutableStateOf(initialTitle) }
    var category by rememberSaveable(initialCategory) { mutableStateOf(initialCategory) }
    val context = LocalContext.current
    AppFormDialog(title = title, onDismiss = onDismiss, confirmLabel = if (input == null) "Сохранить" else "Прикрепить документ",
        onConfirm = { onConfirm(category, name.trim()) }, isBusy = busy, confirmEnabled = name.isNotBlank() && name.trim().length <= 200, errorMessage = error) {
        item { AppTextField(value = name, onValueChange = { name = it }, label = "Название", enabled = !busy, modifier = Modifier.fillMaxWidth(), singleLine = true) }
        item { Text("Категория", style = MaterialTheme.typography.titleSmall) }
        WorkDocumentCategory.entries.forEach { option -> item(key = option.wireValue) {
            Row(Modifier.fillMaxWidth().clickable(enabled = !busy) { category = option }, verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = category == option, onClick = { category = option }, enabled = !busy)
                Text(option.title, Modifier.weight(1f))
            }
        } }
        input?.let { item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(it.fileName)
                Text("Размер: ${Formatter.formatShortFileSize(context, it.data.size.toLong())}", style = MaterialTheme.typography.bodySmall)
            }
        } }
        progress?.let { value -> item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Загрузка на сервер · ${(value * 100).toInt()} %")
                LinearProgressIndicator(progress = { value.toFloat() }, modifier = Modifier.fillMaxWidth())
            }
        } }
    }
}

private suspend fun readWorkDocument(context: Context, uri: Uri): WorkDocumentInput = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver
    var name = "Документ-${UUID.randomUUID()}"
    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0 && !cursor.isNull(nameIndex)) name = cursor.getString(nameIndex)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            require(sizeIndex < 0 || cursor.isNull(sizeIndex) || cursor.getLong(sizeIndex) <= WorkDocumentInput.MAX_BYTES) { "Документ не должен превышать 20 МБ." }
        }
    }
    val mime = normalizedWorkDocumentMime(resolver.getType(uri), name)
    require(mime in WorkDocumentInput.ALLOWED_MIME_TYPES) { "Формат документа не поддерживается." }
    val bytes = resolver.openInputStream(uri)?.use { stream ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = stream.read(buffer)
            if (count == -1) break
            require(output.size() + count <= WorkDocumentInput.MAX_BYTES) { "Документ не должен превышать 20 МБ." }
            output.write(buffer, 0, count)
        }
        output.toByteArray()
    } ?: error("Не удалось прочитать выбранный документ.")
    WorkDocumentInput(bytes, name, mime)
}

private fun workDocumentDirectory(context: Context, owner: String, documentId: String): File {
    fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    return File(context.cacheDir, "work-documents/${hash(owner)}/${hash(documentId)}")
}

private suspend fun prepareWorkDocument(context: Context, owner: String, document: WorkDocument, bytes: ByteArray): File {
    currentCoroutineContext().ensureActive()
    val directory = workDocumentDirectory(context, owner, document.id)
    directory.mkdirs()
    File(context.cacheDir, "work-documents").walkBottomUp()
        .filter { it.isFile && System.currentTimeMillis() - it.lastModified() > 86_400_000L }.forEach(File::delete)
    currentCoroutineContext().ensureActive()
    return File(directory, safeWorkDocumentName(document.fileName)).apply { writeBytes(bytes) }
}
