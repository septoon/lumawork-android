package ru.lumastack.lumawork.data.tools

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

internal interface PaxUsbIo {
    fun prepare()
    fun write(bytes: ByteArray)
    fun read(): ByteArray
}
internal class PaxBulkWire(private val io: PaxUsbIo) : PaxMessageWire {
    private val outer = PaxFrameDecoder()
    private val inner = PaxMessageDecoder()
    private val frames = ArrayDeque<PaxFrame>()
    private val messages = ArrayDeque<PaxMessage>()
    private var sequence = 0
    private var initialized = false
    private var maxChunk = 0
    private var freeWrite = 0

    override suspend fun send(message: PaxMessage) {
        if (!initialized) {
            io.prepare()
            sendFrame(3, byteArrayOf())
            io.prepare()
            val limits = exchange(4, byteArrayOf(), 4)
            paxRequire(limits.size == 2, "PAX не подтвердил параметры USB-транспорта.")
            maxChunk = (limits[0].toInt() and 255) or ((limits[1].toInt() and 255) shl 8)
            paxRequire(maxChunk in 24..32_763, "Размер USB-пакета PAX не поддерживается.")
            // Android 8 truncates bulkTransfer at 16 KiB. Reserve five bytes for outer framing.
            maxChunk = minOf(maxChunk, 16_379)
            initialized = true
        }
        val bytes = encodePaxMessage(message)
        // Every write obeys the current free queue, including fragments of a large inner message.
        status(exchange(2, byteArrayOf(), 2))
        var offset = 0
        while (offset < bytes.size) {
            currentCoroutineContext().ensureActive()
            while (freeWrite == 0) {
                delay(16)
                status(exchange(2, byteArrayOf(), 2))
            }
            val size = minOf(maxChunk, freeWrite, bytes.size - offset)
            status(exchange(0, bytes.copyOfRange(offset, offset + size), 2))
            offset += size
        }
    }

    override suspend fun receive(): PaxMessage {
        paxRequire(initialized, "USB-транспорт PAX не открыт.")
        while (messages.isEmpty()) {
            currentCoroutineContext().ensureActive()
            val available = status(exchange(2, byteArrayOf(), 2))
            if (available == 0) { delay(16); continue }
            val requested = minOf(available, maxChunk)
            val length = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(requested.toShort()).array()
            val payload = exchange(1, length, 1)
            paxRequire(payload.size == requested, "PAX вернул неполный блок данных XCB.")
            messages.addAll(inner.feed(payload))
        }
        return messages.removeFirst()
    }

    private fun status(payload: ByteArray): Int {
        paxRequire(payload.size == 16 && payload[0] == 0.toByte(), "PAX вернул неподдерживаемый статус USB-транспорта.")
        val available = ByteBuffer.wrap(payload, 8, 4).order(ByteOrder.LITTLE_ENDIAN).int
        paxRequire(available in 0..(2 * PAX_MAX_PAYLOAD), "Некорректный размер очереди PAX.")
        freeWrite = ByteBuffer.wrap(payload, 12, 4).order(ByteOrder.LITTLE_ENDIAN).int
        paxRequire(freeWrite in 0..(2 * PAX_MAX_PAYLOAD), "Некорректный размер свободной очереди PAX.")
        return available
    }

    private suspend fun sendFrame(type: Int, payload: ByteArray) {
        currentCoroutineContext().ensureActive()
        sequence = (sequence + 1) and 15
        // Do not retry USB writes: a short/failed transfer has an unknown device-side outcome.
        io.write(encodePaxFrame(sequence, type, payload))
    }

    private suspend fun exchange(type: Int, payload: ByteArray, responseType: Int): ByteArray {
        sendFrame(type, payload)
        while (frames.isEmpty()) {
            currentCoroutineContext().ensureActive()
            val bytes = io.read()
            paxRequire(bytes.isNotEmpty(), "Нет ответа USB Bulk от PAX. Проверьте режим XCB и подключение.")
            frames.addAll(outer.feed(bytes))
        }
        val frame = frames.removeFirst()
        paxRequire(frame.sequence == sequence && frame.type == responseType, "Нарушен порядок USB-ответов PAX. Переподключите устройство.")
        return frame.payload
    }
}
