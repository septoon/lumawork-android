package ru.lumastack.lumawork.ui.tools

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ru.lumastack.lumawork.data.tools.*

@Composable
internal fun ToolsDeviceWorkspace(
    usb: UsbToolsState,
    onScan: () -> Unit,
    onPermission: (String) -> Unit,
    onProbe: (String) -> Unit,
    onCancel: () -> Unit,
    onWiki: () -> Unit,
    firmwareBusy: Boolean = false,
    paxModel: PaxModel = PaxModel.D270,
) {
    val colors = ToolsColors.current
    val pinpadFamily = paxModel == PaxModel.S210
    val devices = usb.devices.filter { device ->
        if (pinpadFamily) isPaxPinpadCandidate(device)
        else isPaxUsbCandidate(device) && !isPaxPinpadCandidate(device)
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (pinpadFamily) {
            Text("OS и OS + APP: подключите S210 с зажатой жёлтой клавишей до белого экрана, затем проверьте подключение.", style = MaterialTheme.typography.bodyMedium, color = colors.muted)
            ToolsSubpanel(colors.blue) {
                ConnectionInfo("После записи OS: при перезагрузке нажимайте «Меню». Оставьте системное меню открытым — приложение проверит OS и продолжит APP.")
                ConnectionInfo("В KeyInstaller: PAX S210_SL. После записи APP до 100% нажмите Cancel на терминале.")
            }
        } else Text("На терминале: ${paxModel.xcbUsbMenuPath}. Оставайтесь в main menu.",
            style = MaterialTheme.typography.bodyMedium, color = colors.muted)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ToolsActionButton("Проверить USB", onScan, icon = ToolsIcons.usb, accent = colors.blue)
            ToolsActionButton("Инструкция в Вики", onWiki, icon = ToolsIcons.book, accent = colors.blue)
        }
        usb.error?.let { message -> ToolsSubpanel(colors.blue) { ConnectionInfo(if (pinpadFamily) message.replace("MAINAPP", "APP", ignoreCase = true) else message, error = true) } }
        if (devices.isEmpty()) ToolsSubpanel(colors.blue) {
            ConnectionInfo(if (usb.hostSupported) "Подключите ${paxModel.title} кабелем передачи данных через USB Host / OTG."
                else "Это Android-устройство не поддерживает USB Host.")
        }
        if (usb.devices.count { isPaxUsbCandidate(it) } > 1) ToolsSubpanel(colors.blue) {
            ConnectionInfo("Оставьте подключённым один терминал PAX.", error = true)
        }
        devices.forEach { device ->
            DeviceCard(device, usb, onPermission, onProbe, onCancel, firmwareBusy, paxModel)
        }
    }
}

@Composable
private fun DeviceCard(device: UsbEquipment, usb: UsbToolsState, onPermission: (String) -> Unit,
    onProbe: (String) -> Unit, onCancel: () -> Unit, firmwareBusy: Boolean, model: PaxModel,
) {
    val colors = ToolsColors.current
    val pinpadFamily = model == PaxModel.S210
    val passport = usb.passports[device.key].takeUnless { pinpadFamily }
    val pinpad = usb.pinpads[device.key].takeIf { pinpadFamily }
    val osBoot = pinpadFamily && device.key in usb.pinpadOsBoot
    val probing = usb.probingKey == device.key
    val failure = usb.failures[device.key]
    var details by rememberSaveable(device.key) { mutableStateOf(false) }
    ToolsSubpanel(colors.blue) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(ToolsIcons.terminal, null, Modifier.size(24.dp), tint = colors.blue)
            Text(passport?.let { "PAX ${it.model}" } ?: pinpad?.let { "PAX ${it.model}" } ?: "PAX · USB",
                style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = colors.ink)
        }
        Text(when {
            probing -> if (pinpadFamily) "Определение режима S210…" else usb.probeStage?.title ?: "Диагностика…"
            !device.hasPermission -> "Требуется разрешение Android"
            passport != null || pinpad != null -> "Данные терминала прочитаны"
            osBoot -> "Белый экран · загрузчик OS готов"
            failure != null -> "Ответ устройства не подтверждён"
            else -> "USB обнаружен · прочитайте данные терминала"
        }, style = MaterialTheme.typography.bodyMedium,
            color = if (failure != null && !probing) MaterialTheme.colorScheme.error else colors.blue)
        if (passport != null) SelectionContainer {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f)) { PassportField("ОС", passport.os ?: "Не указана") }
                Column(Modifier.weight(1f)) { PassportField("MAINAPP", passport.mainApp ?: "Не указан") }
            }
        }
        if (pinpad != null) SelectionContainer {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f)) { PassportField("OS", pinpad.os ?: "Не указана") }
                Column(Modifier.weight(1f)) { PassportField("APP", pinpad.application ?: "Не указано") }
            }
        }
        if (probing) {
            LinearProgressIndicator(Modifier.fillMaxWidth(), color = colors.blue)
            ToolsActionButton("Остановить диагностику", onCancel, icon = Icons.Outlined.Close, accent = colors.blue)
        } else {
            failure?.let { Text(if (pinpadFamily) it.replace("MAINAPP", "APP", ignoreCase = true) else it,
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            if (passport == null && pinpad == null) usb.diagnostics[device.key]?.let {
                Text(if (pinpadFamily) it.replace("MAINAPP", "APP", ignoreCase = true) else it,
                    style = MaterialTheme.typography.bodySmall, color = colors.muted)
            }
            if (!device.hasPermission) ToolsActionButton(
                if (usb.permissionPending == device.key) "Ожидание разрешения…" else "Разрешить USB-доступ",
                { onPermission(device.key) }, enabled = usb.permissionPending == null, icon = Icons.Outlined.Lock, accent = colors.blue)
            else ToolsActionButton(if (pinpadFamily) "Проверить подключение" else if (passport == null) "Прочитать данные" else "Прочитать повторно",
                { onProbe(device.key) }, enabled = usb.probingKey == null && !firmwareBusy, icon = Icons.Outlined.Refresh, accent = colors.blue)
        }
        TextButton(onClick = { details = !details }) {
            Icon(if (details) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown, null, Modifier.size(18.dp), tint = colors.blue)
            Spacer(Modifier.width(6.dp))
            Text(if (details) "Скрыть технические детали" else "Технические детали", color = colors.blue)
        }
        if (details) SelectionContainer {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                passport?.boot?.let { Text("Загрузчик: $it", style = MaterialTheme.typography.bodySmall, color = colors.muted) }
                Text("VID:PID %04X:%04X".format(device.vendorId, device.productId), style = MaterialTheme.typography.bodySmall, color = colors.muted)
                device.manufacturer?.let { Text("Производитель: $it", style = MaterialTheme.typography.bodySmall, color = colors.muted) }
                device.product?.let { Text("USB product: $it", style = MaterialTheme.typography.bodySmall, color = colors.muted) }
                Text("Дескрипторы USB не подтверждают модель терминала.", style = MaterialTheme.typography.bodySmall, color = colors.muted)
                device.interfaces.forEach { iface ->
                    HorizontalDivider(color = colors.border)
                    Text("Интерфейс ${iface.id}, alt ${iface.alternate} · ${iface.deviceClass}/${iface.subclass}/${iface.protocol}", style = MaterialTheme.typography.bodySmall, color = colors.muted)
                    iface.endpoints.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.muted) }
                }
            }
        }
    }
}

@Composable
private fun ConnectionInfo(text: String, error: Boolean = false) {
    val colors = ToolsColors.current
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (error) Icons.Outlined.Warning else Icons.Outlined.Info, null, Modifier.size(24.dp),
            tint = if (error) MaterialTheme.colorScheme.error else colors.blue)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = if (error) MaterialTheme.colorScheme.error else colors.muted)
    }
}

@Composable
private fun PassportField(label: String, value: String) {
    val colors = ToolsColors.current
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = colors.blue)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = colors.ink)
    }
}
