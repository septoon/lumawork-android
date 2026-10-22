package ru.lumastack.lumawork.ui.tools

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import ru.lumastack.lumawork.data.tools.*
import ru.lumastack.lumawork.ui.theme.AppTextField

@Composable
internal fun PaxFirmwarePanel(
    state: ToolsUiState,
    usb: UsbToolsState,
    onModel: (PaxModel) -> Unit,
    onMode: (PaxInstallMode) -> Unit,
    onPinpadOs: (String) -> Unit,
    onImport: () -> Unit,
    onFtp: () -> Unit,
    onStart: () -> Unit,
    onResume: () -> Unit,
    onRetryConnection: () -> Unit,
    files: @Composable ColumnScope.() -> Unit,
    connection: @Composable ColumnScope.() -> Unit,
) {
    var confirm by remember { mutableStateOf(false) }
    var resuming by remember { mutableStateOf(false) }
    var stable by remember { mutableStateOf(false) }
    var s210Prepared by remember { mutableStateOf(false) }
    var retryConfirmed by remember { mutableStateOf(false) }
    val colors = ToolsColors.current
    val installation = state.installation
    val installationMatchesModel = installation.model == state.paxModel
    val completedCurrent = installationMatchesModel && installation.phase == PaxInstallPhase.Complete && installation.mode == state.installMode
    val canResume = installation.canResume && installationMatchesModel
    val active = installation.active
    val busy = state.firmwareBusy || state.globalInstallBusy || active
    val bundle = state.firmware?.takeIf { it.model == state.paxModel }
    val device = usb.devices.singleOrNull { it.hasPermission && isPaxUsbCandidate(it) }
    val passport = device?.let { usb.passports[it.key] }
    val pinpad = device?.let { usb.pinpads[it.key] }
    val modelConfirmed = passport?.model == state.paxModel.name || pinpad?.model == state.paxModel.name
    val s210IdentityConfirmed = pinpad?.let { it.model == PaxModel.S210.name && it.fingerprint != null } == true
    val connectionReady = device != null && if (state.paxModel == PaxModel.S210) {
        if (state.installMode == PaxInstallMode.MainApp) s210IdentityConfirmed else device.key in usb.pinpadOsBoot
    } else modelConfirmed
    val packageReady = (when (state.installMode) {
        PaxInstallMode.RemoveMainApp -> true
        PaxInstallMode.Prolin -> bundle?.hasOs == true
        PaxInstallMode.MainApp -> bundle?.hasMainApp == true
        PaxInstallMode.Full -> bundle?.let { it.hasOs && it.hasMainApp } == true
    }) && (state.paxModel != PaxModel.S210 || state.pinpadExpectedOs.isNotBlank())
    val canStart = !busy && connectionReady && packageReady && usb.probingKey == null &&
        !(state.paxModel == PaxModel.S210 && state.installMode == PaxInstallMode.RemoveMainApp)

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        ToolsStepCard(1, "Модель PAX", colors.orange, decoration = ToolsIcons.terminal) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PaxModel.entries.forEach { model ->
                    ToolsChoiceChip(model.name, state.paxModel == model, { onModel(model) }, enabled = !busy,
                        icon = if (state.paxModel == model) Icons.Outlined.CheckCircle else null)
                }
            }
        }
        ToolsStepCard(2, "Комплект прошивки", colors.mint) {
            state.firmwareError?.let { message ->
                ToolsSubpanel(MaterialTheme.colorScheme.error) {
                    Text("Комплект ${state.paxModel.title} не подготовлен", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.error)
                    Text(message, style = MaterialTheme.typography.bodyMedium, color = colors.ink)
                }
            }
            if (state.firmwareBusy) {
                ToolsSubpanel(colors.mint) {
                    LinearProgressIndicator(Modifier.fillMaxWidth(), color = colors.mint)
                    Text("Проверка архива…", style = MaterialTheme.typography.bodyMedium, color = colors.muted)
                }
            } else if (bundle != null) {
                ToolsSubpanel(colors.mint) {
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        val showArtwork = maxWidth >= 480.dp
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                                FirmwareSummaryRow(Icons.Outlined.List,
                                    android.text.format.Formatter.formatFileSize(LocalContext.current, bundle.totalBytes),
                                    accent = colors.mint, emphasized = true)
                                FirmwareSummaryRow(Icons.Outlined.Settings,
                                    listOfNotNull("BOOT".takeIf { bundle.hasBoot },
                                        (if (state.paxModel == PaxModel.S210) "OS" else "Prolin").takeIf { bundle.hasOs },
                                        (if (state.paxModel == PaxModel.S210) "APP" else "MAINAPP").takeIf { bundle.hasMainApp }).joinToString(" · "))
                                if (state.paxModel == PaxModel.S210) {
                                    bundle.os?.let { FirmwareSummaryRow(ToolsIcons.system, "OS: ${it.name}") }
                                    bundle.application?.let { FirmwareSummaryRow(Icons.Outlined.List, "APP: ${it.name}") }
                                } else {
                                    bundle.expectedOs?.let { FirmwareSummaryRow(ToolsIcons.system, "ОС: $it") }
                                    bundle.expectedMainApp?.let { FirmwareSummaryRow(Icons.Outlined.List, "Приложение: $it") }
                                    bundle.expectedBoot?.let { FirmwareSummaryRow(Icons.Outlined.Settings, "Загрузчик: $it") }
                                }
                            }
                            if (showArtwork) ToolsZipArtwork(Modifier.size(112.dp))
                        }
                    }
                }
            } else ToolsSubpanel(colors.mint) {
                FirmwareSummaryRow(Icons.Outlined.Info, "Выберите ZIP, RAR или 7z для ${state.paxModel.title}.", accent = colors.mint)
            }
            if (bundle != null && state.paxModel == PaxModel.S210) {
                AppTextField(state.pinpadExpectedOs, onPinpadOs, "Версия OS после установки", enabled = !busy, singleLine = true)
                Text("Для известного комплекта заполняется автоматически; для другого укажите версию из документации.",
                    style = MaterialTheme.typography.bodySmall, color = colors.muted)
            }
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                if (maxWidth >= 480.dp) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ToolsActionButton("Выбрать ZIP / RAR / 7z", onImport, Modifier.weight(1f), enabled = !busy,
                        icon = ToolsIcons.folder, primary = true)
                    ToolsActionButton("Загрузить через FTP", onFtp, Modifier.weight(1.15f), enabled = !busy,
                        icon = Icons.Outlined.Send, accent = colors.mint)
                } else Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ToolsActionButton("Выбрать ZIP / RAR / 7z", onImport, Modifier.fillMaxWidth(), enabled = !busy,
                        icon = ToolsIcons.folder, primary = true)
                    ToolsActionButton("Загрузить через FTP", onFtp, Modifier.fillMaxWidth(), enabled = !busy,
                        icon = Icons.Outlined.Send, accent = colors.mint)
                }
            }
            files()
        }
        ToolsStepCard(3, "Подключение", colors.blue, decoration = ToolsIcons.usb) { connection() }
        ToolsStepCard(4, "Операция", colors.violet) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                state.paxModel.availableModes.forEach { mode ->
                    ToolsChoiceChip(paxModeTitle(state.paxModel, mode), state.installMode == mode, { onMode(mode) }, enabled = !busy,
                        icon = when (mode) {
                            PaxInstallMode.Full -> Icons.Outlined.PlayArrow
                            PaxInstallMode.RemoveMainApp -> Icons.Outlined.Delete
                            PaxInstallMode.Prolin -> Icons.Outlined.Settings
                            PaxInstallMode.MainApp -> Icons.Outlined.List
                        })
                }
            }
            Text(when (state.installMode) {
                PaxInstallMode.Full -> if (state.paxModel == PaxModel.S210) "Полный цикл OS + APP. После записи OS потребуется системное меню для проверки и продолжения."
                    else "Установка ОС и приложения из комплекта. Данные приложения будут удалены."
                PaxInstallMode.RemoveMainApp -> if (state.paxModel == PaxModel.S210) "Выберите OS + APP, OS или APP." else "Удаление MAINAPP и его данных. ZIP не требуется."
                PaxInstallMode.Prolin -> if (state.paxModel == PaxModel.S210) "Начните с белого экрана OS: подключите S210 с зажатой жёлтой клавишей и проверьте подключение." else "Установка ОС из выбранного комплекта."
                PaxInstallMode.MainApp -> if (state.paxModel == PaxModel.S210) "Запись APP из системного меню. Перед каждой записью APP требуется обновление OS." else "Установка MAINAPP и файлов приложения из комплекта."
            }, style = MaterialTheme.typography.bodyMedium, color = colors.muted)
            if (state.globalInstallBusy && !active) ToolsSubpanel(colors.violet) {
                FirmwareSummaryRow(Icons.Outlined.Info, "На планшете уже выполняется установка PAX.", accent = colors.violet)
            }
            if (installationMatchesModel && installation.phase !in listOf(PaxInstallPhase.Idle, PaxInstallPhase.Ready)) {
                FirmwareProgress(installation, state.paxModel)
                if (active) {
                    Text("Сохраняйте питание и USB. После перезагрузки разрешите USB-доступ повторно.",
                        style = MaterialTheme.typography.bodySmall, color = colors.muted)
                    if (installation.step == PaxFirmwareStep.Reconnect) ToolsActionButton(
                        if (state.paxModel == PaxModel.S210) "Проверить подключение" else "Терминал включён — продолжить",
                        onRetryConnection, Modifier.fillMaxWidth(), icon = Icons.Outlined.Refresh, accent = colors.blue)
                }
            }
            if (!active) {
                if (canResume) {
                    ToolsSubpanel(colors.violet) {
                        FirmwareSummaryRow(Icons.Outlined.Info, "Подключите тот же терминал. Завершённые этапы сохранены.", accent = colors.violet)
                    }
                    ToolsActionButton("Продолжить установку…",
                        { stable = false; s210Prepared = false; retryConfirmed = false; resuming = true; confirm = true },
                        Modifier.fillMaxWidth(), enabled = !busy && usb.probingKey == null, icon = Icons.Outlined.PlayArrow, primary = true)
                }
                if (!completedCurrent && (!packageReady || !connectionReady)) ToolsSubpanel(colors.blue) {
                    if (!packageReady) FirmwareSummaryRow(Icons.Outlined.Info,
                        if (state.paxModel == PaxModel.S210 && state.pinpadExpectedOs.isBlank()) "Укажите версию OS после установки."
                        else "В комплекте нет компонентов для выбранной операции.", accent = colors.blue)
                    if (!connectionReady) FirmwareSummaryRow(Icons.Outlined.Info, if (state.paxModel == PaxModel.S210 && state.installMode != PaxInstallMode.MainApp) "Подключите S210 с белым экраном и проверьте подключение." else "Подключите один терминал и подтвердите его модель.", accent = colors.blue)
                }
                ToolsActionButton(if (installation.canResume || completedCurrent) "Начать новую операцию…" else "Начать…",
                    { stable = false; s210Prepared = false; retryConfirmed = false; resuming = false; confirm = true },
                    Modifier.fillMaxWidth(), enabled = canStart, icon = Icons.Outlined.PlayArrow, primary = true)
            }
        }
    }
    if (confirm) {
        val selectedModel = if (resuming) installation.model else state.paxModel
        val selectedMode = if (resuming) installation.mode else state.installMode
        val needsS210Confirmation = selectedModel == PaxModel.S210 && (
            selectedMode == PaxInstallMode.MainApp ||
                (selectedMode in listOf(PaxInstallMode.Full, PaxInstallMode.Prolin) &&
                    (!resuming || "pinpad-os" !in installation.completedActions)))
        val needsRetryConfirmation = resuming && installation.recoveryRequired
        AlertDialog(onDismissRequest = { confirm = false }, title = { Text(if (resuming) "Продолжить запись?" else "Начать запись?") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("${selectedModel.title} · ${paxModeTitle(selectedModel, selectedMode)}", style = MaterialTheme.typography.titleMedium)
                if (resuming && selectedModel != PaxModel.S210 && installation.step in listOf(PaxFirmwareStep.Reboot, PaxFirmwareStep.Reconnect, PaxFirmwareStep.CheckingOs))
                    Text("Продолжайте после перезагрузки PAX и входа в main menu → General → XCB service → USB.", style = MaterialTheme.typography.bodyMedium)
                if (!resuming && installation.canResume) Text("При запуске новой операции журнал продолжения предыдущей будет заменён. До запуска журнал сохранится.", style = MaterialTheme.typography.bodyMedium)
                if (needsRetryConfirmation) {
                    Text("Результат последнего этапа не подтверждён. Сначала проверьте состояние терминала.", color = MaterialTheme.colorScheme.error)
                    Row {
                        Checkbox(checked = retryConfirmed, onCheckedChange = { retryConfirmed = it })
                        Text("Разрешаю повтор неподтверждённого этапа.", Modifier.padding(top = 10.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Text("Обрыв питания или USB во время записи может потребовать восстановления терминала.", style = MaterialTheme.typography.bodyMedium)
                if (needsS210Confirmation) Row {
                    Checkbox(checked = s210Prepared, onCheckedChange = { s210Prepared = it })
                    Text(if (selectedMode == PaxInstallMode.MainApp) "OS обновлена перед этой записью APP." else "Подключён именно PAX S210 (S210_SL). Проверил маркировку пинпада.", Modifier.padding(top = 10.dp), style = MaterialTheme.typography.bodyMedium)
                }
                Row {
                    Checkbox(checked = stable, onCheckedChange = { stable = it })
                    Text("Модель и операция проверены, питание и USB стабильны.", Modifier.padding(top = 10.dp), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }, confirmButton = {
            TextButton(onClick = { confirm = false; if (resuming) onResume() else onStart() },
                enabled = stable && (!needsS210Confirmation || s210Prepared) && (!needsRetryConfirmation || retryConfirmed) && !busy && (resuming || canStart)) { Text(if (resuming) "Продолжить" else "Начать запись") }
        }, dismissButton = { TextButton(onClick = { confirm = false }) { Text("Отмена") } })
    }
}

private fun paxModeTitle(model: PaxModel, mode: PaxInstallMode): String = mode.titleFor(model)

@Composable
private fun FirmwareSummaryRow(icon: ImageVector, text: String, accent: Color = ToolsColors.current.muted, emphasized: Boolean = false) {
    val colors = ToolsColors.current
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(if (emphasized) 27.dp else 21.dp), tint = accent)
        Text(text, style = if (emphasized) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodyMedium,
            fontWeight = if (emphasized) FontWeight.Bold else FontWeight.Normal,
            color = if (emphasized) accent else colors.muted)
    }
}

@Composable
private fun FirmwareProgress(installation: PaxInstallState, model: PaxModel) {
    val colors = ToolsColors.current
    val complete = installation.phase == PaxInstallPhase.Complete
    val failed = installation.phase in listOf(PaxInstallPhase.Failed, PaxInstallPhase.Interrupted)
    val progress = if (complete) 1f else if (installation.totalBytes > 0)
        (installation.bytes.toFloat() / installation.totalBytes).coerceIn(0f, 1f) else null
    val accent = if (complete) colors.success else colors.violet
    ToolsSubpanel(colors.violet) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (complete) Icons.Outlined.CheckCircle else Icons.Outlined.Settings, null, Modifier.size(24.dp), tint = accent)
            Text(if (complete) "Операция завершена" else installation.step?.titleFor(model) ?: "Операция PAX",
                Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = colors.ink)
            if (installation.stepCount > 0) Text("Этап ${if (complete) installation.stepCount else installation.stepNumber.coerceIn(0, installation.stepCount)} из ${installation.stepCount}",
                style = MaterialTheme.typography.labelMedium, color = colors.muted)
        }
        if (progress != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.weight(1f).height(9.dp).clip(RoundedCornerShape(50)),
                color = accent, trackColor = accent.copy(alpha = .14f))
            Text("${(progress * 100).toInt()}%", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = accent)
        } else if (installation.active) LinearProgressIndicator(Modifier.fillMaxWidth(), color = accent, trackColor = accent.copy(alpha = .14f))
        FirmwareSummaryRow(if (failed) Icons.Outlined.Warning else Icons.Outlined.CheckCircle,
            if (model == PaxModel.S210) installation.detail.replace("MAINAPP", "APP", ignoreCase = true) else installation.detail,
            accent = if (failed) MaterialTheme.colorScheme.error else accent)
        installation.fileName?.let { FirmwareSummaryRow(Icons.Outlined.List, it) }
        if (installation.completedActions.isNotEmpty()) FirmwareSummaryRow(Icons.Outlined.List,
            "Выполнено этапов: ${installation.completedActions.size}")
    }
}
