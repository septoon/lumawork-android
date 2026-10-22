package ru.lumastack.lumawork.data.tools

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PaxPinpadSessionTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `white loader detection sends only D handshake and never asks for passport`() = runBlocking {
        val peer = Peer()
        var opens = 0
        assertNull(inspectPaxPinpad { opens++; peer })
        assertEquals(1, opens)
        assertEquals(listOf(0x44, 0x44), peer.handshakes)
        assertTrue(peer.commands.isEmpty())
        assertTrue(peer.closed)
    }

    @Test fun `menu fallback uses fresh USB session and reads info without writing firmware`() = runBlocking {
        val boot = Peer(fault = "menu_only")
        val menu = Peer()
        val peers = ArrayDeque(listOf(boot, menu))
        assertEquals("S210", inspectPaxPinpad { peers.removeFirst() }?.model)
        assertEquals(listOf(0x44), boot.handshakes)
        assertEquals(listOf(0x51, 0x51), menu.handshakes)
        assertEquals(listOf(0xa1), menu.commands)
        assertTrue(boot.closed && menu.closed)
    }

    @Test fun `read only inspection sends no clock synchronization or firmware command`() = runBlocking {
        val peer = Peer(fragment = 1)
        val session = PaxPinpadSession(peer)
        session.connect(PaxPinpadMode.SYSTEM_MENU)
        assertEquals("S210", session.readInfo().model)
        assertEquals(listOf(0xa1), peer.commands)
        assertEquals(listOf(0x51, 0x51), peer.handshakes)
        assertEquals(1, peer.hostAcks)
        session.close()
        assertTrue(peer.closed)
    }

    @Test fun `OS update streams 4K blocks and waits for flash completion before reboot`() = runBlocking {
        val data = ByteArray(9000) { (it * 37).toByte() }
        val peer = Peer(fragment = 3)
        val phases = mutableListOf<Triple<PaxPinpadStage, Long, Long>>()
        val session = PaxPinpadSession(peer)
        session.connect(PaxPinpadMode.OS_BOOT)
        val result = session.installOs(asset(data)) { stage, done, total -> phases += Triple(stage, done, total) }
        assertEquals(PaxPinpadInstallResult.OsRebootRequested, result)
        assertEquals(listOf(0xe2, 0xe3, 0xe4, 0xe4, 0xe4, 0xe5, 0xe3, 0xe6, 0xff), peer.commands)
        assertArrayEquals(data, peer.uploaded.toByteArray())
        assertEquals(listOf(4096, 4096, 808), peer.blockSizes)
        assertEquals(listOf(1, 2, 3), peer.sequences)
        assertEquals(listOf(0x44, 0x44, 0x44, 0x44), peer.handshakes)
        assertEquals(Triple(PaxPinpadStage.DownloadingOs, 9000L, 9000L), phases.last { it.first == PaxPinpadStage.DownloadingOs })
        assertTrue(phases.contains(Triple(PaxPinpadStage.WritingOs, 100L, 100L)))
        assertEquals(0, peer.pendingAcks)
        failure { session.installOs(asset(data)) }
    }

    @Test fun `APP requires fresh info and completes only after writing stage`() = runBlocking {
        val data = ByteArray(4097) { it.toByte() }
        val peer = Peer(fragment = 65_536)
        val phases = mutableListOf<Pair<PaxPinpadStage, Long>>()
        val session = PaxPinpadSession(peer)
        session.connect(PaxPinpadMode.SYSTEM_MENU)
        session.readInfo()
        assertEquals(PaxPinpadInstallResult.AppWritingCompleted,
            session.installApp(asset(data)) { stage, done, _ -> phases += stage to done })
        assertEquals(listOf(0xa1, 0xa4, 0xa5, 0xa6, 0xa6, 0xa7, 0xa5, 0xa8), peer.commands)
        assertArrayEquals(data, peer.uploaded.toByteArray())
        assertEquals(PaxPinpadStage.WritingApp to 100L, phases.last())
        assertFalse(peer.commands.contains(0xff))
        assertEquals(0, peer.pendingAcks)
        failure { session.readInfo() }
    }

    @Test fun `rejects missing info wrong app wrong mode and bad hash before destructive command`() = runBlocking {
        for (scenario in listOf("missing", "wrong_app", "wrong_mode", "hash")) {
            val peer = Peer(fault = if (scenario == "wrong_app") "wrong_app" else "")
            val session = PaxPinpadSession(peer)
            session.connect(if (scenario == "wrong_mode") PaxPinpadMode.OS_BOOT else PaxPinpadMode.SYSTEM_MENU)
            if (scenario in listOf("wrong_app", "hash")) session.readInfo()
            val file = asset(ByteArray(8))
            failure { session.installApp(if (scenario == "hash") file.copy(sha256 = "0".repeat(64)) else file) }
            assertFalse(peer.commands.any { it == 0xa4 || it == 0xa5 || it == 0xe2 || it == 0xe3 })
            failure { session.connect(PaxPinpadMode.SYSTEM_MENU) }
        }
    }

    @Test fun `unexpected negotiation progress checksum ACK or sequence poisons session without finalize retry`() = runBlocking {
        for (fault in listOf("negotiation", "regress", "percent", "checksum", "ack", "sequence", "disconnect", "typed_disconnect")) {
            val peer = Peer(fault = fault)
            val session = PaxPinpadSession(peer)
            session.connect(PaxPinpadMode.OS_BOOT)
            failure { session.installOs(asset(ByteArray(5000))) }
            assertFalse(peer.commands.contains(0xe5))
            assertFalse(peer.commands.contains(0xff))
            val commands = peer.commands.toList()
            failure { session.installOs(asset(ByteArray(5000))) }
            assertEquals(commands, peer.commands)
            assertTrue(peer.commands.count { it == 0xe3 } <= 1)
        }
    }

    @Test fun `only typed disconnect during final reboot is tolerated without replay`() = runBlocking {
        val peer = Peer(fault = "ff_disconnect")
        val session = PaxPinpadSession(peer)
        session.connect(PaxPinpadMode.OS_BOOT)
        assertEquals(PaxPinpadInstallResult.OsRebootRequested, session.installOs(asset(ByteArray(8))))
        assertEquals(1, peer.commands.count { it == 0xff })
        val commands = peer.commands.toList()
        failure { session.installOs(asset(ByteArray(8))) }
        assertEquals(commands, peer.commands)
        for (fault in listOf("ff_checksum", "ff_status")) {
            val badPeer = Peer(fault = fault)
            val badSession = PaxPinpadSession(badPeer)
            badSession.connect(PaxPinpadMode.OS_BOOT)
            failure { badSession.installOs(asset(ByteArray(8))) }
            assertEquals(1, badPeer.commands.count { it == 0xff })
        }
    }

    @Test fun `cancellation during write poisons session and sends no finalize`() = runBlocking {
        val peer = Peer(fault = "cancel")
        val session = PaxPinpadSession(peer)
        session.connect(PaxPinpadMode.OS_BOOT)
        try { session.installOs(asset(ByteArray(5000))); fail("Expected cancellation") } catch (_: CancellationException) { }
        assertFalse(peer.commands.contains(0xe5))
        failure { session.connect(PaxPinpadMode.OS_BOOT) }
    }

    @Test fun `changed source is detected before last block and never finalized`() = runBlocking {
        val file = asset(ByteArray(5000))
        val peer = Peer()
        val session = PaxPinpadSession(peer)
        session.connect(PaxPinpadMode.OS_BOOT)
        failure {
            session.installOs(file) { stage, done, _ ->
                if (stage == PaxPinpadStage.ErasingOs && done == 100L) file.file.writeBytes(ByteArray(5000) { 1 })
            }
        }
        assertEquals(1, peer.commands.count { it == 0xe4 })
        assertFalse(peer.commands.contains(0xe5))
    }

    private fun asset(data: ByteArray): PaxFirmwareAsset {
        val file = temporary.newFile().apply { writeBytes(data) }
        val digest = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it.toInt() and 255) }
        return PaxFirmwareAsset("synthetic.bin", file, digest)
    }

    private suspend fun failure(block: suspend () -> Unit) {
        try { block(); fail("Expected protocol failure") } catch (_: PaxDiagnosticException) { }
    }

    /** Synthetic peer enforces host ACK flow, chunk sequence and mutation ordering; no captured identity/data. */
    private class Peer(val fragment: Int = 17, val fault: String = "") : PaxPinpadWire {
        val commands = mutableListOf<Int>()
        val handshakes = mutableListOf<Int>()
        val blockSizes = mutableListOf<Int>()
        val sequences = mutableListOf<Int>()
        val uploaded = ByteArrayOutputStream()
        var pendingAcks = 0
        var hostAcks = 0
        var closed = false
        private var incoming = byteArrayOf()
        override suspend fun write(bytes: ByteArray) {
            check(!closed)
            if (bytes.size == 1) {
                when (val byte = bytes[0].toInt() and 255) {
                    0x44, 0x51 -> { assertEquals(0, pendingAcks); handshakes += byte; if (fault == "menu_only" && byte == 0x44) throw PaxConnectionLostException("No boot reply"); incoming += (if (byte == 0x44) 0x58 else 0x5a).toByte() }
                    6 -> { assertTrue(pendingAcks > 0); pendingAcks--; hostAcks++ }
                    else -> error("Unexpected host control")
                }
                return
            }
            assertEquals(0, pendingAcks)
            assertEquals(2, bytes[0].toInt())
            var length = 0
            for (i in 2..5) length = (length shl 8) or (bytes[i].toInt() and 255)
            assertEquals(length + 7, bytes.size)
            val checksum: Int = bytes.drop(1).fold(0) { xor, byte -> xor xor (byte.toInt() and 255) }
            assertEquals(0, checksum)
            val command = bytes[1].toInt() and 255
            commands += command
            val body = bytes.copyOfRange(6, bytes.lastIndex)
            if (fault == "ff_disconnect" && command == 0xff) throw PaxConnectionLostException("Synthetic reboot disconnect")
            if (fault == "typed_disconnect" && command == 0xe4) throw PaxConnectionLostException("Synthetic transfer disconnect")
            if (fault == "cancel" && command == 0xe4) throw CancellationException("Synthetic interruption")
            if (fault == "disconnect" && command == 0xe4) throw PaxDiagnosticException("Synthetic disconnect")
            if (fault != "ack" || command != 0xe4) incoming += 6.toByte()
            when (command) {
                0xa1 -> response(command, byteArrayOf(0) + ("""{"terminal":{"mach":"S210","os":"1.0.08.syntheticR"},"application":[{"appname":"${if (fault == "wrong_app") "OTHER" else "PAX_PINPAD"}"}]}""").toByteArray() + byteArrayOf(0))
                0xe2, 0xa4 -> {
                    assertEquals(5, body.size); assertEquals(2, body[0].toInt())
                    response(command, byteArrayOf(if (fault == "negotiation") 1 else 0, 2, 0, 0, 0x10, 0, 0x13, 0x88.toByte()))
                }
                0xe3, 0xa5 -> {
                    assertEquals(1, body.size)
                    if (fault == "regress") { response(command, byteArrayOf(0, 50)); response(command, byteArrayOf(0, 40)) }
                    else response(command, byteArrayOf(0, if (fault == "percent") 101 else 100.toByte()))
                }
                0xe4, 0xa6 -> {
                    val seq = ((body[0].toInt() and 255) shl 8) or (body[1].toInt() and 255)
                    assertEquals(sequences.size + 1, seq)
                    sequences += seq; blockSizes += body.size - 2
                    assertTrue(body.size - 2 in 1..4096)
                    uploaded.write(body, 2, body.size - 2)
                    response(command, byteArrayOf(0, body[0], if (fault == "sequence") 99 else body[1]), corrupt = fault == "checksum")
                }
                0xe5, 0xa7 -> { assertTrue(body.isEmpty()); response(command, byteArrayOf(0)) }
                0xe6, 0xa8 -> { assertTrue(body.isEmpty()); response(command, byteArrayOf(0, 0)); response(command, byteArrayOf(0, 100)) }
                0xff -> { assertArrayEquals(byteArrayOf(0), body); response(command, byteArrayOf(if (fault == "ff_status") 1 else 0), corrupt = fault == "ff_checksum") }
                else -> error("Unexpected host command")
            }
        }
        override suspend fun read(): ByteArray {
            if (incoming.isEmpty()) throw PaxDiagnosticException("Synthetic empty stream")
            val size = minOf(fragment, incoming.size)
            return incoming.copyOfRange(0, size).also { incoming = incoming.copyOfRange(size, incoming.size) }
        }
        override fun close() { closed = true }
        private fun response(command: Int, payload: ByteArray, corrupt: Boolean = false) {
            val frame = encodePaxPinpadFrame(command, payload)
            if (corrupt) frame[frame.lastIndex] = (frame.last().toInt() xor 1).toByte()
            incoming += frame
            pendingAcks++
        }
    }
}
