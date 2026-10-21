package ru.lumastack.lumawork.data.tools

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout

fun isPaxUsbCandidate(device: UsbEquipment): Boolean = device.vendorId == 0x2FB8 && device.productId == 0x1101 &&
    device.interfaces.any { it.id == 0 && it.alternate == 0 && it.deviceClass == 255 }

/** One owner and one close path for diagnostics and the installer. Never takes an interface forcibly. */
internal class PaxUsbConnection private constructor(
    private val connection: UsbDeviceConnection, private val iface: UsbInterface,
    input: UsbEndpoint, output: UsbEndpoint,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    val wire = PaxBulkWire(object : PaxUsbIo {
        override fun prepare() {
            val result = connection.controlTransfer(0x40, 0x01, 0x0300, 0, null, 0, 1000)
            paxRequire(result == 0, "PAX не подтвердил начальную настройку USB-транспорта.")
        }
        override fun write(bytes: ByteArray) {
            paxRequire(!closed.get() && connection.bulkTransfer(output, bytes, bytes.size, 1000) == bytes.size,
                "Ошибка передачи USB Bulk. Операция остановлена без повтора команды.")
        }
        override fun read(): ByteArray {
            val buffer = ByteArray(16_384)
            paxRequire(!closed.get(), "USB-сеанс закрыт.")
            val size = connection.bulkTransfer(input, buffer, buffer.size, 1000)
            paxRequire(size > 0, "Нет ответа USB Bulk. Проверьте кабель и XCB service → USB в main menu.")
            return buffer.copyOf(size)
        }
    })
    override fun close() {
        if (closed.compareAndSet(false, true)) try { connection.releaseInterface(iface) } finally { connection.close() }
    }
    companion object {
        fun open(manager: UsbManager, device: UsbDevice): PaxUsbConnection {
            paxRequire(device.vendorId == 0x2FB8 && device.productId == 0x1101 && manager.hasPermission(device),
                "Подключите PAX и разрешите USB-доступ.")
            val iface = (0 until device.interfaceCount).map(device::getInterface)
                .singleOrNull { it.id == 0 && it.alternateSetting == 0 && it.interfaceClass == 255 }
                ?: throw PaxDiagnosticException("Нужный интерфейс PAX отсутствует. Проверьте режим XCB USB.")
            val endpoints = (0 until iface.endpointCount).map(iface::getEndpoint)
            fun endpoint(address: Int) = endpoints.singleOrNull { it.address == address && it.type == UsbConstants.USB_ENDPOINT_XFER_BULK }
                ?: throw PaxDiagnosticException("Ожидаемые Bulk endpoints PAX отсутствуют.")
            val input = endpoint(0x81)
            val output = endpoint(0x01)
            val connection = manager.openDevice(device) ?: throw PaxDiagnosticException("Не удалось открыть USB PAX.")
            try {
                paxRequire(connection.claimInterface(iface, false), "USB-интерфейс занят или недоступен. Закройте другое приложение.")
                return PaxUsbConnection(connection, iface, input, output)
            } catch (failure: Throwable) { connection.close(); throw failure }
        }
    }
}

internal class PaxUsbDiagnosticsAdapter(private val manager: UsbManager) : UsbProtocolDiagnosticsAdapter {
    private val active = AtomicReference<PaxUsbConnection?>(null)
    override fun supports(device: UsbEquipment) = isPaxUsbCandidate(device)
    override suspend fun inspect(device: UsbDevice, onStage: (UsbProbeStage) -> Unit): UsbDiagnosticResult = withTimeout(15_000) {
        onStage(UsbProbeStage.Opening)
        val connection = PaxUsbConnection.open(manager, device)
        try {
            paxRequire(active.compareAndSet(null, connection), "Диагностика PAX уже выполняется.")
            currentCoroutineContext().ensureActive()
            onStage(UsbProbeStage.Handshake)
            val session = PaxInstallerSession(connection.wire)
            session.connect()
            onStage(UsbProbeStage.Reading)
            val passport = session.passport()
            UsbDiagnosticResult("PAX ${passport.model} ответил. ОС: ${passport.os ?: "не указана"}; MAINAPP: ${passport.mainApp ?: "не указан"}. USB-сессия закрыта.", passport)
        } finally { active.compareAndSet(connection, null); connection.close() }
    }
    override fun cancel() { active.getAndSet(null)?.close() }
}
