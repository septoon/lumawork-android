package ru.lumastack.lumawork.ui.fuel

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import java.io.File
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.lumastack.lumawork.data.fuel.FuelReceipt
import ru.lumastack.lumawork.data.fuel.FuelReceiptInput
import ru.lumastack.lumawork.data.fuel.FuelReceiptsRepository
import ru.lumastack.lumawork.data.fuel.readFuelReceiptBytes
import ru.lumastack.lumawork.domain.model.AppSession
import ru.lumastack.lumawork.domain.fuel.FuelRecord

@Composable
internal fun FuelReceiptsHost(
    session: AppSession, offline: Boolean,
    content: @Composable (List<FuelReceipt>, Boolean, (String) -> Unit) -> Unit,
) {
    key(session.user.id, session.token, offline) {
        val context = LocalContext.current
        val repository = remember { FuelReceiptsRepository.create(context.applicationContext, session.user.id) }
        val receipts by repository.receipts.collectAsState()
        val authorizationRequired by repository.authorizationRequired.collectAsState()
        var month by rememberSaveable { mutableStateOf<String?>(null) }
        var loading by remember { mutableStateOf(true) }
        var refreshing by remember { mutableStateOf(false) }
        var refreshError by remember { mutableStateOf<String?>(null) }
        val scope = rememberCoroutineScope()
        suspend fun refresh() {
            refreshing = true
            try { repository.refresh(session.token); refreshError = null }
            catch (failure: Exception) { if (failure is CancellationException) throw failure; refreshError = failure.message ?: "Не удалось обновить чеки." }
            finally { refreshing = false }
        }
        LaunchedEffect(repository) {
            try { repository.hydrate() } finally { loading = false }
            if (!offline) refresh()
        }
        content(receipts, loading) { month = it }
        month?.let { selected ->
            FuelReceiptsSheet(session, offline, authorizationRequired, selected, receipts.filter { it.month == selected }, repository,
                loading || refreshing, refreshError, onRefresh = { scope.launch { refresh() } }, onClose = { month = null })
        }
    }
}

@Composable
internal fun FuelReceiptsButton(receipts: List<FuelReceipt>, loading: Boolean, onClick: () -> Unit) {
    val fiscal = receipts.count { it.kind == "fiscal" }
    val bank = receipts.count { it.kind == "bank" }
    Surface(onClick = onClick, shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = .75f)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Чеки", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Text(if (loading) "Загрузка…" else "Фиск. $fiscal · Банк. $bank", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private data class ReceiptTarget(val id: String, val month: String, val kind: String)
private val ReceiptTargetSaver = Saver<ReceiptTarget?, List<String>>(
    save = { value -> value?.let { listOf(it.id, it.month, it.kind) } },
    restore = { ReceiptTarget(it[0], it[1], it[2]) },
)
private data class PendingReceipt(val target: ReceiptTarget, val input: FuelReceiptInput)

@Composable
private fun FuelReceiptsSheet(
    session: AppSession, offline: Boolean, authorizationRequired: Boolean, month: String, receipts: List<FuelReceipt>, repository: FuelReceiptsRepository,
    loading: Boolean, refreshError: String?, onRefresh: () -> Unit, onClose: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<Double?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var deleteReceipt by remember { mutableStateOf<FuelReceipt?>(null) }
    var pickerTarget by rememberSaveable(stateSaver = ReceiptTargetSaver) { mutableStateOf<ReceiptTarget?>(null) }
    var pending by remember { mutableStateOf<PendingReceipt?>(null) }
    var operation by remember { mutableStateOf<Job?>(null) }
    val locked = busy || pickerTarget != null
    fun report(failure: Exception) {
        if (failure is CancellationException) throw failure
        error = failure.message ?: "Не удалось выполнить действие с чеком."
    }
    fun upload(value: PendingReceipt) {
        busy = true
        error = null
        operation = scope.launch {
            try {
                progress = 0.0
                repository.upload(session.token, value.target.month, value.target.id, value.target.kind, value.input, offline) { amount -> scope.launch { progress = amount } }
                pending = null
            } catch (failure: Exception) { report(failure) }
            finally { busy = false; progress = null }
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val target = pickerTarget
        pickerTarget = null
        if (uri != null && target != null && !offline && !authorizationRequired) {
            busy = true
            error = null
            operation = scope.launch {
                try {
                    val value = PendingReceipt(target, readFuelReceipt(context, uri))
                    pending = value
                    // Keep the selected input until the server confirms it, so a failed upload can retry.
                    progress = 0.0
                    repository.upload(session.token, target.month, target.id, target.kind, value.input, offline) { amount -> scope.launch { progress = amount } }
                    pending = null
                } catch (failure: Exception) { report(failure) }
                finally { busy = false; progress = null }
            }
        }
    }
    fun select(kind: String, receipt: FuelReceipt? = null) {
        pickerTarget = ReceiptTarget(receipt?.id ?: UUID.randomUUID().toString(), month, kind)
        picker.launch(FuelReceiptInput.MIME_TYPES.toTypedArray())
    }
    val title = remember(month) { fuelReceiptMonthLabel(month) }
    Dialog(onDismissRequest = { if (!locked && pending == null) onClose() }, properties = DialogProperties(dismissOnBackPress = !locked && pending == null, dismissOnClickOutside = !locked && pending == null)) {
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxWidth().fillMaxHeight(.85f).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Чеки заправок", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = onClose, enabled = !locked && pending == null) { Text("Закрыть") }
                }
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    item(key = "status") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (offline) Text("Офлайн · доступны ранее открытые чеки", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text("PDF, JPEG, PNG, HEIC/HEIF · до 20 МБ", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                else if (!offline) TextButton(onClick = onRefresh, enabled = !locked && pending == null) { Text("Обновить") }
                            }
                            progress?.let { amount ->
                                LinearProgressIndicator(progress = { amount.toFloat() }, modifier = Modifier.fillMaxWidth())
                                Text("Сохранение · ${(amount * 100).toInt()} %", style = MaterialTheme.typography.labelSmall)
                            }
                            if (busy) TextButton(onClick = { operation?.cancel() }) { Text("Отменить действие") }
                            (error ?: refreshError)?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                            pending?.let { selected ->
                                if (!busy) Column {
                                    Text(selected.input.fileName, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Row {
                                        TextButton(onClick = { upload(selected) }, enabled = !offline && !authorizationRequired) { Text("Повторить") }
                                        TextButton(onClick = { pending = null }) { Text("Убрать") }
                                    }
                                }
                            }
                        }
                    }
                    listOf("fiscal" to "Фискальные", "bank" to "Банковские").forEach { (kind, label) ->
                        val group = receipts.filter { it.kind == kind }
                        item(key = kind) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text("$label · ${group.size}", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                TextButton(onClick = { select(kind) }, enabled = !locked && !offline && !authorizationRequired && pending == null) { Text("Прикрепить") }
                            }
                        }
                        if (group.isEmpty()) item(key = "$kind-empty") { Text("Чеки не прикреплены", Modifier.padding(bottom = 12.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        items(group, key = FuelReceipt::id) { receipt ->
                            FuelReceiptRow(receipt, !locked && !authorizationRequired && pending == null, !offline, onOpen = {
                                busy = true
                                error = null
                                operation = scope.launch {
                                    try {
                                        val file = repository.preview(session.token, receipt, offline)
                                        openFuelReceipt(context, receipt, file)
                                    } catch (failure: Exception) { report(failure) }
                                    finally { busy = false }
                                }
                            }, onReplace = { select(kind, receipt) }, onDelete = { deleteReceipt = receipt })
                        }
                    }
                }
            }
        }
    }
    deleteReceipt?.let { receipt ->
        AlertDialog(onDismissRequest = { deleteReceipt = null }, title = { Text("Удалить чек?") },
            text = { Text("«${receipt.fileName}» будет удалён с сервера.") },
            dismissButton = { TextButton(onClick = { deleteReceipt = null }) { Text("Отмена") } },
            confirmButton = { TextButton(onClick = {
                deleteReceipt = null
                busy = true
                error = null
                operation = scope.launch {
                    try { repository.delete(session.token, receipt, offline) }
                    catch (failure: Exception) { report(failure) }
                    finally { busy = false }
                }
            }) { Text("Удалить", color = MaterialTheme.colorScheme.error) } })
    }
}

@Composable
private fun FuelReceiptRow(receipt: FuelReceipt, enabled: Boolean, online: Boolean, onOpen: () -> Unit, onReplace: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val context = LocalContext.current
    Column {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(receipt.fileName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("${receipt.mimeType.substringAfter('/').uppercase()} · ${Formatter.formatShortFileSize(context, receipt.sizeBytes.toLong())}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = onOpen, enabled = enabled) { Text("Открыть") }
            Box {
                IconButton(onClick = { menu = true }, enabled = enabled && online) { Icon(Icons.Default.MoreVert, "Действия с чеком") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Заменить") }, onClick = { menu = false; onReplace() })
                    DropdownMenuItem(text = { Text("Удалить", color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}

private suspend fun readFuelReceipt(context: Context, uri: Uri): FuelReceiptInput = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver
    var name = "Чек"
    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0 && !cursor.isNull(nameIndex)) name = cursor.getString(nameIndex)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            require(sizeIndex < 0 || cursor.isNull(sizeIndex) || cursor.getLong(sizeIndex) <= FuelReceiptInput.MAX_BYTES) { "Чек не должен превышать 20 МБ." }
        }
    }
    val bytes = resolver.openInputStream(uri)?.use { readFuelReceiptBytes(it) } ?: error("Не удалось прочитать выбранный чек.")
    FuelReceiptInput(bytes, name, resolver.getType(uri))
}

private fun openFuelReceipt(context: Context, receipt: FuelReceipt, file: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, receipt.mimeType).apply {
        clipData = ClipData.newRawUri(receipt.fileName, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Открыть чек"))
}

// Uses the entire snapshot, including archived and nonselected years; receipt access never affects fuel totals.
internal fun fuelReceiptOnlyMonths(receipts: List<FuelReceipt>, records: List<FuelRecord>): List<String> {
    val recordMonths = records.mapTo(mutableSetOf(), FuelRecord::resolvedMonthKey)
    return receipts.map(FuelReceipt::month).distinct().filterNot { it in recordMonths }.sortedDescending()
}

internal fun fuelReceiptMonthLabel(month: String): String = YearMonth.parse(month)
    .format(DateTimeFormatter.ofPattern("LLLL yyyy", Locale.forLanguageTag("ru-RU")))
    .replaceFirstChar { it.titlecase() }
