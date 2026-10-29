package ru.lumastack.lumawork.data.tools

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Optional private-fixture check. Never copies captures into the project or emits their payload. */
class PaxCaptureReplayTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `D230 capture confirms complete cycle and matches the supplied installation files`() {
        val root = System.getenv("PAX_D230_CAPTURE_DIR")?.let(::File)
        assumeTrue("Set PAX_D230_CAPTURE_DIR to the private D230 reference folder", root?.isDirectory == true)
        val source = requireNotNull(root)
        val packets = packets(File(source, "pax_d230_full_update.pcapng").readBytes())
        assertEquals(42_325, packets.size)
        val devices = packets.filter { it.transfer == 2 && it.payload.size >= 18 &&
            it.payload.copyOfRange(0, 2).contentEquals(byteArrayOf(18, 1)) &&
            it.payload.copyOfRange(8, 12).contentEquals(byteArrayOf(0xb8.toByte(), 0x2f, 1, 0x11)) }.map { it.device }.toSet()
        assertEquals(2, devices.size) // USB re-enumerates after the captured reboot.
        val groups = packets.filter { it.device in devices && it.transfer == 3 && it.payload.isNotEmpty() &&
            (it.endpoint == 1 && it.info and 1 == 0 || it.endpoint == 129 && it.info and 1 == 1) }.groupBy { it.device to it.endpoint }
        val trace = mutableListOf<Pair<Packet, PaxMessage>>()
        var frames = 0
        for (group in groups.values) {
            val outer = PaxFrameDecoder(); val inner = PaxMessageDecoder()
            for (packet in group) {
                val decoded = outer.feed(packet.payload); frames += decoded.size
                decoded.filter { it.type == if (packet.endpoint == 1) 0 else 1 }.forEach { frame ->
                    inner.feed(frame.payload).forEach { trace += packet to it }
                }
            }
        }
        assertEquals(21_035, frames)
        assertEquals(1_532, trace.size)
        val services = mutableMapOf<Pair<Pair<Int, Int>, Int>, String>()
        val requests = mutableMapOf<Pair<Pair<Int, Int>, Int>, ByteArrayOutputStream>()
        val replies = mutableMapOf<Pair<Pair<Int, Int>, Int>, ByteArrayOutputStream>()
        val uploads = linkedMapOf<String, ByteArray>()
        val commands = mutableListOf<List<String>>()
        val passports = mutableListOf<PaxPassport>()
        var reboots = 0
        for ((packet, message) in trace.sortedBy { it.first.index }) {
            val outgoing = packet.endpoint == 1
            val key = packet.device to if (outgoing) message.arg0 else message.arg1
            if (outgoing && message.command == "OPEN") {
                services[key] = message.payload.decodeToString().trimEnd('\u0000')
                if (services[key] == "reboot:") reboots++
            }
            if (message.command == "WRTE") {
                val buffers = if (outgoing) requests else replies
                buffers.getOrPut(key) { ByteArrayOutputStream() }.write(message.payload)
            }
            if (!outgoing && message.command == "CLSE") {
                val request = requests.remove(key)?.toByteArray() ?: byteArrayOf()
                val reply = replies.remove(key)?.toByteArray() ?: byteArrayOf()
                when (services.remove(key)) {
                    "devinfo:" -> passports += parsePaxPassport(reply)
                    "installer:" -> {
                        val records = captureRecords(request)
                        records.singleOrNull { it.first == "CMD " }?.let { (_, body) ->
                            val command = body.decodeToString().split('\u0000').filter(String::isNotEmpty)
                            commands += command
                            val response = captureRecords(reply)
                            assertEquals("OKAY", response.last().first)
                            val status = response.filter { it.first == "DATA" }.joinToString("") { it.second.decodeToString() }
                            val marker = if (command.first() == "firmware") "firmware-base" else command.first()
                            assertEquals("installer $marker done.", status.lineSequence().map(String::trim).filter(String::isNotEmpty).last())
                        }
                        records.singleOrNull { it.first == "SEND" }?.let { (_, body) ->
                            val name = body.decodeToString().substringBefore(',').substringAfterLast('/')
                            uploads[name] = ByteArrayOutputStream().also { out -> records.filter { it.first == "DATA" }.forEach { out.write(it.second) } }.toByteArray()
                        }
                    }
                }
            }
        }
        val os = "prolin-albatross-2.9.54.12262R-Inpas_SIG.zip"
        val app = "TPTP_BASE_3.0.65.014_RU_D230_sign.aip"
        val data = listOf("Config.sys", "agent.mod", "cah.mod", "kdh.mod", "RootCA1-ITS_RootCA0.pem")
        val options = listOf("fonts_sig.tar.gz", "libbt-2.9.12_SIG.tar.gz")
        assertEquals(listOf(listOf("uaip", "MAINAPP"), listOf("firmware", "/tmp/$os"), listOf("uaip", "MAINAPP"),
            listOf("aip", "/tmp/$app")) + data.map { listOf("appdata", "MAINAPP", "/tmp/$it") } + options.map { listOf("opt", "/tmp/$it") }, commands)
        assertEquals(1, reboots)
        assertEquals(listOf(os, app) + data + options, uploads.keys.toList())
        (listOf(app) + data + options).forEach { name ->
            assertArrayEquals(name, File(source, "Pax_Prolin_D230/$name").readBytes(), uploads.getValue(name))
        }
        assertEquals("3.0.53.012", passports.first().mainApp)
        assertTrue(passports.any { it.mainApp == null })
        assertTrue(passports.all { it.model == "D230" && it.target == "Inpas" && it.os == "Prolin2.9.54" && it.boot == "7.0.11.232(R)" })
        assertNotNull(passports.first().fingerprint)
        assertEquals(1, passports.map { it.fingerprint }.distinct().size)
        assertEquals("3.0.65.014", passports.last().mainApp)

        // Import the original captured bytes and vendor script through the production importer.
        val archive = temporary.newFile("d230.zip")
        ZipOutputStream(archive.outputStream()).use { zip ->
            val files = uploads + ("script_VSS.list" to File(source, "Pax_Prolin_D230/script_VSS.list").readBytes())
            files.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry("Pax_Prolin_D230/$name")); zip.write(bytes); zip.closeEntry()
            }
        }
        val pkg = archive.inputStream().use { PaxArchiveCatalog.prepare(it, File(temporary.root, "prepared"), PaxModel.D230) }
        assertEquals("2.9.54-r12262", pkg.expectedOs)
        assertEquals("3.0.65.014", pkg.expectedMainApp)
        assertNull(pkg.boot)
        assertEquals(data, pkg.appData.map { it.name })
        assertEquals(options, pkg.options.map { it.name })
        assertEquals("a191c51079aa5cdd295c19ada02e1b1b3d02f639fd552e1cf56d0b8ad1c359cc", pkg.os?.sha256)
        assertEquals(pkg, PaxArchiveCatalog.read(pkg.directory, PaxModel.D230))
    }

    private fun captureRecords(bytes: ByteArray): List<Pair<String, ByteArray>> {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return buildList {
            while (buffer.hasRemaining()) {
                require(buffer.remaining() >= 8)
                val tag = ByteArray(4).also(buffer::get).decodeToString()
                val value = buffer.int
                val length = if (tag == "DONE") 0 else value
                require(length in 0..buffer.remaining())
                add(tag to ByteArray(length).also(buffer::get))
            }
        }
    }
    @Test fun `private captures reproduce expected frames messages and final software`() {
        val directory = System.getenv("PAX_CAPTURE_DIR")?.let(::File)
        assumeTrue("Set PAX_CAPTURE_DIR to run private capture replay", directory?.isDirectory == true)
        val expected = listOf(
            Expected("pax_connection.pcapng", 15_033, 7_483, 0),
            Expected("pax_termassist.pcapng", 13_125, 6_551, 0),
            Expected("pax_termassist_connected.pcapng", 10_302, 2_511, 42),
            Expected("pax_termassist_full_update.pcapng", 61_962, 21_657, 1_932),
            Expected("Прошивка Pax Q25/pax_q25_termassist_full_update.pcapng", 35_641, 17_751, 1_706, 0x1234, 0x0101),
        )
        for (capture in expected) {
            val packets = packets(File(requireNotNull(directory), capture.file).readBytes())
            assertEquals(capture.file, capture.packets, packets.size)
            val devices = packets.filter { it.transfer == 2 && it.payload.size >= 18 &&
                it.payload[0] == 18.toByte() && it.payload[1] == 1.toByte() &&
                it.payload.copyOfRange(8, 12).contentEquals(byteArrayOf(capture.vendor.toByte(), (capture.vendor shr 8).toByte(),
                    capture.product.toByte(), (capture.product shr 8).toByte())) }
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
            if (capture.messages == 1_706) {
                assertEquals("Q25", finalPassports.last().model)
                assertEquals("Prolin2.7.159", finalPassports.last().os)
                assertEquals("3.0.65.014", finalPassports.last().mainApp)
                assertEquals(1, finalPassports.map { it.fingerprint }.distinct().size)
            }
        }
    }

    private data class Expected(val file: String, val packets: Int, val frames: Int, val messages: Int,
        val vendor: Int = 0x2FB8, val product: Int = 0x1101)
    private data class Packet(val device: Pair<Int, Int>, val endpoint: Int, val transfer: Int, val info: Int, val payload: ByteArray, val index: Int)
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
                    bytes.copyOfRange(start + headerLength, start + capturedLength), packets.size)
            }
            offset += length
        }
        return packets
    }
}
