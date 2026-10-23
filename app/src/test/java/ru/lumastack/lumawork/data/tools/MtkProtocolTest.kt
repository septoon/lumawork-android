package ru.lumastack.lumawork.data.tools

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import org.junit.Assert.*
import org.junit.Test

internal class MtkFakeIo(bytes: ByteArray = byteArrayOf(), private val chunk: Int = 1) : MtkBulkIo {
    private val input = ByteArrayInputStream(bytes)
    val output = ByteArrayOutputStream()
    var closed = false
    override fun read(bytes: ByteArray, offset: Int, count: Int) = input.read(bytes, offset, minOf(chunk, count))
    override fun write(bytes: ByteArray, offset: Int, count: Int): Int = minOf(chunk, count).also { output.write(bytes, offset, it) }
    override fun close() { closed = true }
}
class MtkProtocolTest {
    @Test fun zero_length_usb_packet_after_aligned_gpt_payload_does_not_end_the_protocol_stream() {
        val payload = ByteArray(32768) { (it % 251).toByte() }
        val encoded = MtkFakeIo()
        MtkXflashCodec(MtkStreamWire(encoded)).writePayload(ByteArrayInputStream(ByteArray(4)), 4)
        var reads = 0
        val io = object : MtkBulkIo {
            override fun read(bytes: ByteArray, offset: Int, count: Int): Int {
                val next = when (reads++) {
                    0 -> payload.copyOfRange(0,16384)
                    1 -> payload.copyOfRange(16384,32768)
                    2 -> byteArrayOf() // A completed bulk transfer with a ZLP, not EOF.
                    3 -> encoded.output.toByteArray()
                    else -> error("Unexpected extra read")
                }
                next.copyInto(bytes,offset); return next.size
            }
            override fun write(bytes: ByteArray, offset: Int, count: Int): Int = error("Read retry must not resend a command")
            override fun close() {}
        }
        val wire = MtkStreamWire(io)
        assertArrayEquals(payload,wire.readExact(payload.size))
        MtkXflashCodec(wire).expectSuccess()
        assertEquals(4,reads)
    }
    @Test fun one_byte_protocol_read_keeps_the_rest_of_a_usb_packet() {
        var reads = 0
        val io = object : MtkBulkIo {
            override fun read(bytes: ByteArray, offset: Int, count: Int): Int {
                assertEquals(16384, count)
                assertEquals(0, reads++)
                "READY".toByteArray().copyInto(bytes, offset)
                return 5
            }
            override fun write(bytes: ByteArray, offset: Int, count: Int) = count
            override fun close() {}
        }
        val wire = MtkStreamWire(io)
        assertArrayEquals("R".toByteArray(), wire.readExact(1))
        assertArrayEquals("EADY".toByteArray(), wire.readExact(4))
        assertEquals(1, reads)
    }
    @Test fun header_is_little_endian_and_reads_fragmented_coalesced_frames() {
        val io = MtkFakeIo(); val codec = MtkXflashCodec(MtkStreamWire(io))
        codec.writePayload(ByteArrayInputStream(byteArrayOf(1,2,3)), 3)
        assertArrayEquals(byteArrayOf(0xef.toByte(),0xee.toByte(),0xee.toByte(),0xfe.toByte(),1,0,0,0,3,0,0,0,1,2,3), io.output.toByteArray())
        val bytes = io.output.toByteArray()
        for (chunk in listOf(1, 2, 5, 16384)) {
            val read = MtkXflashCodec(MtkStreamWire(MtkFakeIo(bytes + bytes, chunk)))
            repeat(2) { assertArrayEquals(byteArrayOf(1,2,3), read.readFrame().payload) }
        }
    }
    @Test fun streaming_payload_above_two_gigabytes_uses_bounded_buffer_and_long_counter() {
        var written = 0L; var maxCount = 0
        val io = object : MtkBulkIo {
            override fun read(bytes: ByteArray, offset: Int, count: Int) = -1
            override fun write(bytes: ByteArray, offset: Int, count: Int): Int { written += count; maxCount = maxOf(maxCount, count); return count }
            override fun close() {}
        }
        val size = 2348906824L
        val lazy = object : InputStream() {
            var remaining = size
            override fun read(): Int = error("Must use buffered reads")
            override fun read(b: ByteArray, off: Int, len: Int): Int { if (remaining == 0L) return -1; val n = minOf(remaining, len.toLong()).toInt(); remaining -= n; return n }
        }
        MtkXflashCodec(MtkStreamWire(io)).writePayload(lazy, size)
        assertEquals(size + 12, written)
        assertTrue(maxCount <= 16384)
    }
    @Test fun malformed_frames_and_eof_poison_codec_before_next_command() {
        val valid = byteArrayOf(0xef.toByte(),0xee.toByte(),0xee.toByte(),0xfe.toByte(),1,0,0,0,4,0,0,0)
        val invalid = listOf(valid.copyOf().apply { this[0] = 0 }, valid.copyOf().apply { this[11] = 0x7f }, valid.copyOf().apply { this[11] = -1 }, valid + byteArrayOf(0))
        invalid.forEach { bytes ->
            val io = MtkFakeIo(bytes); val codec = MtkXflashCodec(MtkStreamWire(io))
            assertThrows(Exception::class.java) { codec.readFrame() }
            assertThrows(IllegalStateException::class.java) { codec.writePayload(ByteArrayInputStream(byteArrayOf()), 0) }
            assertEquals(0, io.output.size())
        }
    }
    @Test fun status_requires_exact_known_success() {
        val io = MtkFakeIo(); val writer = MtkXflashCodec(MtkStreamWire(io))
        writer.writePayload(ByteArrayInputStream(byteArrayOf(1,0,0,0)), 4)
        val reader = MtkXflashCodec(MtkStreamWire(MtkFakeIo(io.output.toByteArray())))
        assertThrows(MtkProtocolException::class.java) { reader.expectSuccess() }
    }
    @Test fun usb_zero_short_error_and_eof_do_not_spin_or_report_success() {
        val io = MtkFakeIo(byteArrayOf(1))
        assertThrows(EOFException::class.java) { MtkStreamWire(io).readExact(2) }
        val broken = object : MtkBulkIo {
            override fun read(bytes: ByteArray, offset: Int, count: Int) = 0
            override fun write(bytes: ByteArray, offset: Int, count: Int) = 0
            override fun close() {}
        }
        assertThrows(MtkProtocolException::class.java) { MtkStreamWire(broken).writeAll(byteArrayOf(1), 0, 1) }
        assertThrows(MtkProtocolException::class.java) { MtkStreamWire(broken).readExact(1) }
    }
    @Test fun empty_usb_reads_remain_bounded_cancellable_and_do_not_hide_disconnect() {
        var reads = 0
        val empty = object : MtkBulkIo {
            override fun read(bytes: ByteArray, offset: Int, count: Int) = 0.also { reads++ }
            override fun write(bytes: ByteArray, offset: Int, count: Int) = error("No command retry")
            override fun close() {}
        }
        assertThrows(MtkProtocolException::class.java) { MtkStreamWire(empty).readExact(1) }
        assertEquals(9,reads)
        var checkpoints = 0
        assertThrows(kotlinx.coroutines.CancellationException::class.java) {
            MtkStreamWire(empty) { if (++checkpoints == 2) throw kotlinx.coroutines.CancellationException() }.readExact(1)
        }
        assertEquals(2,checkpoints)
        reads = 0
        val detached = object : MtkBulkIo by empty {
            override fun read(bytes: ByteArray, offset: Int, count: Int) = if (reads++ == 0) 0 else -1
        }
        assertThrows(EOFException::class.java) { MtkStreamWire(detached).readExact(1) }
        assertEquals(2,reads)
    }
    @Test fun download_and_send_da_checksums_are_distinct_and_unsigned() {
        assertEquals(511, mtkDownloadChecksum(byteArrayOf(-1,-1,1)))
        assertEquals(0x0205, mtkSendDaChecksum(byteArrayOf(1,2,4)))
    }
}
