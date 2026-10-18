package ru.lumastack.lumawork.ui.salary

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.text.format.Formatter
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import java.io.ByteArrayInputStream
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.lumastack.lumawork.data.salary.SalaryDocumentInput
import ru.lumastack.lumawork.data.salary.SalaryDocumentsRepository
import ru.lumastack.lumawork.data.salary.readSalaryDocumentBytes
import ru.lumastack.lumawork.data.salary.salaryDocumentsCacheDirectory
import ru.lumastack.lumawork.domain.model.AppSession
import ru.lumastack.lumawork.ui.theme.AppOutlinedButton

@Composable
fun SalaryDocumentsCard(
    session: AppSession,
    startOffline: Boolean,
    month: String,
    onMessage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    key(session.user.id, session.token, startOffline, month) {
        SalaryDocumentsContent(session, startOffline, month, onMessage, modifier)
    }
}

@Composable
private fun SalaryDocumentsContent(session: AppSession, offline: Boolean, month: String, onMessage: (String) -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val presentationAccess = LocalSalaryPresentationAccess.current
    val scope = rememberCoroutineScope()
    val repository = remember { SalaryDocumentsRepository.create(context.applicationContext, session.user.id) }
    val documents by repository.documents.collectAsState()
    val document = documents.firstOrNull { it.month == month }
    var localMonths by remember { mutableStateOf(emptySet<String>()) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<Double?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var deleteConfirmation by remember { mutableStateOf(false) }
    var actions by remember { mutableStateOf(false) }
    var htmlPreview by remember { mutableStateOf<SalaryDocumentInput?>(null) }
    var exportInput by remember { mutableStateOf<SalaryDocumentInput?>(null) }
    val hasLocal = month in localMonths
    val locked = busy || (loading && document == null && !hasLocal)

    fun report(failure: Throwable) {
        if (failure is CancellationException) throw failure
        val message = failure.message?.takeIf { it.isNotBlank() } ?: "Не удалось выполнить действие с расчётным листком."
        error = message
        onMessage(message)
    }

    suspend fun refreshAndMigrate() {
        try {
            repository.refresh(session.user.id, session.token)
            repository.migrateLegacy(session.user.id, session.token) { value -> scope.launch { progress = value } }
            localMonths = repository.legacyMonths()
            error = null
        } finally { progress = null }
    }

    LaunchedEffect(Unit) {
        try {
            repository.hydrate(session.user.id)
            localMonths = repository.legacyMonths()
            if (!offline) refreshAndMigrate()
        } catch (failure: Exception) { report(failure) }
        finally { loading = false }
    }

    fun save(uri: Uri?) {
        val input = exportInput
        exportInput = null
        if (uri == null || input == null) return
        busy = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(input.data) }
                        ?: error("Не удалось сохранить расчётный листок.")
                }
                onMessage("Расчётный листок сохранён.")
            } catch (failure: Exception) { report(failure) }
            finally { busy = false }
        }
    }
    val savePdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf"), ::save)
    val saveHtml = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/html"), ::save)

    fun action(kind: String) {
        val ticket = presentationAccess?.ticket()
        if (presentationAccess != null && ticket == null) return
        val canPresent = { presentationAccess == null || presentationAccess.permits(ticket) }
        busy = true
        error = null
        scope.launch {
            try {
                val input = if (document != null) repository.documentInput(session.token, document, offline,
                    salaryDocumentsCacheDirectory(context, session.user.id, month))
                else repository.legacyInput(month) ?: error("Расчётный листок не найден.")
                if (!canPresent()) return@launch
                when (kind) {
                    "save" -> {
                        exportInput = input
                        if (input.mimeType == "application/pdf") savePdf.launch(input.fileName) else saveHtml.launch(input.fileName)
                    }
                    "share" -> shareOrOpenSalaryDocument(context, session.user.id, month, input, true, canPresent)
                    else -> if (input.mimeType == "text/html") htmlPreview = input
                        else shareOrOpenSalaryDocument(context, session.user.id, month, input, false, canPresent)
                }
            } catch (failure: Exception) { report(failure) }
            finally { busy = false }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && !offline) {
            busy = true
            error = null
            scope.launch {
                try {
                    val input = readSalaryDocument(context, uri)
                    progress = 0.0
                    repository.upload(session.user.id, session.token, month, input, offline) { value -> scope.launch { progress = value } }
                    // The picker may replace a month with a legacy file: migrate cleanup only
                    // after a fresh metadata list confirms this exact uploaded document.
                    repository.refresh(session.user.id, session.token)
                    onMessage("Расчётный листок сохранён на сервере.")
                } catch (failure: Exception) { report(failure) }
                finally { busy = false; progress = null }
            }
        }
    }

    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Расчётный листок", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (locked) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else if (!offline) TextButton(onClick = {
                    loading = true
                    scope.launch {
                        try { refreshAndMigrate() } catch (failure: Exception) { report(failure) }
                        finally { loading = false }
                    }
                }) { Text("Обновить") }
            }
            if (document != null) {
                Text(document.fileName, style = MaterialTheme.typography.bodyMedium)
                Text("${Formatter.formatShortFileSize(context, document.sizeBytes.toLong())} · на сервере", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else if (hasLocal) {
                Text("Локальный листок · ожидает синхронизации", style = MaterialTheme.typography.bodySmall)
            } else Text(if (loading) "Загрузка списка…" else "Листок за этот месяц не прикреплён.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            progress?.let { value ->
                LinearProgressIndicator(progress = { value.toFloat() }, modifier = Modifier.fillMaxWidth())
                Text("Загрузка на сервер · ${(value * 100).toInt()} %", style = MaterialTheme.typography.bodySmall)
            }
            if (document != null || hasLocal) Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { action("open") }, enabled = !locked) { Text("Открыть") }
                Column {
                    TextButton(onClick = { actions = true }, enabled = !locked) { Text("Действия") }
                    DropdownMenu(expanded = actions, onDismissRequest = { actions = false }) {
                        DropdownMenuItem(text = { Text("Поделиться") }, onClick = { actions = false; action("share") })
                        DropdownMenuItem(text = { Text("Сохранить") }, onClick = { actions = false; action("save") })
                        if (document != null && !offline) DropdownMenuItem(text = { Text("Удалить", color = MaterialTheme.colorScheme.error) }, onClick = { actions = false; deleteConfirmation = true })
                    }
                }
            }
            AppOutlinedButton(onClick = { picker.launch(SalaryDocumentInput.MIME_TYPES.toTypedArray()) }, enabled = !locked && !offline, modifier = Modifier.fillMaxWidth()) {
                Text(if (document != null || hasLocal) "Заменить листок" else "Прикрепить листок")
            }
            Text("PDF или HTML · до 20 МБ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (offline) Text("Офлайн: можно открыть ранее загруженный листок. Для синхронизации требуется интернет.", style = MaterialTheme.typography.bodySmall)
            error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        }
    }
    if (deleteConfirmation) AlertDialog(
        onDismissRequest = { deleteConfirmation = false }, title = { Text("Удалить расчётный листок?") },
        text = { Text("Листок за $month будет удалён с сервера.") },
        dismissButton = { TextButton(onClick = { deleteConfirmation = false }) { Text("Отмена") } },
        confirmButton = { TextButton(onClick = {
            deleteConfirmation = false
            busy = true
            scope.launch {
                try {
                    repository.delete(session.user.id, session.token, month, offline)
                    withContext(Dispatchers.IO) { salaryDocumentsCacheDirectory(context, session.user.id, month).deleteRecursively() }
                    onMessage("Расчётный листок удалён с сервера.")
                } catch (failure: Exception) { report(failure) }
                finally { busy = false }
            }
        }) { Text("Удалить", color = MaterialTheme.colorScheme.error) } },
    )
    htmlPreview?.let { input -> SalaryHtmlPreview(input) { htmlPreview = null } }
}

private suspend fun readSalaryDocument(context: Context, uri: Uri): SalaryDocumentInput = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver
    var name = "Расчётный листок"
    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0 && !cursor.isNull(nameIndex)) name = cursor.getString(nameIndex)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            require(sizeIndex < 0 || cursor.isNull(sizeIndex) || cursor.getLong(sizeIndex) <= SalaryDocumentInput.MAX_BYTES) { "Расчётный листок не должен превышать 20 МБ." }
        }
    }
    val bytes = resolver.openInputStream(uri)?.use(::readSalaryDocumentBytes) ?: error("Не удалось прочитать выбранный расчётный листок.")
    SalaryDocumentInput(bytes, name, resolver.getType(uri))
}

private suspend fun shareOrOpenSalaryDocument(context: Context, owner: String, month: String, input: SalaryDocumentInput, share: Boolean, canPresent: () -> Boolean) {
    val file = withContext(Dispatchers.IO) {
        val directory = File(salaryDocumentsCacheDirectory(context, owner, month), "export")
        directory.mkdirs()
        File(directory, input.fileName).apply { writeBytes(input.data) }
    }
    if (!canPresent()) return
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val intent = Intent(if (share) Intent.ACTION_SEND else Intent.ACTION_VIEW).apply {
        if (share) { type = input.mimeType; putExtra(Intent.EXTRA_STREAM, uri) }
        else setDataAndType(uri, input.mimeType)
        clipData = ClipData.newRawUri(input.fileName, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, if (share) "Поделиться расчётным листком" else "Открыть расчётный листок"))
}

@Composable
private fun SalaryHtmlPreview(input: SalaryDocumentInput, onClose: () -> Unit) {
    val html = remember(input) {
        val charset = when {
            input.data.size >= 2 && input.data[0] == 0xff.toByte() && input.data[1] == 0xfe.toByte() -> Charsets.UTF_16LE
            input.data.size >= 2 && input.data[0] == 0xfe.toByte() && input.data[1] == 0xff.toByte() -> Charsets.UTF_16BE
            else -> Charsets.UTF_8
        }
        input.data.toString(charset)
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Расчётный листок", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                    TextButton(onClick = onClose) { Text("Закрыть") }
                }
                AndroidView(modifier = Modifier.fillMaxWidth().weight(1f), factory = { context ->
                    WebView(context).apply {
                        settings.javaScriptEnabled = false
                        settings.domStorageEnabled = false
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        settings.blockNetworkLoads = true
                        settings.setSupportZoom(true)
                        settings.builtInZoomControls = true
                        settings.displayZoomControls = false
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?) = true
                            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?) =
                                WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(byteArrayOf()))
                        }
                        loadDataWithBaseURL("https://salary-document.invalid/", html, "text/html", "UTF-8", null)
                    }
                }, onRelease = { it.stopLoading(); it.destroy() })
            }
        }
    }
}
