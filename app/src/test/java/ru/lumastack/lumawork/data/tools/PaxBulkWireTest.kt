package ru.lumastack.lumawork.data.tools

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PaxBulkWireTest {
    @Test fun `bulk wire initializes then reassembles split replies without resending requests`() = runBlocking {
        val io = TestUsbIo()
        val wire = PaxBulkWire(io)
        wire.send(PaxMessage("CNXN", 0x01000000, 4096, "host::\u0000".toByteArray()))
        assertEquals("CNXN", wire.receive().command)
        val sent = io.written.flatMap { PaxFrameDecoder().feed(it) }
        assertEquals(listOf(3, 4, 2, 0, 2, 1), sent.map { it.type })
        assertEquals(listOf(1, 2, 3, 4, 5, 6), sent.map { it.sequence })
        assertArrayEquals(byteArrayOf(33, 0), sent.last().payload)
        assertEquals(listOf(0, 1), io.preparedAtWriteCounts)
    }

    @Test fun `bulk wire rejects wrong sequence and corrupt replies without retry`() = runBlocking {
        for (mode in listOf("sequence", "checksum", "empty")) {
            val io = TestUsbIo(mode)
            assertTrue(runCatching { PaxBulkWire(io).send(PaxMessage("CNXN", 0x01000000, 4096)) }.isFailure)
            assertEquals(2, io.written.size)
        }
    }

    @Test fun `failed USB preparation sends no bulk data`() = runBlocking {
        val io = TestUsbIo("prepare_failure")
        assertTrue(runCatching { PaxBulkWire(io).send(PaxMessage("CNXN", 0x01000000, 4096)) }.isFailure)
        assertTrue(io.written.isEmpty())
    }

    @Test fun `large XCB message fragments within reported free write queue and reassembles exactly`() = runBlocking {
        val io = FragmentUsbIo()
        val wire = PaxBulkWire(io)
        val message = PaxMessage("WRTE", 1, 2, ByteArray(65_536) { (it % 251).toByte() })
        wire.send(message)
        assertArrayEquals(encodePaxMessage(message), io.fragments.fold(byteArrayOf()) { bytes, fragment -> bytes + fragment })
        assertTrue(io.fragments.size >= 4)
        assertTrue(io.fragments.all { it.size <= 16_379 })
        assertEquals(7, io.fragments.first().size)
        assertTrue(io.polls >= 2) // Zero free capacity must be polled, never written through.
    }

    @Test fun `failed fragment is never resent`() = runBlocking {
        val io = FragmentUsbIo(failAfter = 2)
        val result = runCatching { PaxBulkWire(io).send(PaxMessage("WRTE", 1, 2, ByteArray(65_536))) }
        assertTrue(result.isFailure)
        assertEquals(2, io.fragments.size)
    }

    private class FragmentUsbIo(private val failAfter: Int = Int.MAX_VALUE) : PaxUsbIo {
        val fragments = mutableListOf<ByteArray>()
        var polls = 0
        private val reads = ArrayDeque<ByteArray>()
        private var free = 7
        override fun prepare() = Unit
        override fun write(bytes: ByteArray) {
            val frame = PaxFrameDecoder().feed(bytes).single()
            val payload = when (frame.type) {
                3 -> return
                4 -> byteArrayOf(0xfb.toByte(), 0x7f)
                2 -> { polls++; if (polls > 1) free = 32_763; status(free) }
                0 -> {
                    assertTrue("Write exceeds available queue", frame.payload.size <= free)
                    fragments += frame.payload
                    if (fragments.size == failAfter) error("Disconnected")
                    free = if (fragments.size == 1) 0 else 32_763
                    status(free)
                }
                else -> error("Unexpected outer type")
            }
            reads += encodePaxFrame(frame.sequence, if (frame.type == 4) 4 else 2, payload)
        }
        override fun read() = reads.removeFirst()
        private fun status(free: Int) = java.nio.ByteBuffer.allocate(16).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .putInt(0).putInt(0).putInt(0).putInt(free).array()
    }

    private class TestUsbIo(private val mode: String = "normal") : PaxUsbIo {
        val written = mutableListOf<ByteArray>()
        val preparedAtWriteCounts = mutableListOf<Int>()
        override fun prepare() {
            if (mode == "prepare_failure") error("USB control failed")
            preparedAtWriteCounts += written.size
        }
        private val reads = ArrayDeque<ByteArray>()
        override fun write(bytes: ByteArray) {
            written += bytes
            val request = PaxFrameDecoder().feed(bytes).single()
            var payload = byteArrayOf()
            val type = when (request.type) {
                3 -> return
                4 -> { payload = byteArrayOf(0xfb.toByte(), 0x7f); 4 }
                0 -> { payload = ByteArray(16).apply { this[14] = 1 }; 2 }
                2 -> { payload = ByteArray(16).apply { this[8] = 33; this[14] = 1 }; 2 }
                1 -> { payload = hex("434e584e000000010000010009000000e4020000bcb1a7b16465766963653a3a00"); 1 }
                else -> error("unexpected request")
            }
            val response = encodePaxFrame(if (mode == "sequence") 0 else request.sequence, type, payload)
            if (mode == "checksum") response[response.lastIndex] = (response.last().toInt() xor 1).toByte()
            if (mode == "empty") reads.add(byteArrayOf())
            else {
                reads.add(response.copyOfRange(0, 2))
                reads.add(response.copyOfRange(2, response.size))
            }
        }
        override fun read(): ByteArray = reads.removeFirst()
        private fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
