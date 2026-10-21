package ru.lumastack.lumawork.data.tools

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Optional private-fixture check. Never copies captures into the project or emits their payload. */
class PaxCaptureReplayTest {
    @Test fun `all four private captures reproduce expected frames messages and final software`() {
        val directory = System.getenv("PAX_CAPTURE_DIR")?.let(::File)
        assumeTrue("Set PAX_CAPTURE_DIR to run private capture replay", directory?.isDirectory == true)
        val expected = listOf(
            Expected("pax_connection.pcapng", 15_033, 7_483, 0),
            Expected("pax_termassist.pcapng", 13_125, 6_551, 0),
            Expected("pax_termassist_connected.pcapng", 10_302, 2_511, 42),
            Expected("pax_termassist_full_update.pcapng", 61_962, 21_657, 1_932),
        )
        for (capture in expected) {
            val packets = packets(File(requireNotNull(directory), capture.file).readBytes())
            assertEquals(capture.file, capture.packets, packets.size)
            val devices = packets.filter { it.transfer == 2 && it.payload.size >= 18 &&
                it.payload[0] == 18.toByte() && it.payload[1] == 1.toByte() &&
                it.payload.copyOfRange(8, 12).contentEquals(byteArrayOf(0xb8.toByte(), 0x2f, 1, 0x11)) }
                .map { it.device }.toSet()
            assertTrue(capture.file, devices.isNotEmpty())
            val groups = packets.filter { it.device in devices && it.transfer == 3 && it.payload.isNotEmpty() &&
                (it.endpoint == 1 && it.info and 1 == 0 || it.endpoint == 129 && it.info and 1 == 1) }
                .groupBy { it.device to it.endpoint }
            var frames = 0
            var messages = 0
            val finalPassports = mutableListOf<PaxPassport>()
            for ((_, group) in groups) {
                val outer = PaxFrameDecoder()
                val inner = PaxMessageDecoder()
                val responseXml = mutableMapOf<Int, ByteArrayOutputStream>()
                for (packet in group) {
                    val decoded = outer.feed(packet.payload)
                    frames += decoded.size
                    decoded.filter { it.type == if (packet.endpoint == 1) 0 else 1 }.forEach { frame ->
                        val decodedMessages = inner.feed(frame.payload)
                        messages += decodedMessages.size
                        // Only inbound devinfo XML is parsed; no source XML is logged or persisted.
                        if (packet.endpoint == 129) for (message in decodedMessages) {
                            if (message.command == "WRTE") responseXml.getOrPut(message.arg1) { ByteArrayOutputStream() }.write(message.payload)
                            if (message.command == "CLSE") {
                                val xml = responseXml.remove(message.arg1)?.toByteArray()
                                if (xml != null && xml.take(16).toByteArray().toString(Charsets.UTF_8).contains("<devinfo>"))
                                    finalPassports += parsePaxPassport(xml)
                            }
                        }
                    }
                }
            }
            assertEquals(capture.file, capture.frames, frames)
            assertEquals(capture.file, capture.messages, messages)
            if (capture.messages > 0) assertTrue(capture.file, finalPassports.isNotEmpty())
            if (capture.messages == 1_932) {
                assertEquals("D270", finalPassports.last().model)
                assertEquals("Nuxiums3.1.24", finalPassports.last().os)
                assertEquals("3.0.65.014", finalPassports.last().mainApp)
            }
        }
    }

    private data class Expected(val file: String, val packets: Int, val frames: Int, val messages: Int)
    private data class Packet(val device: Pair<Int, Int>, val endpoint: Int, val transfer: Int, val info: Int, val payload: ByteArray)
    private fun packets(bytes: ByteArray): List<Packet> {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val packets = mutableListOf<Packet>()
        var offset = 0
        while (offset < bytes.size) {
            val type = buffer.getInt(offset)
            val length = buffer.getInt(offset + 4)
            require(length >= 12 && offset + length <= bytes.size && buffer.getInt(offset + length - 4) == length)
            if (type == 1) require(buffer.getShort(offset + 8).toInt() == 249)
            if (type == 6) {
                val capturedLength = buffer.getInt(offset + 20)
                val start = offset + 28
                require(capturedLength >= 27 && start + capturedLength <= offset + length - 4)
                val headerLength = buffer.getShort(start).toInt() and 65535
                require(headerLength in 27..capturedLength)
                packets += Packet((buffer.getShort(start + 17).toInt() and 65535) to (buffer.getShort(start + 19).toInt() and 65535),
                    bytes[start + 21].toInt() and 255, bytes[start + 22].toInt() and 255, bytes[start + 16].toInt() and 255,
                    bytes.copyOfRange(start + headerLength, start + capturedLength))
            }
            offset += length
        }
        return packets
    }
}
