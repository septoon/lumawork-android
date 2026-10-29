package ru.lumastack.lumawork.data.tools

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PaxInstallerSessionTest {
    @Test fun `negotiates max payload and reads only whitelisted passport fields`() = runBlocking {
        val wire = Device()
        val session = PaxInstallerSession(wire)
        session.connect()
        assertEquals(PaxPassport("D270", "Nuxiums3.1.24", "8.1.12.274(R)", "3.0.65.014"), session.passport())
        assertEquals(listOf("get-maxpayload:", "devinfo:"), wire.services)
        assertEquals(4096, wire.sent.first().arg1)
        assertArrayEquals(byteArrayOf(32), wire.writes["devinfo:"]!!.toByteArray())
        assertTrue(wire.acks > 4) // Fragmented DATA must be acknowledged while the host waits for its write ACK.
    }

    @Test fun `uses exact approved command grammar and final markers for each installer kind`() = runBlocking {
        val wire = Device()
        val session = PaxInstallerSession(wire)
        session.connect()
        session.removeMainApp()
        session.installBoot("/tmp/boot.zip")
        session.installOs("/tmp/os.zip")
        session.installMainApp("/tmp/app.aip")
        session.installAppData("/tmp/Config.sys")
        session.installOption("/tmp/fonts.tar.gz")
        assertEquals(listOf("uaip\u0000MAINAPP\u0000\u0000", "boot\u0000/tmp/boot.zip\u0000\u0000",
            "firmware\u0000/tmp/os.zip\u0000\u0000", "aip\u0000/tmp/app.aip\u0000\u0000",
            "appdata\u0000MAINAPP\u0000/tmp/Config.sys\u0000\u0000", "opt\u0000/tmp/fonts.tar.gz\u0000\u0000"), wire.commands)
        assertEquals(6, wire.quits)
    }

    @Test fun `Q25 Prolin completes with firmware base marker followed by OKAY`() = runBlocking {
        val wire = Device(status = "installer firmware-kernel done.\r\ninstaller firmware-ramdisk done.\r\ninstaller firmware-base done.\r\n")
        val session = PaxInstallerSession(wire)
        session.connect()
        session.installOs("/tmp/os.zip", PaxModel.Q25)
        assertEquals(1, wire.quits)
    }

    @Test fun `D230 Prolin completes only after captured firmware base marker and OKAY`() = runBlocking {
        val wire = Device(status = "installer firmware-kernel done.\r\ninstaller firmware-ramdisk done.\r\ninstaller firmware-base done.\r\n")
        val session = PaxInstallerSession(wire)
        session.connect()
        session.installOs("/tmp/prolin-albatross-2.9.54.12262R-Inpas_SIG.zip", PaxModel.D230)
        assertEquals(listOf("firmware\u0000/tmp/prolin-albatross-2.9.54.12262R-Inpas_SIG.zip\u0000\u0000"), wire.commands)
        assertEquals(1, wire.quits)
    }

    @Test fun `OS completion marker must match selected model and incomplete Prolin stops`() = runBlocking {
        for ((model, status) in listOf(
            PaxModel.D270 to "installer firmware-base done.\r\n",
            PaxModel.Q25 to "installer rootfs.img done.\r\n",
            PaxModel.Q25 to "installer firmware-kernel done.\r\ninstaller firmware-ramdisk done.\r\n",
            PaxModel.Q25 to "verify failed\r\ninstaller firmware-base done.\r\n",
            PaxModel.D230 to "installer rootfs.img done.\r\n",
            PaxModel.D230 to "installer firmware-kernel done.\r\ninstaller firmware-ramdisk done.\r\n",
            PaxModel.D230 to "verify failed\r\ninstaller firmware-base done.\r\n",
        )) {
            val wire = Device(status = status)
            val session = PaxInstallerSession(wire)
            session.connect()
            assertFailure { session.installOs("/tmp/os.zip", model) }
            assertEquals(0, wire.quits)
        }
    }

    @Test fun `upload streams complete bytes with STAT ASK SEND DATA DONE QUIT and no resume`() = runBlocking {
        val wire = Device()
        val updates = mutableListOf<Pair<Long, Long>>()
        val session = PaxInstallerSession(wire) { done, total -> updates += done to total }
        session.connect()
        val bytes = ByteArray(140_003) { (it % 251).toByte() }
        var opens = 0
        val result = session.upload(PaxTransferAsset("boot.zip", bytes.size.toLong(), 1_700_000_000) {
            opens++
            object : ByteArrayInputStream(bytes) {
                override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, minOf(37, len))
            }
        })
        assertEquals("/tmp/boot.zip", result)
        assertEquals(1, opens)
        assertArrayEquals(bytes, wire.uploaded.toByteArray())
        assertEquals(listOf("STAT", "ASK ", "SEND", "DATA", "DATA", "DATA", "DONE", "QUIT"), wire.uploadTags)
        assertEquals("size:136", wire.ask)
        assertEquals("/tmp/boot.zip,33206", wire.sendPath)
        assertEquals(1_700_000_000, wire.mtime)
        assertEquals(bytes.size.toLong() to bytes.size.toLong(), updates.last())
        assertTrue(wire.sent.filter { it.command == "WRTE" }.all { it.payload.size <= 65_536 })
    }

    @Test fun `early remote upload failure stops before DATA and DONE`() = runBlocking {
        val wire = Device(mode = "early_upload_fail")
        val session = PaxInstallerSession(wire)
        session.connect()
        assertFailure { session.upload(PaxTransferAsset("app.aip", 140_003, 1) { ByteArrayInputStream(ByteArray(140_003)) }) }
        assertEquals(listOf("STAT", "ASK ", "SEND"), wire.uploadTags)
        assertEquals(0, wire.uploaded.size())
    }

    @Test fun `large upload crosses real message and outer frame fragmentation layers`() = runBlocking {
        val peer = Device()
        val usb = DeviceUsb(peer)
        val session = PaxInstallerSession(PaxBulkWire(usb))
        session.connect()
        val bytes = ByteArray(140_003) { (it % 251).toByte() }
        assertEquals("/tmp/app.aip", session.upload(PaxTransferAsset("app.aip", bytes.size.toLong(), 1) { ByteArrayInputStream(bytes) }))
        assertArrayEquals(bytes, peer.uploaded.toByteArray())
        assertTrue(usb.writeSizes.any { it == 16_379 })
        assertTrue(usb.writeSizes.all { it <= 16_379 })
        assertEquals(1, peer.uploadTags.count { it == "DONE" })
    }

    @Test fun `does not accept transport progress or verify success as completed installation`() = runBlocking {
        for (status in listOf("verify success!\r\n", "installer firmware-kernel done.\r\n", "installer aip done.\r\nverify failed\r\n")) {
            val wire = Device(status = status)
            val session = PaxInstallerSession(wire)
            session.connect()
            assertFailure { session.installOs("/tmp/os.zip") }
            assertEquals(0, wire.quits)
        }
    }

    @Test fun `FAIL malformed sync and premature close stop operation without retry`() = runBlocking {
        for (mode in listOf("fail", "length", "truncated", "wrong_ids", "bad_okay")) {
            val wire = Device(mode = mode)
            val session = PaxInstallerSession(wire)
            session.connect()
            assertFailure { session.removeMainApp() }
            assertEquals(1, wire.commands.size)
            assertEquals(0, wire.quits)
            val traffic = wire.sent.size
            assertFailure { session.removeMainApp() }
            assertEquals(traffic, wire.sent.size)
        }
    }

    @Test fun `bad upload size timestamp and paths cause no installer traffic`() = runBlocking {
        val wire = Device()
        val session = PaxInstallerSession(wire)
        session.connect()
        for (name in listOf("../a", "a/b", "a\\b", "a\n", "a\u0000", "", ".", "..", "a,b")) {
            assertFailure { session.upload(PaxTransferAsset(name, 2, 1) { ByteArrayInputStream(byteArrayOf(1, 2)) }) }
        }
        assertFailure { session.upload(PaxTransferAsset("a", -1, 1) { error("Must not open") }) }
        assertFailure { session.upload(PaxTransferAsset("a", 1, -1) { error("Must not open") }) }
        assertFailure { session.installBoot("/tmp/../a") }
        assertFailure { session.installAppData("/elsewhere/a") }
        assertEquals(listOf("get-maxpayload:"), wire.services)
    }

    @Test fun `truncated and oversized files never send DONE or retry source`() = runBlocking {
        for (declared in listOf(2L, 4L)) {
            val wire = Device()
            val session = PaxInstallerSession(wire)
            session.connect()
            var opens = 0
            assertFailure { session.upload(PaxTransferAsset("a", declared, 1) { opens++; ByteArrayInputStream(byteArrayOf(1, 2, 3)) }) }
            assertEquals(1, opens)
            assertFalse(wire.uploadTags.contains("DONE"))
        }
    }

    @Test fun `cancellation propagates with no repeat write or automatic reboot`() = runBlocking {
        val wire = Device(mode = "cancel")
        val session = PaxInstallerSession(wire)
        session.connect()
        try { session.removeMainApp(); fail("Expected cancellation") } catch (_: CancellationException) { }
        assertEquals(1, wire.commands.size)
        assertFalse(wire.services.contains("reboot:"))
    }

    @Test fun `reboot uses empty service and invalidates session until reconnect`() = runBlocking {
        val wire = Device()
        val session = PaxInstallerSession(wire)
        session.connect()
        session.reboot()
        assertFalse(wire.writes.containsKey("reboot:"))
        assertFailure { session.removeMainApp() }
        session.connect()
        session.removeMainApp()
        assertEquals(2, wire.sent.count { it.command == "CNXN" })
    }

    @Test fun `rejects authentication and malformed max payload before installer`() = runBlocking {
        for (mode in listOf("auth", "bad_max")) {
            val wire = Device(mode = mode)
            assertFailure { PaxInstallerSession(wire).connect() }
            assertFalse(wire.services.contains("installer:"))
        }
    }

    private suspend fun assertFailure(block: suspend () -> Unit) {
        try { block(); fail("Expected protocol failure") } catch (_: PaxDiagnosticException) { }
    }

    /** Exercises the real frame decoder, message decoder and flow control without any device I/O. */
    private class DeviceUsb(private val peer: Device) : PaxUsbIo {
        val writeSizes = mutableListOf<Int>()
        private val messages = PaxMessageDecoder()
        private val replies = ArrayDeque<ByteArray>()
        private var inbound = byteArrayOf()
        override fun prepare() = Unit
        override fun write(bytes: ByteArray) {
            val frame = PaxFrameDecoder().feed(bytes).single()
            val payload = when (frame.type) {
                3 -> return
                4 -> byteArrayOf(0xfb.toByte(), 0x7f)
                0 -> {
                    writeSizes += frame.payload.size
                    runBlocking { messages.feed(frame.payload).forEach { peer.send(it) } }
                    peer.drain().forEach { inbound += encodePaxMessage(it) }
                    status()
                }
                2 -> status()
                1 -> {
                    val count = (frame.payload[0].toInt() and 255) or ((frame.payload[1].toInt() and 255) shl 8)
                    inbound.copyOfRange(0, count).also { inbound = inbound.copyOfRange(count, inbound.size) }
                }
                else -> error("Unexpected frame")
            }
            replies += encodePaxFrame(frame.sequence, if (frame.type == 0) 2 else frame.type, payload)
        }
        override fun read() = replies.removeFirst()
        private fun status() = int(0) + int(0) + int(inbound.size) + int(65_536)
    }

    /** Synthetic peer, no captured configurations, identity fields, firmware or certificates. */
    private class Device(val mode: String = "normal", val status: String? = null) : PaxMessageWire {
        val sent = mutableListOf<PaxMessage>()
        val services = mutableListOf<String>()
        val writes = mutableMapOf<String, ByteArrayOutputStream>()
        val commands = mutableListOf<String>()
        val uploaded = ByteArrayOutputStream()
        val uploadTags = mutableListOf<String>()
        var ask = ""
        var sendPath = ""
        var mtime = 0
        var quits = 0
        var acks = 0
        private val inbound = ArrayDeque<PaxMessage>()
        private var local = 0
        private var remote = 0
        private var service = ""
        private var pending = byteArrayOf()
        override suspend fun send(message: PaxMessage) {
            sent += message
            when (message.command) {
                "CNXN" -> inbound.add(PaxMessage(if (mode == "auth") "AUTH" else "CNXN", 0x01000000, 4096, "device::\u0000".toByteArray()))
                "OPEN" -> {
                    local = message.arg0; remote = local + 100; service = message.payload.toString(Charsets.UTF_8).trimEnd('\u0000')
                    services += service; pending = byteArrayOf()
                    inbound.add(PaxMessage("OKAY", remote, local))
                    when (service) {
                        "get-maxpayload:" -> { reply((if (mode == "bad_max") "x65536" else "65536").toByteArray()); close() }
                        "reboot:" -> close()
                    }
                }
                "OKAY" -> acks++
                "CLSE" -> Unit
                "WRTE" -> {
                    writes.getOrPut(service) { ByteArrayOutputStream() }.write(message.payload)
                    if (service == "devinfo:") {
                        reply("<devinfo><terminal><mach>D270</mach><os>Nuxiums3.1.24</os><boot>8.1.12.274(R)</boot></terminal><application><app id=\"MAINAPP\"><version>3.0.65.014</version></app></application></devinfo>".toByteArray())
                        inbound.add(PaxMessage("OKAY", remote, local)); close(); return
                    }
                    pending += message.payload
                    val oldQuits = quits
                    parseOutgoing()
                    inbound.add(PaxMessage("OKAY", remote, local))
                    if (quits > oldQuits) close()
                }
                else -> error("Unexpected host message")
            }
        }
        override suspend fun receive(): PaxMessage = inbound.removeFirst()
        fun drain(): List<PaxMessage> = buildList { while (inbound.isNotEmpty()) add(inbound.removeFirst()) }
        private fun close() { inbound.add(PaxMessage("CLSE", 0, local)) }
        private fun reply(bytes: ByteArray) {
            // Deliberately split inside record headers, UTF-8 text and lengths; respond before the host's OKAY.
            bytes.asList().chunked(13).forEach { part -> inbound.add(PaxMessage("WRTE", remote, local, part.toByteArray())) }
        }
        private fun parseOutgoing() {
            while (pending.size >= 8) {
                val tag = pending.copyOfRange(0, 4).toString(Charsets.US_ASCII)
                val n = le(pending, 4)
                val length = if (tag == "DONE") 0 else n
                if (pending.size < 8 + length) return
                val body = pending.copyOfRange(8, 8 + length)
                pending = pending.copyOfRange(8 + length, pending.size)
                when (tag) {
                    "CMD " -> {
                        val command = body.toString(Charsets.UTF_8); commands += command
                        if (mode == "cancel") throw CancellationException("Interrupted")
                        val action = command.substringBefore('\u0000')
                        val marker = if (action == "firmware") "rootfs.img" else action
                        val data = record("DATA", (status ?: "verify success!\r\ninstaller $marker done.\r\n").toByteArray())
                        when (mode) {
                            "fail" -> reply(record("FAIL", "verification failed".toByteArray()))
                            "length" -> reply("DATA".toByteArray() + int(Int.MAX_VALUE))
                            "truncated" -> { reply(data.copyOf(10)); close() }
                            "wrong_ids" -> inbound.add(PaxMessage("WRTE", remote + 1, local, data))
                            "bad_okay" -> { reply(data); reply(record("OKAY", byteArrayOf(1))) }
                            else -> { reply(data); reply(record("OKAY")) }
                        }
                    }
                    "STAT" -> { uploadTags += tag; reply("STAT".toByteArray() + int(0) + int(0) + int(0)) }
                    "ASK " -> { uploadTags += tag; ask = body.toString(Charsets.UTF_8); reply(record("OKAY")) }
                    "SEND" -> { uploadTags += tag; sendPath = body.toString(Charsets.UTF_8); if (mode == "early_upload_fail") reply(record("FAIL", "write rejected".toByteArray())) }
                    "DATA" -> { uploadTags += tag; uploaded.write(body) }
                    "DONE" -> { uploadTags += tag; mtime = n; reply(record("OKAY")) }
                    "QUIT" -> { quits++; if (uploadTags.isNotEmpty()) uploadTags += tag }
                    else -> error("Unexpected sync tag $tag")
                }
            }
        }
    }
    companion object {
        private fun int(value: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
        private fun le(bytes: ByteArray, offset: Int) = ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int
        private fun record(tag: String, body: ByteArray = byteArrayOf()) = tag.toByteArray() + int(body.size) + body
    }
}
