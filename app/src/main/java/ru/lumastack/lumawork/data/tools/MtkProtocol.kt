package ru.lumastack.lumawork.data.tools

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class MtkProtocolException(message: String) : java.io.IOException(message)
interface MtkWire : AutoCloseable {
    fun readExact(size: Int): ByteArray
    fun writeAll(bytes: ByteArray, offset: Int = 0, count: Int = bytes.size - offset)
}
internal interface MtkBulkIo : AutoCloseable {
    fun read(bytes: ByteArray, offset: Int, count: Int): Int
    fun write(bytes: ByteArray, offset: Int, count: Int): Int
}
/** USB packet boundaries never define Preloader or XFlash message boundaries. */
internal class MtkStreamWire(private val io: MtkBulkIo, private val checkpoint: () -> Unit = {}) : MtkWire {
    private val input = ByteArray(16384)
    private var inputOffset = 0
    private var inputSize = 0
    override fun readExact(size: Int): ByteArray {
        require(size in 0..MTK_MAX_FRAME)
        val bytes = ByteArray(size)
        var offset = 0
        var emptyReads = 0
        while (offset < size) {
            checkpoint()
            if (inputOffset == inputSize) {
                val n = io.read(input, 0, input.size)
                if (n < 0) throw EOFException("MTK: USB отключён или время ответа истекло.")
                // A successful bulk transfer may contain a zero-length packet, especially after
                // an aligned readback. Consume it without resending protocol commands or ACKs.
                if (n == 0) {
                    if (++emptyReads > 8) throw MtkProtocolException("MTK: слишком много пустых USB-пакетов.")
                    continue
                }
                if (n > input.size) throw MtkProtocolException("MTK: неполный USB-ответ.")
                emptyReads = 0
                inputOffset = 0; inputSize = n
            }
            val n = minOf(size - offset, inputSize - inputOffset)
            input.copyInto(bytes, offset, inputOffset, inputOffset + n)
            inputOffset += n
            offset += n
        }
        return bytes
    }
    override fun writeAll(bytes: ByteArray, offset: Int, count: Int) {
        require(offset >= 0 && count >= 0 && offset <= bytes.size - count)
        var sent = 0
        while (sent < count) {
            checkpoint()
            val chunk = minOf(16384, count - sent)
            val n = io.write(bytes, offset + sent, chunk)
            if (n <= 0 || n > chunk) throw MtkProtocolException("MTK: USB-передача прервана. Команда автоматически не повторяется.")
            sent += n
        }
    }
    override fun close() = io.close()
}
internal const val MTK_MAX_FRAME = 2 * 1024 * 1024
private const val MTK_MAGIC = 0xFEEEEEEF.toInt()
data class MtkFrame(val type: Int, val payload: ByteArray)
class MtkXflashCodec(private val wire: MtkWire) {
    private var failed = false
    fun readFrame(): MtkFrame = guarded {
        val header = ByteBuffer.wrap(wire.readExact(12)).order(ByteOrder.LITTLE_ENDIAN)
        if (header.int != MTK_MAGIC) throw MtkProtocolException("MTK: неверный XFlash magic.")
        val type = header.int
        val size = header.int.toLong() and 0xffffffffL
        if (type != 1 || size > MTK_MAX_FRAME) throw MtkProtocolException("MTK: неподдерживаемый служебный XFlash-кадр.")
        MtkFrame(type, wire.readExact(size.toInt()))
    }
    /** Streaming readback payload. A four-byte zero status returns zero without writing data. */
    fun readPayload(output: OutputStream, maxSize: Long): Long = guarded {
        require(maxSize in 0..0xffffffffL)
        val header = ByteBuffer.wrap(wire.readExact(12)).order(ByteOrder.LITTLE_ENDIAN)
        if (header.int != MTK_MAGIC || header.int != 1) throw MtkProtocolException("MTK: неверный XFlash-заголовок.")
        val size = header.int.toLong() and 0xffffffffL
        if (size > maxSize) throw MtkProtocolException("MTK: размер readback превышает профиль.")
        if (size <= 4) {
            val short = wire.readExact(size.toInt())
            // READ_DATA may emit status0 before/between payload frames. It is neither
            // backup data nor completion; the caller still requires the full byte count.
            if (short.size == 4 && ByteBuffer.wrap(short).order(ByteOrder.LITTLE_ENDIAN).int == 0) return@guarded 0L
            val detail = if (short.size == 4) "статус 0x%08X".format(ByteBuffer.wrap(short).order(ByteOrder.LITTLE_ENDIAN).int)
                else "длина $size"
            throw MtkProtocolException("MTK: преждевременный кадр readback ($detail). Получение данных остановлено.")
        }
        var remaining = size
        while (remaining > 0) {
            val bytes = wire.readExact(minOf(65536L, remaining).toInt())
            output.write(bytes); remaining -= bytes.size
        }
        size
    }
    fun writePayload(input: InputStream, size: Long) = guarded {
        require(size in 0..0xffffffffL)
        wire.writeAll(ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putInt(MTK_MAGIC).putInt(1).putInt(size.toInt()).array())
        val buffer = ByteArray(65536)
        var remaining = size
        while (remaining > 0) {
            val n = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (n <= 0) throw EOFException("MTK: исходный файл закончился до объявленной длины.")
            wire.writeAll(buffer, 0, n); remaining -= n
        }
    }
    fun expectSuccess() = guarded {
        val frame = readFrame()
        if (frame.payload.size != 4) throw MtkProtocolException("MTK: неподдерживаемый ответ статуса.")
        val status = ByteBuffer.wrap(frame.payload).order(ByteOrder.LITTLE_ENDIAN).int
        if (status != 0) throw MtkProtocolException("MTK: неподдерживаемый статус 0x%08X. Сеанс остановлен.".format(status))
    }
    internal fun expectWord(expected: Int) = guarded {
        val frame = readFrame()
        if (frame.payload.size != 4 || ByteBuffer.wrap(frame.payload).order(ByteOrder.LITTLE_ENDIAN).int != expected)
            throw MtkProtocolException("MTK: агент не подтвердил XFlash SYNC. Сеанс остановлен.")
    }
    private inline fun <T> guarded(block: () -> T): T {
        check(!failed) { "XFlash-сеанс закрыт после ошибки. Повтор команды запрещён." }
        try { return block() } catch (failure: Throwable) { failed = true; throw failure }
    }
}
fun mtkDownloadChecksum(bytes: ByteArray): Int = bytes.fold(0) { sum, byte -> (sum + (byte.toInt() and 255)) and 0xffff }
/** SEND_DA uses XOR of little-endian 16-bit words, including an odd final byte. */
fun mtkSendDaChecksum(bytes: ByteArray): Int {
    var checksum = 0
    var offset = 0
    while (offset < bytes.size) {
        val low = bytes[offset].toInt() and 255
        val high = if (offset + 1 < bytes.size) (bytes[offset + 1].toInt() and 255) shl 8 else 0
        checksum = checksum xor (low or high); offset += 2
    }
    return checksum
}
