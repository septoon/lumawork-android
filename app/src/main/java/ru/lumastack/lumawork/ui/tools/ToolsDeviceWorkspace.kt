package ru.lumastack.lumawork.ui.tools

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
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
    onFirmware: () -> Unit = {},
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text("Подключение", style = MaterialTheme.typography.headlineSmall)
                Text(if (usb.devices.isEmpty()) "Устройство не подключено" else "Обнаружено по USB: ${usb.devices.size}",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = onScan) { Text("Проверить USB") }
        }
        usb.error?.let { message -> ToolCard("Проверьте подключение") { Text(message, color = MaterialTheme.colorScheme.error) } }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val devicePane: @Composable (Modifier) -> Unit = { modifier ->
                Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (usb.devices.isEmpty()) ToolCard(if (usb.hostSupported) "Подключите терминал" else "USB Host недоступен") {
                        Text(if (usb.hostSupported) "Используйте кабель передачи данных и режим USB Host на телефоне или планшете."
                            else "Это Android-устройство не объявляет поддержку прямого USB-подключения.")
                        if (usb.hostSupported) Text("Если терминал только заряжается, проверьте кабель или OTG-переходник.",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    usb.devices.forEach { device ->
                        DeviceCard(device, usb, onPermission, onProbe, onCancel, firmwareBusy)
                    }
                }
            }
            val helpPane: @Composable (Modifier) -> Unit = { modifier ->
                Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ToolCard("PAX D270") {
                        Text("Перед чтением паспорта")
                        Text("1. Откройте main menu терминала.\n2. Выберите General → XCB service → USB.\n3. Оставайтесь в main menu.",
                            style = MaterialTheme.typography.bodyMedium)
                        Text("Чтение паспорта — экспериментальная функция. Доступность на вашем устройстве определяется по ответу терминала.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = onWiki) { Text("Инструкции в Вики") }
                    }
                    ToolCard("Сервисные операции") {
                        Text("Диагностика подключения, чтение паспорта и полный цикл прошивки PAX D270.", style = MaterialTheme.typography.bodyMedium)
                        Text("Подготовка комплекта и запуск находятся во вкладке «Файлы». Результаты сохраняются в «Истории».",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = onFirmware) { Text(if (firmwareBusy) "Ход установки" else "Подготовить прошивку") }
                    }
                }
            }
            if (maxWidth >= 720.dp) Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                devicePane(Modifier.weight(1.3f))
                helpPane(Modifier.weight(1f))
            } else Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                devicePane(Modifier.fillMaxWidth())
                helpPane(Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun DeviceCard(device: UsbEquipment, usb: UsbToolsState, onPermission: (String) -> Unit,
    onProbe: (String) -> Unit, onCancel: () -> Unit, firmwareBusy: Boolean,
) {
    val passport = usb.passports[device.key]
    val probing = usb.probingKey == device.key
    val failure = usb.failures[device.key]
    val candidate = isPaxUsbCandidate(device)
    var details by rememberSaveable(device.key) { mutableStateOf(false) }
    ToolCard(passport?.let { "PAX ${it.model}" } ?: if (candidate) "PAX · USB" else "USB-устройство") {
        Text(when {
            probing -> usb.probeStage?.title ?: "Диагностика…"
            !device.hasPermission -> "Требуется разрешение Android"
            passport != null -> "Паспорт прочитан · сеанс закрыт"
            failure != null -> "Ответ устройства не подтверждён"
            else -> "USB обнаружен · протокол не проверен"
        }, style = MaterialTheme.typography.labelLarge,
            color = if (failure != null && !probing) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
        if (passport != null) {
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            SelectionContainer { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PassportField("Операционная система", passport.os ?: "Не указана")
                PassportField("MAINAPP", passport.mainApp ?: "Не указан в ответе")
                PassportField("Загрузчик", passport.boot ?: "Не указан")
            } }
        }
        if (probing) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            TextButton(onClick = onCancel) { Text("Остановить диагностику") }
        } else {
            failure?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            if (passport == null) usb.diagnostics[device.key]?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            if (!device.hasPermission) Button(onClick = { onPermission(device.key) }, enabled = usb.permissionPending == null) {
                Text(if (usb.permissionPending == device.key) "Ожидание разрешения…" else "Разрешить USB-доступ")
            } else Button(onClick = { onProbe(device.key) }, enabled = usb.probingKey == null && !firmwareBusy) {
                Text(if (candidate) if (passport == null) "Прочитать паспорт" else "Прочитать повторно" else "Проверить устройство")
            }
        }
        TextButton(onClick = { details = !details }) { Text(if (details) "Скрыть технические детали" else "Технические детали") }
        if (details) SelectionContainer { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("VID:PID %04X:%04X".format(device.vendorId, device.productId), style = MaterialTheme.typography.bodySmall)
            device.manufacturer?.let { Text("Производитель: $it", style = MaterialTheme.typography.bodySmall) }
            device.product?.let { Text("USB product: $it", style = MaterialTheme.typography.bodySmall) }
            Text("Дескрипторы USB не подтверждают модель терминала.", style = MaterialTheme.typography.bodySmall)
            device.interfaces.forEach { iface ->
                HorizontalDivider()
                Text("Интерфейс ${iface.id}, alt ${iface.alternate} · ${iface.deviceClass}/${iface.subclass}/${iface.protocol}", style = MaterialTheme.typography.bodySmall)
                iface.endpoints.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        } }
    }
}

@Composable private fun PassportField(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium)
    }
}
