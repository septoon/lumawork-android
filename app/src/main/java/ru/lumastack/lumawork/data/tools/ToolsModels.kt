package ru.lumastack.lumawork.data.tools

import kotlinx.serialization.Serializable

const val PAX_FIRMWARE_FTP_PATH = "/VTB_TO/TO/Vendor/Verifone_Pax/Прошивки"

@Serializable data class LocalToolFile(
    val id: String, val name: String, val size: Long, val modifiedAt: Long,
    val downloadedAt: Long, val locator: String, val downloading: Boolean = false,
    val remotePath: String? = null,
)
fun LocalToolFile.isPaxFirmwareSource(): Boolean = remotePath?.startsWith("$PAX_FIRMWARE_FTP_PATH/") == true && name.isPaxArchiveName()
fun String.isPaxArchiveName(): Boolean = endsWith(".zip", true) || endsWith(".rar", true) || endsWith(".7z", true)
@Serializable data class SelectedFirmware(val file: LocalToolFile, val sha256: String, val selectedAt: Long)
@Serializable data class ToolRequestRef(val sysId: String, val number: String)
@Serializable enum class ToolsEventKind(val title: String) {
    FileSelected("Файл выбран"), HashVerified("SHA-256 рассчитан"), SelectionInvalidated("Выбор сброшен"),
    TransferBlocked("Передача заблокирована"), UsbAttached("USB подключён"), UsbDetached("Кабель отсоединён"),
    Diagnostic("Диагностика USB"), Interrupted("Операция прервана"),
    FileCopied("Файл скопирован"), SessionClosed("USB-сессия закрыта"),
    AutonomyConfirmed("Автономное продолжение подтверждено"), AutonomyUnknown("Автономное продолжение не подтверждено"),
    InstallationWritten("Запись завершена · требуется проверка"),
    InstallationConfirmed("Установка подтверждена"), InstallationUnknown("Результат установки не подтверждён"),
}
@Serializable data class ToolsEvent(
    val id: String, val time: Long, val kind: ToolsEventKind, val detail: String,
    val fileId: String? = null, val deviceKey: String? = null, val request: ToolRequestRef? = null,
)
@Serializable enum class UpdatePhase(val title: String) {
    Idle("Операция не выполняется"), Selected("Файл выбран"), Checking("Проверка файла"),
    Transferring("Передача выполняется"), Copied("Файл скопирован"), SessionClosed("USB-сессия закрыта"),
    AutonomyConfirmed("Автономное продолжение подтверждено"), SafeToDisconnect("Кабель можно отсоединить"),
    TerminalUpdating("Терминал продолжает обновление самостоятельно"),
    InstallationConfirmed("Результат установки подтверждён"), InstallationUnknown("Передача завершена, итог установки не подтверждён"),
    Interrupted("Операция прервана"),
}
/** Protocol adapters must report copy, close and installation as separate facts. Never infer installation from bytes. */
@Serializable data class UpdateOperation(
    val phase: UpdatePhase = UpdatePhase.Idle, val deviceKey: String? = null,
    val totalBytes: Long = 0, val bytesCopied: Long = 0,
    val sessionClosed: Boolean = false, val autonomousConfirmed: Boolean = false,
) {
    val canDisconnect: Boolean get() = phase == UpdatePhase.SafeToDisconnect && sessionClosed && autonomousConfirmed && totalBytes > 0 && bytesCopied == totalBytes
    val installationConfirmed: Boolean get() = phase == UpdatePhase.InstallationConfirmed
    fun progress(device: String, bytes: Long): UpdateOperation {
        require(phase == UpdatePhase.Transferring && device == deviceKey && bytes in bytesCopied..totalBytes)
        return copy(bytesCopied = bytes)
    }
    fun copied(bytes: Long): UpdateOperation {
        require(phase == UpdatePhase.Transferring && bytes == totalBytes && bytes > 0)
        return copy(phase = UpdatePhase.Copied, bytesCopied = bytes)
    }
    /** True only when a verified adapter guarantees no remaining host commands are required. */
    fun closed(autonomous: Boolean): UpdateOperation {
        require(phase == UpdatePhase.Copied && bytesCopied == totalBytes)
        return copy(phase = if (autonomous) UpdatePhase.SafeToDisconnect else UpdatePhase.InstallationUnknown,
            sessionClosed = true, autonomousConfirmed = autonomous)
    }
    fun detached(): UpdateOperation = when {
        canDisconnect -> copy(phase = UpdatePhase.TerminalUpdating)
        phase == UpdatePhase.Transferring || phase == UpdatePhase.Checking -> copy(phase = UpdatePhase.Interrupted)
        phase in listOf(UpdatePhase.Copied, UpdatePhase.SessionClosed, UpdatePhase.AutonomyConfirmed) -> copy(phase = UpdatePhase.InstallationUnknown)
        else -> this
    }
    fun confirmInstallation(device: String): UpdateOperation {
        require(device == deviceKey && phase in listOf(UpdatePhase.TerminalUpdating, UpdatePhase.InstallationUnknown, UpdatePhase.SafeToDisconnect))
        return copy(phase = UpdatePhase.InstallationConfirmed)
    }
    fun recover(): UpdateOperation = when (phase) {
        UpdatePhase.Transferring, UpdatePhase.Checking -> copy(phase = UpdatePhase.Interrupted, autonomousConfirmed = false)
        UpdatePhase.Copied, UpdatePhase.SessionClosed, UpdatePhase.AutonomyConfirmed, UpdatePhase.SafeToDisconnect, UpdatePhase.TerminalUpdating ->
            copy(phase = UpdatePhase.InstallationUnknown, autonomousConfirmed = false)
        else -> this
    }
}
@Serializable data class ToolsSnapshot(
    val selection: SelectedFirmware? = null, val history: List<ToolsEvent> = emptyList(),
    val operation: UpdateOperation = UpdateOperation(),
)

enum class UsbInterfaceKind(val title: String) {
    MtpCandidate("MTP/PTP: требуется проверка ответа устройства"), CdcAcm("USB Serial (CDC ACM): сервисные команды не подтверждены"),
    CdcData("CDC Data"), Other("Другой интерфейс: протокол не подтверждён"),
}
fun classifyUsbInterface(deviceClass: Int, subclass: Int, protocol: Int): UsbInterfaceKind = when {
    deviceClass == 6 && subclass == 1 && protocol == 1 -> UsbInterfaceKind.MtpCandidate
    deviceClass == 2 && subclass == 2 -> UsbInterfaceKind.CdcAcm
    deviceClass == 10 -> UsbInterfaceKind.CdcData
    else -> UsbInterfaceKind.Other
}
data class UsbInterfaceDetails(val id: Int, val alternate: Int, val deviceClass: Int, val subclass: Int, val protocol: Int, val endpoints: List<String>) {
    val kind get() = classifyUsbInterface(deviceClass, subclass, protocol)
}
data class UsbEquipment(
    val key: String, val vendorId: Int, val productId: Int, val manufacturer: String?, val product: String?,
    val hasPermission: Boolean, val interfaces: List<UsbInterfaceDetails>,
    // Descriptor strings alone do not prove the hardware model or the firmware compatibility.
    val confirmedModel: String? = null,
)

/** Extension point for an official SDK; an empty registry deliberately grants no terminal operations. */
interface TerminalProtocolAdapter {
    val id: String
    fun supports(device: UsbEquipment): Boolean
    suspend fun checkFirmware(device: UsbEquipment, file: SelectedFirmware): FirmwareCheck
}
data class FirmwareCheck(val canTransfer: Boolean = false, val message: String)
