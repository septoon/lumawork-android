package ru.lumastack.lumawork.ui.auto

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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.lumastack.lumawork.data.auto.AutoRepository
import ru.lumastack.lumawork.domain.auto.Vehicle
import ru.lumastack.lumawork.domain.auto.VehicleDocument
import ru.lumastack.lumawork.domain.auto.VehicleDocumentInput
import ru.lumastack.lumawork.domain.auto.VehicleDocumentKind
import ru.lumastack.lumawork.domain.auto.normalizedVehicleDocumentMime
import ru.lumastack.lumawork.domain.auto.safeVehicleDocumentName
import ru.lumastack.lumawork.domain.model.AppSession
import ru.lumastack.lumawork.ui.theme.AppFormDialog
import ru.lumastack.lumawork.ui.theme.AppOutlinedButton
import ru.lumastack.lumawork.ui.theme.AppTextField

@Composable
fun VehicleDocumentsSection(
    session: AppSession,
    vehicle: Vehicle,
    repository: AutoRepository,
    startOffline: Boolean,
    modifier: Modifier = Modifier,
) {
    // Changing account or selected vehicle disposes pending imports, downloads and uploads.
    key(session.user.id, session.token, vehicle.id, startOffline) {
        VehicleDocumentsContent(session, vehicle, repository, startOffline, modifier)
    }
}

@Composable
private fun VehicleDocumentsContent(
    session: AppSession,
    vehicle: Vehicle,
    repository: AutoRepository,
    startOffline: Boolean,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val messages = remember { SnackbarHostState() }
    var sourceMenu by remember { mutableStateOf(false) }
    var selection by remember { mutableStateOf<VehicleDocumentInput?>(null) }
    var busyDocument by remember { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<Double?>(null) }
    var pendingDeletion by remember { mutableStateOf<VehicleDocument?>(null) }
    var uploadError by remember { mutableStateOf<String?>(null) }
    val documents = vehicle.documents.orEmpty()
    val busy = busyDocument != null || importing || progress != null

    fun report(error: Throwable, fallback: String) {
        if (error is CancellationException) throw error
        scope.launch { messages.showSnackbar(error.message?.takeIf { it.isNotBlank() } ?: fallback) }
    }
    fun importDocument(uri: Uri) {
        importing = true
        scope.launch {
            try {
                selection = readVehicleDocument(context, uri)
                uploadError = null
            } catch (error: Exception) {
                report(error, "Не удалось прочитать выбранный документ.")
            } finally { importing = false }
        }
    }
    fun open(document: VehicleDocument, share: Boolean) {
        busyDocument = document.id
        scope.launch {
            try {
                val bytes = repository.downloadDocument(session, vehicle.id, document.id)
                val file = withContext(Dispatchers.IO) {
                    prepareVehicleDocument(context, session.user.id, vehicle.id, document, bytes)
                }
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                val intent = Intent(if (share) Intent.ACTION_SEND else Intent.ACTION_VIEW).apply {
                    if (share) {
                        type = document.mimeType
                        putExtra(Intent.EXTRA_STREAM, uri)
                    } else setDataAndType(uri, document.mimeType)
                    clipData = ClipData.newRawUri(document.fileName, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(intent, if (share) "Поделиться документом" else "Открыть документ"))
            } catch (error: Exception) {
                report(error, "Не удалось открыть документ.")
            } finally { busyDocument = null }
        }
    }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(::importDocument) }
    val photos = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(::importDocument) }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("ПРИКРЕПЛЁННЫЕ ДОКУМЕНТЫ", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (documents.isNotEmpty()) Text(documents.size.toString(), style = MaterialTheme.typography.labelMedium)
        }
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
            if (documents.isEmpty()) {
                Text("Добавьте полис ОСАГО, сканы СТС и ПТС или другой документ автомобиля.", Modifier.padding(14.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else Column {
                documents.forEachIndexed { index, document ->
                    var menu by remember(document.id) { mutableStateOf(false) }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(
                            Modifier.weight(1f).clickable(enabled = !busy && !startOffline) { open(document, false) }.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            Text(document.kind.title, fontWeight = FontWeight.SemiBold)
                            Text(document.fileName, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                            Text(documentMetadata(context, document), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (busyDocument == document.id) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                        else {
                            IconButton(onClick = { menu = true }, enabled = !busy && !startOffline) {
                                Icon(Icons.Default.MoreVert, "Действия с документом ${document.fileName}")
                            }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text("Открыть") }, onClick = { menu = false; open(document, false) })
                                DropdownMenuItem(text = { Text("Поделиться") }, onClick = { menu = false; open(document, true) })
                                DropdownMenuItem(text = { Text("Удалить", color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; pendingDeletion = document })
                            }
                        }
                    }
                    if (index < documents.lastIndex) HorizontalDivider(Modifier.padding(start = 20.dp))
                }
            }
        }
        AppOutlinedButton(
            onClick = { sourceMenu = true },
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy && !startOffline && documents.size < 20,
        ) { Text(if (importing) "Подготовка документа…" else "Добавить документ") }
        Text("PDF, JPG, PNG, HEIC или WebP · до 20 МБ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (documents.size >= 20) Text("К автомобилю можно прикрепить не более 20 документов.", style = MaterialTheme.typography.bodySmall)
        if (startOffline) Text("Для работы с документами требуется подключение к интернету.", style = MaterialTheme.typography.bodySmall)
        SnackbarHost(messages)
    }
    if (sourceMenu) AlertDialog(
        onDismissRequest = { sourceMenu = false },
        title = { Text("Добавить документ") },
        text = {
            Column {
                TextButton(onClick = { sourceMenu = false; photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) { Text("Выбрать фото") }
                TextButton(onClick = { sourceMenu = false; files.launch(VehicleDocumentInput.ALLOWED_MIME_TYPES.toTypedArray()) }) { Text("Выбрать файл") }
            }
        },
        confirmButton = { TextButton(onClick = { sourceMenu = false }) { Text("Отмена") } },
    )
    selection?.let { input ->
        DocumentUploadDialog(input, progress, uploadError, onDismiss = { selection = null; uploadError = null }) { kind, name ->
            progress = 0.0
            uploadError = null
            scope.launch {
                try {
                    val confirmed = withContext(Dispatchers.IO) { VehicleDocumentInput(input.data, name, input.mimeType, kind, input.id) }
                    repository.uploadDocument(session, vehicle.id, confirmed) { value ->
                        scope.launch { progress = value }
                    }
                    selection = null
                    progress = null
                    messages.showSnackbar("Документ прикреплён к автомобилю.")
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    uploadError = error.message ?: "Не удалось прикрепить документ."
                } finally { progress = null }
            }
        }
    }
    pendingDeletion?.let { document ->
        AlertDialog(
            onDismissRequest = { pendingDeletion = null },
            title = { Text("Удалить документ?") },
            text = { Text(document.fileName) },
            dismissButton = { TextButton(onClick = { pendingDeletion = null }) { Text("Отмена") } },
            confirmButton = {
                TextButton(onClick = {
                    pendingDeletion = null
                    busyDocument = document.id
                    scope.launch {
                        try {
                            repository.deleteDocument(session, vehicle.id, document.id)
                            withContext(Dispatchers.IO) { vehicleDocumentCacheDirectory(context, session.user.id, vehicle.id, document.id).deleteRecursively() }
                            busyDocument = null
                            messages.showSnackbar("Документ удалён.")
                        } catch (error: Exception) { report(error, "Не удалось удалить документ.") }
                        finally { busyDocument = null }
                    }
                }) { Text("Удалить", color = MaterialTheme.colorScheme.error) }
            },
        )
    }
}

@Composable
private fun DocumentUploadDialog(
    input: VehicleDocumentInput,
    progress: Double?,
    error: String?,
    onDismiss: () -> Unit,
    onConfirm: (VehicleDocumentKind, String) -> Unit,
) {
    var kind by remember(input.id) { mutableStateOf(input.kind) }
    var name by remember(input.id) { mutableStateOf(input.fileName) }
    val context = LocalContext.current
    AppFormDialog(
        title = "Новый документ", onDismiss = onDismiss,
        confirmLabel = "Прикрепить документ", onConfirm = { onConfirm(kind, name) },
        isBusy = progress != null, confirmEnabled = name.isNotBlank(), errorMessage = error,
    ) {
        item { Text("Тип документа", style = MaterialTheme.typography.titleSmall) }
        VehicleDocumentKind.entries.forEach { option ->
            item(key = option.wireValue) {
                Row(Modifier.fillMaxWidth().clickable(enabled = progress == null) { kind = option }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = kind == option, onClick = { kind = option }, enabled = progress == null)
                    Text(option.title, Modifier.weight(1f))
                }
            }
        }
        item { AppTextField(value = name, onValueChange = { name = it }, label = "Название", enabled = progress == null, singleLine = true, modifier = Modifier.fillMaxWidth()) }
        item { Text("Размер: ${Formatter.formatShortFileSize(context, input.data.size.toLong())}") }
        progress?.let { value -> item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Загрузка на сервер")
                LinearProgressIndicator(progress = { value.toFloat() }, modifier = Modifier.fillMaxWidth())
                Text("${(value * 100).toInt()} %")
            }
        } }
    }
}

private suspend fun readVehicleDocument(context: Context, uri: Uri): VehicleDocumentInput = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver
    var name = "Документ-${UUID.randomUUID()}"
    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameColumn >= 0 && !cursor.isNull(nameColumn)) name = cursor.getString(nameColumn)
            val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
            require(sizeColumn < 0 || cursor.isNull(sizeColumn) || cursor.getLong(sizeColumn) <= VehicleDocumentInput.MAX_BYTES) { "Документ не должен превышать 20 МБ." }
        }
    }
    val mime = normalizedVehicleDocumentMime(resolver.getType(uri), name)
    require(mime in VehicleDocumentInput.ALLOWED_MIME_TYPES) { "Поддерживаются PDF, JPG, PNG, HEIC и WebP." }
    val data = resolver.openInputStream(uri)?.use { stream ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = stream.read(buffer)
            if (count == -1) break
            require(output.size() + count <= VehicleDocumentInput.MAX_BYTES) { "Документ не должен превышать 20 МБ." }
            output.write(buffer, 0, count)
        }
        output.toByteArray()
    } ?: error("Не удалось прочитать выбранный документ.")
    VehicleDocumentInput(data, name, mime, VehicleDocumentKind.suggested(name))
}

private fun vehicleDocumentCacheDirectory(context: Context, owner: String, vehicle: String, document: String): File {
    fun hash(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    return File(context.cacheDir, "vehicle-documents/${hash(owner)}/${hash(vehicle)}/${hash(document)}")
}

private fun prepareVehicleDocument(context: Context, owner: String, vehicle: String, document: VehicleDocument, bytes: ByteArray): File {
    val directory = vehicleDocumentCacheDirectory(context, owner, vehicle, document.id)
    directory.mkdirs()
    // Repeated previews replace only this document; stale temporary shares expire after a day.
    val root = File(context.cacheDir, "vehicle-documents")
    root.walkBottomUp().filter { it.isFile && System.currentTimeMillis() - it.lastModified() > 86_400_000L }.forEach(File::delete)
    return File(directory, safeVehicleDocumentName(document.fileName)).apply { writeBytes(bytes) }
}

private fun documentMetadata(context: Context, document: VehicleDocument): String {
    val size = Formatter.formatShortFileSize(context, document.sizeBytes.toLong())
    val date = runCatching {
        Instant.parse(document.createdAt).atZone(ZoneId.systemDefault()).toLocalDate().format(DateTimeFormatter.ofPattern("d MMM yyyy"))
    }.getOrNull()
    return if (date == null) size else "$size · $date"
}
