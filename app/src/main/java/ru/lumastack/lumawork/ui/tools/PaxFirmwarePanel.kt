package ru.lumastack.lumawork.ui.tools

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import ru.lumastack.lumawork.data.tools.*

@Composable
internal fun PaxFirmwarePanel(state: ToolsUiState, usb: UsbToolsState, onImport: () -> Unit,
    onFtp: () -> Unit, onDevice: () -> Unit, onStart: () -> Unit,
) {
    var confirm by remember { mutableStateOf(false) }
    var prepared by remember { mutableStateOf(false) }
    val installation = state.installation
    val active = installation.active
    val busy = state.firmwareBusy || state.globalInstallBusy || active
    val device = usb.devices.singleOrNull { it.hasPermission && isPaxUsbCandidate(it) }
    val passport = device?.let { usb.passports[it.key] }
    val compatible = passport?.let { it.model == "D270" && it.os?.startsWith("Nuxiums") == true && it.target == "Inpas" && it.fingerprint != null } == true
    ToolCard("Полная прошивка PAX D270") {
        Text("Загрузчик 8.1.12.274(R) · Nuxiums 3.1.24 · MAINAPP 3.0.65.014", style = MaterialTheme.typography.bodyMedium)
        if (state.globalInstallBusy && !active) Text("На этом планшете уже выполняется установка PAX. Дождитесь её завершения.")
        if (state.firmwareBusy) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("Проверка комплекта…")
        }
        state.firmware?.let { bundle ->
            val size = android.text.format.Formatter.formatFileSize(LocalContext.current, bundle.totalBytes)
            Text("Комплект готов · 10 файлов · $size", color = MaterialTheme.colorScheme.primary)
            Text("Состав и SHA-256 совпали с комплектом из успешного захвата. Подписи проверяет сам терминал.", style = MaterialTheme.typography.bodySmall)
        } ?: Text("Выберите ZIP полного комплекта: загрузчик, ОС и папка Nuxiums со script_VSS.list. Один архив MAINAPP не содержит всех компонентов.", style = MaterialTheme.typography.bodyMedium)
        if (!active) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onImport, enabled = !busy) { Text("Выбрать ZIP") }
                TextButton(onClick = onFtp, enabled = !busy) { Text("Прошивки PAX в FTP") }
            }
        }
        if (installation.phase !in listOf(PaxInstallPhase.Idle, PaxInstallPhase.Ready)) {
            HorizontalDivider()
            Text(installation.step?.title ?: "Установка PAX", style = MaterialTheme.typography.titleSmall)
            if (installation.stepCount > 0) Text("Этап ${installation.stepNumber} из ${installation.stepCount}", style = MaterialTheme.typography.labelMedium)
            Text(installation.detail, style = MaterialTheme.typography.bodyMedium,
                color = if (installation.phase in listOf(PaxInstallPhase.Failed, PaxInstallPhase.Interrupted)) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            installation.fileName?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (active) {
                if (installation.totalBytes > 0) {
                    LinearProgressIndicator(progress = { (installation.bytes.toFloat() / installation.totalBytes).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    val formatter = LocalContext.current
                    Text("${installation.bytes * 100 / installation.totalBytes}% · ${android.text.format.Formatter.formatFileSize(formatter, installation.bytes)} / ${android.text.format.Formatter.formatFileSize(formatter, installation.totalBytes)}", style = MaterialTheme.typography.bodySmall)
                } else LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("Не отсоединяйте кабель. Установка продолжится при сворачивании приложения. После перезагрузки оставайтесь в main menu и разрешите повторный USB-доступ.", style = MaterialTheme.typography.bodySmall)
            }
        }
        if (!active) {
            if (!compatible) TextButton(onClick = onDevice) { Text("Прочитать паспорт D270") }
            if (state.firmware != null) Button(onClick = { prepared = false; confirm = true },
                enabled = !busy && compatible && usb.probingKey == null) { Text("Полный цикл прошивки…") }
            if (installation.phase in listOf(PaxInstallPhase.Failed, PaxInstallPhase.Interrupted))
                Text("Повтор запускает процесс с начала. Сначала проверьте состояние PAX; восстановление не выполняется автоматически.", style = MaterialTheme.typography.bodySmall)
        }
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("Прошить PAX D270 полностью?") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Будут удалены MAINAPP и его данные, установлены загрузчик и ОС, выполнена перезагрузка. Затем будут установлены MAINAPP 3.0.65.014, файлы приложения и дополнительные пакеты.")
            Text("Во время записи загрузчика и ОС нельзя отключать питание или USB. Неверный комплект либо обрыв могут потребовать восстановления штатным инструментом PAX.")
            Row {
                Checkbox(checked = prepared, onCheckedChange = { prepared = it })
                Text("Питание стабильно, терминал выбран верно, к полному обновлению и возможному восстановлению готов.", Modifier.padding(top = 10.dp), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }, confirmButton = {
        TextButton(onClick = { confirm = false; onStart() }, enabled = prepared && !busy && compatible && usb.probingKey == null) { Text("Начать полный цикл") }
    }, dismissButton = { TextButton(onClick = { confirm = false }) { Text("Отмена") } })
}
