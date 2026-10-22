package ru.lumastack.lumawork.data.tools

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PaxProtocolTest {
    @Test fun `outer decoder accepts split frames and coalesced status replies`() {
        val decoder = PaxFrameDecoder()
        val fixture = hex("02040300fb7f81") + hex("030211000028000000000100000000000000010038")
        assertTrue(decoder.feed(fixture.copyOfRange(0, 3)).isEmpty())
        val frames = decoder.feed(fixture.copyOfRange(3, fixture.size))
        assertEquals(listOf(4, 2), frames.map { it.type })
        assertArrayEquals(hex("fb7f"), frames[0].payload)
        assertEquals(16, frames[1].payload.size)
    }

    @Test fun `outer decoder uses high length byte and rejects altered checksum`() {
        val payload = ByteArray(600) { 7 }
        val frame = wireFrame(9, 1, payload)
        assertArrayEquals(payload, PaxFrameDecoder().feed(frame).single().payload)
        frame[frame.lastIndex] = (frame.last().toInt() xor 1).toByte()
        assertTrue(runCatching { PaxFrameDecoder().feed(frame) }.isFailure)
        assertTrue(runCatching { PaxFrameDecoder().feed(hex("01020000")) }.isFailure)
    }

    @Test fun `inner decoder restores split CNXN and rejects forged magic or checksum`() {
        val fixture = hex("434e584e000000010000010009000000e4020000bcb1a7b16465766963653a3a00")
        for (split in 1 until fixture.size) {
            val decoder = PaxMessageDecoder()
            assertTrue(decoder.feed(fixture.copyOfRange(0, split)).isEmpty())
            assertEquals("CNXN", decoder.feed(fixture.copyOfRange(split, fixture.size)).single().command)
        }
        for (position in listOf(16, 20, 24)) {
            val bad = fixture.copyOf().apply { this[position] = (this[position].toInt() xor 1).toByte() }
            assertTrue(runCatching { PaxMessageDecoder().feed(bad) }.isFailure)
        }
        val huge = fixture.copyOf().apply { this[15] = 0x7f }
        assertTrue(runCatching { PaxMessageDecoder().feed(huge) }.isFailure)
    }

    @Test fun `passport reads only model and software and rejects external entities`() {
        val passport = parsePaxPassport("""<devinfo><terminal><mach>D270</mach><os>Nuxiums3.1.24</os><boot>8.1.12.274(R)</boot><sn>PRIVATE-SERIAL</sn></terminal><application><app id="MAINAPP"><version>3.0.65.014</version></app></application><keyinfo><secret>PRIVATE-KEY-DATA</secret></keyinfo></devinfo>""".toByteArray())
        assertEquals("D270", passport.model)
        assertEquals("Nuxiums3.1.24", passport.os)
        assertEquals("3.0.65.014", passport.mainApp)
        assertFalse(passport.toString().contains("PRIVATE"))
        assertTrue(runCatching { parsePaxPassport("""<!DOCTYPE devinfo [<!ENTITY x SYSTEM "file:///etc/passwd">]><devinfo><terminal><mach>&x;</mach></terminal></devinfo>""".toByteArray()) }.isFailure)
        assertTrue(runCatching { parsePaxPassport("<devinfo><terminal><mach>UNKNOWN</mach></terminal></devinfo>".toByteArray()) }.isFailure)
    }

    @Test fun `supported XCB models are read from passport without D270 substitution`() {
        for (model in listOf("D230", "D270", "Q25")) {
            val xml = "<devinfo><terminal><mach>$model</mach><sn>PRIVATE-SERIAL</sn><target>Inpas</target></terminal><application/></devinfo>"
            val passport = parsePaxPassport(xml.toByteArray())
            assertEquals(model, passport.model)
            assertNotNull(passport.fingerprint)
        }
        assertTrue(runCatching { parsePaxPassport("<devinfo><terminal><mach>S210</mach></terminal></devinfo>".toByteArray()) }.isFailure)
    }
    @Test fun `readonly session queries passport with space selector and acknowledges fragments`() = runBlocking {
        val wire = ScriptedPaxWire()
        wire.incoming += message("CNXN", 0x01000000, 65536, "device::\u0000")
        wire.incoming += message("OKAY", 5, 11)
        wire.incoming += message("OKAY", 5, 11)
        wire.incoming += message("WRTE", 5, 11, "<devinfo><terminal><mach>D270</mach><os>Nuxiums3.1.24</os></terminal>")
        wire.incoming += message("WRTE", 5, 11, "<application/></devinfo>")
        wire.incoming += message("CLSE", 0, 11)
        val passport = PaxReadOnlySession(wire).inspect()
        assertEquals("D270", passport.model)
        assertNull(passport.mainApp)
        assertEquals(listOf("CNXN", "OPEN", "WRTE", "OKAY", "OKAY", "CLSE"), wire.sent.map { it.command })
        assertEquals(listOf("devinfo:\u0000"), wire.sent.filter { it.command == "OPEN" }.map { it.payload.decodeToString() })
        assertArrayEquals(byteArrayOf(0x20), wire.sent.single { it.command == "WRTE" }.payload)
    }

    @Test fun `auth and wrong stream ids never produce a passport or send selector`() = runBlocking {
        for (reply in listOf(message("AUTH", 1, 0), message("CNXN", 0x01000000, 65536, "device::\u0000"))) {
            val wire = ScriptedPaxWire()
            wire.incoming += reply
            wire.incoming += message("OKAY", 5, 999)
            assertTrue(runCatching { PaxReadOnlySession(wire).inspect() }.isFailure)
            assertFalse(wire.sent.any { it.command == "WRTE" })
        }
    }

    @Test fun `silent peer times out after one connection attempt`() = runTest {
        val sent = mutableListOf<PaxMessage>()
        val wire = object : PaxMessageWire {
            override suspend fun send(message: PaxMessage) { sent += message }
            override suspend fun receive(): PaxMessage = awaitCancellation()
        }
        val failure = runCatching { PaxReadOnlySession(wire).inspect() }.exceptionOrNull()
        assertTrue(failure is TimeoutCancellationException)
        assertEquals(listOf("CNXN"), sent.map { it.command })
    }

    @Test fun `foreign response and oversized passport stop without acknowledging invalid data`() = runBlocking {
        for (reply in listOf(message("WRTE", 5, 999, "bad"), PaxMessage("WRTE", 5, 11, ByteArray(131_073)))) {
            val wire = ScriptedPaxWire()
            wire.incoming += message("CNXN", 0x01000000, 65536, "device::\u0000")
            wire.incoming += message("OKAY", 5, 11)
            wire.incoming += reply
            assertTrue(runCatching { PaxReadOnlySession(wire).inspect() }.isFailure)
            assertFalse(wire.sent.any { it.command == "OKAY" })
        }
    }

    private class ScriptedPaxWire : PaxMessageWire {
        val incoming = ArrayDeque<PaxMessage>()
        val sent = mutableListOf<PaxMessage>()
        override suspend fun send(message: PaxMessage) { sent += message }
        override suspend fun receive(): PaxMessage = incoming.removeFirst()
    }

    private fun message(command: String, local: Int, remote: Int, payload: String = "") =
        PaxMessage(command, local, remote, payload.toByteArray())

    private fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun wireFrame(sequence: Int, type: Int, payload: ByteArray): ByteArray {
        val bytes = ByteBuffer.allocate(payload.size + 5).order(ByteOrder.LITTLE_ENDIAN)
            .put(sequence.toByte()).put(type.toByte()).putShort((payload.size + 1).toShort()).put(payload).array()
        bytes[bytes.lastIndex] = bytes.dropLast(1).fold(0) { xor, byte -> xor xor (byte.toInt() and 255) }.toByte()
        return bytes
    }
}
