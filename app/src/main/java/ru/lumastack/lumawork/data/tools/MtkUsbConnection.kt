package ru.lumastack.lumawork.data.tools

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

fun isMtkPreloader(vendorId: Int, productId: Int) = vendorId == 0x0e8d && productId == 0x2000
internal data class MtkUsbCandidate(val key: String, val deviceId: Int, val epoch: Long, val permission: Boolean)
internal class MtkUsbSelection {
    fun select(candidates: List<MtkUsbCandidate>): MtkUsbCandidate? {
        if (candidates.size > 1) throw MtkProtocolException("Подключено несколько MTK-устройств. Оставьте один тестовый P8.")
        return candidates.singleOrNull()
    }
    fun canOpen(expected: MtkUsbCandidate, current: MtkUsbCandidate?) = current != null && expected.permission && current.permission &&
        expected.key == current.key && expected.deviceId == current.deviceId && expected.epoch == current.epoch
}

internal object MtkUsbConnection {
    fun open(manager: UsbManager, device: UsbDevice, stillCurrent: () -> Boolean, checkpoint: () -> Unit): MtkWire {
        require(isMtkPreloader(device.vendorId, device.productId)) { "Нужен MediaTek Preloader 0E8D:2000." }
        require(stillCurrent() && manager.hasPermission(device)) { "Устройство отключено или USB-разрешение устарело. Переподключите терминал." }
        val interfaces = (0 until device.interfaceCount).map(device::getInterface)
        val data = interfaces.singleOrNull { it.id == 0 && it.alternateSetting == 0 && it.interfaceClass == 0x0a }
            ?: throw MtkProtocolException("MTK: data interface не соответствует проверенному профилю.")
        val control = interfaces.singleOrNull { it.id == 1 && it.alternateSetting == 0 && it.interfaceClass == 2 && it.interfaceSubclass == 2 && it.interfaceProtocol == 1 }
            ?: throw MtkProtocolException("MTK: CDC control interface не соответствует профилю.")
        val endpoints = (0 until data.endpointCount).map(data::getEndpoint)
        val input = endpoints.singleOrNull { it.address == 0x81 && it.type == UsbConstants.USB_ENDPOINT_XFER_BULK && it.maxPacketSize == 512 }
            ?: throw MtkProtocolException("MTK: не найден Bulk IN 0x81/512.")
        val output = endpoints.singleOrNull { it.address == 0x01 && it.type == UsbConstants.USB_ENDPOINT_XFER_BULK && it.maxPacketSize == 512 }
            ?: throw MtkProtocolException("MTK: не найден Bulk OUT 0x01/512.")
        val connection = manager.openDevice(device) ?: throw MtkProtocolException("Не удалось открыть MTK USB.")
        var dataClaimed = false; var controlClaimed = false
        val closed = AtomicBoolean(false)
        val io = object : MtkBulkIo {
            override fun read(bytes: ByteArray, offset: Int, count: Int): Int {
                // Owner checks its attach epoch/cancellation in MtkStreamWire's checkpoint and
                // closes this connection on detach. Keep Binder enumeration out of the timed
                // Preloader exchange, especially between JUMP_DA's address echo and status.
                check(!closed.get()) { "MTK USB отключён." }
                return connection.bulkTransfer(input, bytes, offset, count, 3000)
            }
            override fun write(bytes: ByteArray, offset: Int, count: Int): Int {
                check(!closed.get()) { "MTK USB отключён." }
                return connection.bulkTransfer(output, bytes, offset, count, 3000)
            }
            override fun close() {
                if (!closed.compareAndSet(false, true)) return
                try {
                    if (controlClaimed) runCatching { connection.releaseInterface(control) }
                    if (dataClaimed) runCatching { connection.releaseInterface(data) }
                } finally { connection.close() }
            }
        }
        fun controlOut(request: Int, value: Int, bytes: ByteArray? = null) {
            val size = bytes?.size ?: 0
            if (connection.controlTransfer(0x21, request, value, 1, bytes, size, 1000) != size)
                throw MtkProtocolException("MTK: CDC-запрос 0x%02X не подтверждён.".format(request))
        }
        fun baud(value: Int) = controlOut(0x20, 0, ByteBuffer.allocate(7).order(ByteOrder.LITTLE_ENDIAN).putInt(value).put(0).put(0).put(8).array())
        try {
            // Samsung's kernel binds both Preloader interfaces to cdc_acm (observed on SM-T595).
            // Android's force flag disconnects that kernel driver; it does not bypass USB permission.
            dataClaimed = connection.claimInterface(data, true)
            if (!dataClaimed) throw MtkProtocolException("MTK: data interface занят.")
            controlClaimed = connection.claimInterface(control, true)
            if (!controlClaimed) throw MtkProtocolException("MTK: CDC control interface занят.")
            check(stillCurrent() && manager.hasPermission(device)) { "MTK изменился во время открытия USB." }
            android.util.Log.i("MtkFirmware", "USB 0E8D:2000 data=0 class=0A IN=81 OUT=01 packet=512 control=1 class=02 claimed")
            val coding = ByteArray(7)
            if (connection.controlTransfer(0xa1, 0x21, 0, 1, coding, 7, 1000) != 7)
                throw MtkProtocolException("MTK: GET_LINE_CODING не подтверждён.")
            // Captured CDC setup: 115200, RTS, DTR+RTS, then 921600 and SEND_BREAK(0).
            baud(115200); controlOut(0x22, 2); controlOut(0x22, 3)
            baud(921600); controlOut(0x23, 0); controlOut(0x22, 3)
            return MtkStreamWire(io, checkpoint)
        } catch (failure: Throwable) { io.close(); throw failure }
    }
}
