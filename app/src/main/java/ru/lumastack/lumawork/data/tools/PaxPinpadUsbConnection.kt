package ru.lumastack.lumawork.data.tools

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout

fun isPaxPinpadCandidate(device: UsbEquipment) = device.vendorId == 0x2FB8 && device.productId == 0x3101 &&
    device.interfaces.any { it.id == 1 && it.alternate == 0 && it.deviceClass == 10 }

/** CDC topology and 115200/8N1/RTS taken from the supplied S210 capture, not XCB. */
internal class PaxPinpadUsbConnection private constructor(
    private val connection: UsbDeviceConnection, private val control: UsbInterface, private val data: UsbInterface,
) : PaxPinpadWire, AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val input = (0 until data.endpointCount).map(data::getEndpoint).single { it.address == 0x82 && it.type == UsbConstants.USB_ENDPOINT_XFER_BULK }
    private val output = (0 until data.endpointCount).map(data::getEndpoint).single { it.address == 0x01 && it.type == UsbConstants.USB_ENDPOINT_XFER_BULK }
    override suspend fun write(bytes: ByteArray) {
        var offset = 0
        while (offset < bytes.size) {
            currentCoroutineContext().ensureActive()
            if (closed.get()) throw PaxConnectionLostException("USB S210 закрыт. Подключите пинпад повторно.")
            val count = connection.bulkTransfer(output, bytes, offset, bytes.size - offset, 5_000)
            if (count <= 0) throw PaxConnectionLostException("Нет подтверждения USB S210. Продолжите после повторного подключения.")
            offset += count
        }
    }
    override suspend fun read(): ByteArray {
        currentCoroutineContext().ensureActive()
        if (closed.get()) throw PaxConnectionLostException("USB S210 закрыт.")
        val buffer = ByteArray(16_384)
        val size = connection.bulkTransfer(input, buffer, buffer.size, 5_000)
        if (size <= 0) throw PaxConnectionLostException("S210 не ответил. Проверьте режим загрузчика и USB-подключение.")
        return buffer.copyOf(size)
    }
    override fun close() {
        if (closed.compareAndSet(false, true)) try {
            connection.releaseInterface(data); connection.releaseInterface(control)
        } finally { connection.close() }
    }
    companion object {
        fun open(manager: UsbManager, device: UsbDevice): PaxPinpadUsbConnection {
            paxRequire(device.vendorId == 0x2FB8 && device.productId == 0x3101 && manager.hasPermission(device), "Подключите S210 и разрешите USB-доступ.")
            val interfaces = (0 until device.interfaceCount).map(device::getInterface)
            val control = interfaces.singleOrNull { it.id == 0 && it.alternateSetting == 0 && it.interfaceClass == 2 && it.interfaceSubclass == 2 }
                ?: throw PaxDiagnosticException("Нет ожидаемого CDC-интерфейса S210.")
            val data = interfaces.singleOrNull { it.id == 1 && it.alternateSetting == 0 && it.interfaceClass == 10 }
                ?: throw PaxDiagnosticException("Нет ожидаемого CDC Data S210.")
            val endpoints = (0 until data.endpointCount).map(data::getEndpoint)
            paxRequire(listOf(0x82, 0x01).all { address -> endpoints.count { it.address == address && it.type == UsbConstants.USB_ENDPOINT_XFER_BULK } == 1 }, "Неверные endpoints S210.")
            val connection = manager.openDevice(device) ?: throw PaxDiagnosticException("Не удалось открыть USB S210.")
            try {
                // Android may bind cdc_acm; force detaches that kernel driver, not another app.
                paxRequire(connection.claimInterface(control, true), "Не удалось занять CDC S210. Закройте USB-приложения и переподключите пинпад.")
                paxRequire(connection.claimInterface(data, true), "Не удалось занять CDC Data S210. Закройте USB-приложения и переподключите пинпад.")
                paxRequire(connection.setInterface(data), "S210 не подтвердил выбор CDC Data.")
                val coding = byteArrayOf(0, 0xc2.toByte(), 1, 0, 0, 0, 8)
                paxRequire(connection.controlTransfer(0x21, 0x20, 0, 0, coding, coding.size, 1000) == coding.size &&
                    connection.controlTransfer(0x21, 0x22, 2, 0, null, 0, 1000) == 0, "S210 не подтвердил настройку CDC.")
                return PaxPinpadUsbConnection(connection, control, data)
            } catch (failure: Throwable) {
                runCatching { connection.releaseInterface(data) }; runCatching { connection.releaseInterface(control) }; connection.close(); throw failure
            }
        }
    }
}
internal class PaxPinpadDiagnosticsAdapter(private val manager: UsbManager) : UsbProtocolDiagnosticsAdapter {
    private val active = AtomicReference<PaxPinpadUsbConnection?>(null)
    override fun supports(device: UsbEquipment) = isPaxPinpadCandidate(device)
    override suspend fun inspect(device: UsbDevice, onStage: (UsbProbeStage) -> Unit): UsbDiagnosticResult = withTimeout(25_000) {
        try {
            onStage(UsbProbeStage.Handshake)
            val info = inspectPaxPinpad {
                val connection = PaxPinpadUsbConnection.open(manager, device)
                val previous = active.getAndSet(connection)
                previous?.close()
                connection
            }
            if (info == null) UsbDiagnosticResult(
                "Загрузчик OS ответил. Можно начать OS или OS + APP с белого экрана. Модель S210 подтвердите по маркировке пинпада; данные будут прочитаны после записи OS.",
                pinpadOsBoot = true)
            else UsbDiagnosticResult("S210 · ОС ${info.os ?: "не указана"} · ${info.application ?: "приложение не определено"}", pinpad = info)
        } catch (_: PaxConnectionLostException) {
            throw PaxDiagnosticException("S210 не ответил. Для OS подключите USB с зажатой жёлтой клавишей до белого экрана; для APP откройте системное меню. Повторите проверку подключения.")
        } finally { active.getAndSet(null)?.close() }
    }
    override fun cancel() { active.getAndSet(null)?.close() }
}
