package ru.lumastack.lumawork.data.tools

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.mtp.MtpDevice
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** Diagnostic support is separate from permission to write firmware or read terminal logs. */
internal interface UsbProtocolDiagnosticsAdapter {
    fun supports(device: UsbEquipment): Boolean
    suspend fun inspect(device: UsbDevice): String
    fun cancel() {}
}
internal class CdcAcmDiagnosticsAdapter : UsbProtocolDiagnosticsAdapter {
    override fun supports(device: UsbEquipment) = device.interfaces.any { it.kind == UsbInterfaceKind.CdcAcm }
    override suspend fun inspect(device: UsbDevice) =
        "Объявлен USB Serial (CDC ACM). Кассовый COM-интерфейс не подтверждает поддержку выгрузки логов или прошивки. Сервисные команды не отправлялись."
}
internal class MtpDiagnosticsAdapter(private val manager: UsbManager) : UsbProtocolDiagnosticsAdapter {
    private val mtpSession = AtomicReference<MtpProbeSession?>(null)
    override fun supports(device: UsbEquipment) = device.interfaces.any { it.kind == UsbInterfaceKind.MtpCandidate }
    override suspend fun inspect(device: UsbDevice): String {
        val connection = manager.openDevice(device) ?: error("USB open failed")
        val session = MtpProbeSession(MtpDevice(device), connection)
        val mtp = session.device
        try {
            check(mtpSession.compareAndSet(null, session)) { "USB session already active" }
            currentCoroutineContext().ensureActive()
            check(session.open()) { "MTP open failed" }
            currentCoroutineContext().ensureActive()
            val info = requireNotNull(mtp.deviceInfo) { "No MTP device info" }
            val operations = info.operationsSupported.toSet()
            // Standard MTP opcodes: SendObjectInfo 0x100C / SendObject 0x100D. Advertised support is not a write test.
            val sendDeclared = 0x100C in operations && 0x100D in operations
            val storages = mtp.storageIds?.toList()?.mapNotNull { id -> mtp.getStorageInfo(id)?.let {
                "${it.description.ifBlank { id.toString() }}: свободно ${it.freeSpace} Б"
            } }.orEmpty()
            return buildString {
                append("MTP/PTP ответ получен. Модель из ответа: ${info.model.ifBlank { "не указана" }}.\n")
                append("Хранилища: ${storages.ifEmpty { listOf("недоступны") }.joinToString("; ")}.\n")
                append("SendObject: ${if (sendDeclared) "объявлен устройством, запись не проверена" else "не объявлен"}.\n")
                append("Доступ к Downloads, совместимость прошивки и получение логов не подтверждены. USB-сессия диагностики закрыта.")
            }
        } finally {
            mtpSession.compareAndSet(session, null)
            session.close()
        }
    }
    override fun cancel() {
        val session = mtpSession.getAndSet(null)
        if (session != null) cleanupScope.launch { runCatching { session.close() } }
    }
    companion object {
        // Native MTP cleanup must outlive a cancelled ViewModel/lifecycle scope.
        private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
private class MtpProbeSession(val device: MtpDevice, private val connection: UsbDeviceConnection) : AutoCloseable {
    private var closed = false
    @Synchronized fun open(): Boolean { check(!closed); return device.open(connection) }
    @Synchronized override fun close() {
        if (closed) return
        closed = true
        try { device.close() } finally { connection.close() }
    }
}
