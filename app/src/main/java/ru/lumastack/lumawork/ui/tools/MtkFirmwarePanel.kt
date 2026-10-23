package ru.lumastack.lumawork.ui.tools

import android.os.SystemClock
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import ru.lumastack.lumawork.R
import ru.lumastack.lumawork.data.ftp.FtpItem
import ru.lumastack.lumawork.data.tools.*

@Composable
internal fun MtkFirmwarePanel(state: MtkFirmwareUiState, tools: ToolsUiState, onAgent: () -> Unit, onArchive: () -> Unit,
    onFtp: () -> Unit, onInspect: () -> Unit, onInspectAgent: () -> Unit, onCancel: () -> Unit, onCancelDownload: () -> Unit,
    onDownload: () -> Unit, onEmergency: () -> Unit, onBootConfirmed: () -> Unit,
) {
    val colors = ToolsColors.current
    val operation = state.installation
    val waiting = operation.active && !state.serviceRunning && !state.loading
    val editable = !state.loading && !operation.active && !tools.globalInstallBusy && tools.mtkDownload == null
    var confirmDownload by remember { mutableStateOf(false) }
    var confirmEmergency by remember { mutableStateOf(false) }
    var diagnostics by remember { mutableStateOf(false) }
    var composition by remember(state.firmware?.sha256) { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        ToolsStepCard(1, "Терминал", colors.orange, artwork = R.drawable.tools_unitodi_p8) {
            Text("UniTodi P8", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
        ToolsStepCard(2, "Комплект прошивки", colors.mint) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    FirmwareSummaryRow(if (state.agentVerified) Icons.Outlined.CheckCircle else Icons.Outlined.Settings,
                        if (state.agentVerified) "Агент проверен" else "Download Agent не выбран", accent = colors.mint)
                }
                TextButton(onClick = onAgent, enabled = editable) { Text(if (state.agentVerified) "Заменить" else "Выбрать") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                ToolsActionButton("Выбрать на FTP", onFtp, Modifier.weight(1f), enabled = editable && state.agentVerified,
                    icon = ToolsIcons.folder, accent = colors.mint)
                TextButton(onClick = onArchive, enabled = editable && state.agentVerified) { Text("Из файла") }
            }
            when {
                tools.mtkDownload != null -> {
                    val download = tools.mtkDownload
                    ToolsSubpanel(colors.mint) {
                        Text("Загрузка комплекта", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Text(download.fileName, style = MaterialTheme.typography.bodySmall, color = colors.muted)
                        download.progress?.let { progress ->
                            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth(), color = colors.mint)
                        } ?: LinearProgressIndicator(Modifier.fillMaxWidth(), color = colors.mint)
                        Text(mtkBytes(download.bytesWritten) + " / " + (download.totalBytes?.let(::mtkBytes) ?: "—"),
                            style = MaterialTheme.typography.labelMedium, color = colors.muted)
                        if (download.canCancel) TextButton(onClick = onCancelDownload) { Text("Отменить загрузку") }
                    }
                }
                state.loading -> ToolsSubpanel(colors.mint) {
                    LinearProgressIndicator(Modifier.fillMaxWidth(), color = colors.mint)
                    Text(operation.fileName ?: "Проверка файлов…", style = MaterialTheme.typography.bodyMedium, color = colors.muted)
                }
                state.firmware != null -> {
                    val pkg = state.firmware
                    ToolsSubpanel(colors.mint) {
                        BoxWithConstraints(Modifier.fillMaxWidth()) {
                            val artwork = maxWidth >= 480.dp
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                                    FirmwareSummaryRow(Icons.Outlined.CheckCircle, "Комплект проверен", colors.mint, emphasized = true)
                                    FirmwareSummaryRow(ToolsIcons.system, "v1.0.48 · 11.03.2026")
                                    FirmwareSummaryRow(ToolsIcons.folder, mtkBytes(pkg.images.distinctBy { it.file }.sumOf { it.size }))
                                }
                                if (artwork) ToolsZipArtwork(Modifier.size(112.dp))
                            }
                        }
                    }
                    TextButton(onClick = { composition = !composition }) {
                        Text("Состав комплекта")
                        Icon(Icons.Outlined.ArrowDropDown, null, Modifier.rotate(if (composition) 180f else 0f))
                    }
                    if (composition) SelectionContainer {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(pkg.profile, style = MaterialTheme.typography.bodySmall, color = colors.muted)
                            Text("SHA-256 комплекта: " + pkg.sha256, style = MaterialTheme.typography.bodySmall)
                            pkg.images.forEach { image ->
                                Text(image.partitionName + " · " + image.file.name + " · " + mtkBytes(image.size) + "\n" + image.sha256,
                                    style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                else -> ToolsSubpanel(colors.mint) {
                    FirmwareSummaryRow(Icons.Outlined.Info,
                        if (state.agentVerified) "Выберите комплект прошивки." else "Выберите Download Agent.", accent = colors.mint)
                    Text("Поддерживаемый комплект: v1.0.48 · 11.03.2026", style = MaterialTheme.typography.bodySmall, color = colors.muted)
                }
            }
            state.error?.let { message -> ToolsSubpanel(MaterialTheme.colorScheme.error) {
                FirmwareSummaryRow(Icons.Outlined.Warning, message, accent = MaterialTheme.colorScheme.error)
            } }
        }
        ToolsStepCard(3, "Подключение", colors.blue, decoration = ToolsIcons.usb) {
            ToolsSubpanel(colors.blue) {
                val target = operation.target
                FirmwareSummaryRow(if (target?.compatible == true) Icons.Outlined.CheckCircle else ToolsIcons.usb,
                    when {
                        operation.phase == MtkInstallPhase.VerifyingFiles && !state.serviceRunning -> "Проверка комплекта"
                        waiting && operation.phase == MtkInstallPhase.Permission -> "Ожидается разрешение USB"
                        state.serviceRunning || operation.active && operation.usbPermission -> "Терминал подключён"
                        waiting -> "Ожидание терминала"
                        target?.compatible == true -> "Терминал проверен"
                        else -> "Подключите P8 после нажатия Download"
                    }, accent = colors.blue)
            }
            TextButton(onClick = { diagnostics = !diagnostics }) {
                Text("Диагностика")
                Icon(Icons.Outlined.ArrowDropDown, null, Modifier.rotate(if (diagnostics) 180f else 0f))
            }
            if (diagnostics) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ToolsActionButton("Проверить USB", onInspect, enabled = editable, icon = Icons.Outlined.Refresh, accent = colors.blue)
                    ToolsActionButton("Проверить терминал", onInspectAgent, enabled = editable && state.agentVerified, icon = Icons.Outlined.Settings, accent = colors.blue)
                }
                Text("После проверки отключите USB и перезапустите терминал.",
                    style = MaterialTheme.typography.bodySmall, color = colors.muted)
                SelectionContainer { Text(operation.detail, style = MaterialTheme.typography.bodySmall, color = colors.muted) }
            }
        }
        ToolsStepCard(4, "Прошивка", colors.violet) {
            if (tools.globalInstallBusy && !operation.active) ToolsSubpanel(colors.violet) {
                FirmwareSummaryRow(Icons.Outlined.Info, "USB занят другой операцией.", accent = colors.violet)
            }
            if (operation.active || operation.upgradeRequested ||
                operation.phase in listOf(MtkInstallPhase.Failed, MtkInstallPhase.Interrupted, MtkInstallPhase.RecoveryRequired, MtkInstallPhase.WriteComplete)) {
                MtkFirmwareProgress(operation)
            }
            if (waiting) ToolsActionButton(
                if (operation.phase in listOf(MtkInstallPhase.Handshake, MtkInstallPhase.LoadingAgent)) "Прервать диагностику" else "Отменить ожидание",
                onCancel, Modifier.fillMaxWidth(), icon = Icons.Outlined.Close, accent = colors.blue)
            else if (state.serviceRunning) TextButton(onClick = { if (operation.destructiveStarted) confirmEmergency = true else onEmergency() }) {
                Text(if (operation.destructiveStarted) "Аварийно прервать запись" else "Остановить операцию", color = MaterialTheme.colorScheme.error)
            }
            if (!operation.active) ToolsActionButton("Download", { confirmDownload = true }, Modifier.fillMaxWidth(),
                enabled = state.canStartDownload && editable, icon = Icons.Outlined.PlayArrow, primary = true)
            if (operation.phase == MtkInstallPhase.WriteComplete) {
                if (operation.bootConfirmedAt == null) ToolsActionButton("Терминал загрузился", onBootConfirmed, Modifier.fillMaxWidth(),
                    icon = Icons.Outlined.CheckCircle, accent = colors.blue)
                else FirmwareSummaryRow(Icons.Outlined.CheckCircle, "Загрузка подтверждена инженером.", accent = colors.success)
            }
        }
    }
    if (confirmDownload) AlertDialog(onDismissRequest = { confirmDownload = false }, title = { Text("Прошивка UniTodi P8") },
        text = { Text("Дождитесь приглашения подключить выключенный терминал.") },
        confirmButton = { TextButton(onClick = { confirmDownload = false; onDownload() }) { Text("Начать Download") } },
        dismissButton = { TextButton(onClick = { confirmDownload = false }) { Text("Отмена") } })
    if (confirmEmergency) AlertDialog(onDismissRequest = { confirmEmergency = false }, title = { Text("Прервать запись?") },
        text = { Text("Терминал может перестать загружаться. Потребуется восстановление через Agenew.") },
        confirmButton = { TextButton(onClick = { confirmEmergency = false; onEmergency() }) { Text("Прервать", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { confirmEmergency = false }) { Text("Продолжить запись") } })
}

@Composable
private fun MtkFirmwareProgress(operation: MtkInstallState) {
    val colors = ToolsColors.current
    val writing = operation.phase == MtkInstallPhase.WritingPartition
    val speedMeter = remember(operation.operationId, operation.fileName, operation.phase) { MtkWriteSpeed() }
    val confirmedBytes by rememberUpdatedState(operation.bytes)
    var writeSpeed by remember(speedMeter) { mutableStateOf<Double?>(null) }
    LaunchedEffect(speedMeter) {
        if (writing) while (true) {
            writeSpeed = speedMeter.sample(confirmedBytes, SystemClock.elapsedRealtime())
            delay(1000)
        }
    }
    val complete = operation.phase == MtkInstallPhase.WriteComplete
    val failed = operation.phase in listOf(MtkInstallPhase.Failed, MtkInstallPhase.Interrupted, MtkInstallPhase.RecoveryRequired)
    val accent = when { complete -> colors.success; failed -> MaterialTheme.colorScheme.error; else -> colors.violet }
    ToolsSubpanel(accent) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (complete) Icons.Outlined.CheckCircle else if (failed) Icons.Outlined.Warning else Icons.Outlined.Settings,
                null, Modifier.size(24.dp), tint = accent)
            Text(when (operation.phase) {
                MtkInstallPhase.VerifyingFiles -> "Проверка комплекта"
                MtkInstallPhase.WaitingForUsb -> "Ожидание подключения"
                MtkInstallPhase.Permission -> "Разрешите доступ USB"
                MtkInstallPhase.Handshake -> "Проверка терминала"
                MtkInstallPhase.LoadingAgent -> "Запуск агента"
                MtkInstallPhase.BackingUp -> "Резервное чтение"
                MtkInstallPhase.PreparingStorage -> "Подготовка к прошивке"
                MtkInstallPhase.CheckingLayout -> "Проверка разметки"
                MtkInstallPhase.WritingPartition -> "Запись прошивки"
                MtkInstallPhase.Finalizing -> "Завершение записи"
                MtkInstallPhase.WriteComplete -> "Прошивка записана"
                MtkInstallPhase.RecoveryRequired -> "Требуется восстановление"
                MtkInstallPhase.Interrupted -> "Операция остановлена"
                else -> "Операция не завершена"
            }, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = colors.ink)
        }
        val progress = if (complete) 1f else if (operation.totalBytes > 0) (operation.bytes.toDouble() / operation.totalBytes).toFloat().coerceIn(0f, 1f) else null
        if (progress != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.weight(1f).height(9.dp).clip(RoundedCornerShape(50)),
                color = accent, trackColor = accent.copy(alpha = .14f))
            Text((progress * 100).toInt().toString() + "%", color = accent, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        } else if (operation.active) LinearProgressIndicator(Modifier.fillMaxWidth(), color = accent)
        if (writing) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Text(mtkBytes(operation.bytes) + " / " + mtkBytes(operation.totalBytes),
                style = MaterialTheme.typography.labelMedium, color = colors.muted)
            Text(writeSpeed?.let { "%.1f МБ/с".format(it) } ?: "— МБ/с",
                style = MaterialTheme.typography.labelMedium, color = colors.muted)
        }
        val hint = when (operation.phase) {
            MtkInstallPhase.WaitingForUsb -> "Подключите выключенный P8 по USB."
            MtkInstallPhase.Permission -> "Подтвердите разрешение на планшете."
            MtkInstallPhase.PreparingStorage, MtkInstallPhase.CheckingLayout,
            MtkInstallPhase.WritingPartition, MtkInstallPhase.Finalizing -> "Не отключайте USB и питание."
            MtkInstallPhase.WriteComplete -> MTK_WRITE_SUCCESS
            MtkInstallPhase.RecoveryRequired -> "Восстановите терминал через Agenew."
            MtkInstallPhase.Failed, MtkInstallPhase.Interrupted -> "Отключите USB и перезапустите терминал."
            else -> null
        }
        hint?.let { FirmwareSummaryRow(if (failed) Icons.Outlined.Warning else Icons.Outlined.Info, it, accent = accent) }
        operation.fileName?.let { FirmwareSummaryRow(Icons.Outlined.List, it) }
    }
}

/** Samples only acknowledged bytes; the first block starts timing after file verification. */
internal class MtkWriteSpeed {
    private var previousBytes: Long? = null
    private var previousTime = 0L
    fun sample(bytes: Long, nowMillis: Long): Double? {
        val before = previousBytes
        val elapsed = nowMillis - previousTime
        previousBytes = bytes.takeIf { it > 0 }
        previousTime = nowMillis
        if (bytes <= 0 || before == null || bytes < before || elapsed <= 0) return null
        return (bytes - before).toDouble() / elapsed * 1000 / 1_000_000
    }
}

@Composable
internal fun MtkArchivePicker(state: ToolsUiState, onClose: () -> Unit, onRefresh: () -> Unit,
    onDownload: (FtpItem) -> Unit, onSaved: (String) -> Unit,
) {
    val saved = state.files.filter { !it.downloading && it.name == MTK_P8_ARCHIVE && it.remotePath == "$MTK_P8_FTP_PATH/$MTK_P8_ARCHIVE" }
    val remote = state.ftpItems.filter { !it.isDirectory && it.name == MTK_P8_ARCHIVE }
    AlertDialog(onDismissRequest = onClose, title = { Text("UniTodi P8 · комплект с FTP") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("v1.0.48 · 11.03.2026", style = MaterialTheme.typography.bodySmall)
            if (state.ftpLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.ftpError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            LazyColumn(Modifier.heightIn(max = 300.dp)) {
                items(saved, key = { "saved:${it.id}" }) { file -> TextButton(onClick = { onSaved(file.id) }) { Text("Использовать скачанный комплект") } }
                items(remote, key = { it.path }) { file -> TextButton(onClick = { onDownload(file) }) { Text("Скачать с FTP · ${file.sizeText ?: file.name}") } }
                if (!state.ftpLoading && state.ftpError == null && remote.isEmpty() && saved.isEmpty()) item { Text("Проверенный комплект не найден.") }
            }
            TextButton(onClick = onRefresh, enabled = !state.ftpLoading) { Text("Обновить каталог") }
        }
    }, confirmButton = { TextButton(onClick = onClose) { Text("Закрыть") } })
}
private fun mtkBytes(bytes: Long): String = if (bytes >= 1024L * 1024 * 1024) "%.2f ГиБ".format(bytes.toDouble() / (1024 * 1024 * 1024)) else "%.1f МиБ".format(bytes.toDouble() / (1024 * 1024))
