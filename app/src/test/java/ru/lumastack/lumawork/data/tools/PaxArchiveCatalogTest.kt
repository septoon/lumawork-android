package ru.lumastack.lumawork.data.tools

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PaxArchiveCatalogTest {
    @Test fun `single disk ZIP64 POS Loader wrapper validates its directory`() {
        val os = zip(mapOf("RELEASE.TXT" to "Product : OS\nVersion : 2.7.181-r13278\nModel : PAXQ25\n".toByteArray()))
        val source = zip64(zip(mapOf("OS_RemoteUpdatePkg_1.2.2.0.zip" to zip(mapOf("q25-os.zip" to os)),
            "POS Loader 4.1.zip" to byteArrayOf(1))))
        val archive = File(temporary.root, "zip64.zip").apply { writeBytes(source) }
        assertEquals(PaxArchiveCatalog.ZipMetadata(encrypted = false, zip64 = true), PaxArchiveCatalog.validateCentralDirectory(archive))
    }

    @Test fun `ZIP64 still rejects multiple disks invalid offsets excessive sizes and truncated extra fields`() {
        val valid = zip64(zip(files()))
        val end = valid.size - 22
        val locator = end - 20
        val record = locator - 56
        fun rejected(change: (java.nio.ByteBuffer) -> Unit) {
            val bytes = valid.copyOf()
            change(java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN))
            val archive = File(temporary.root, "invalid-${System.nanoTime()}.zip").apply { writeBytes(bytes) }
            assertTrue(runCatching { PaxArchiveCatalog.validateCentralDirectory(archive) }.isFailure)
        }
        rejected { it.putInt(locator + 16, 2) }
        rejected { it.putInt(record + 16, 1) }
        rejected { it.putLong(locator + 8, Long.MAX_VALUE) }
        rejected { it.putLong(record + 40, Long.MAX_VALUE) }
        rejected { it.putLong(record + 32, 10001) }
        val central = java.nio.ByteBuffer.wrap(valid).order(java.nio.ByteOrder.LITTLE_ENDIAN).getLong(record + 48).toInt()
        rejected { it.putInt(central + 46 + it.getShort(central + 28).toInt() + 2, 0) }
        rejected { it.putInt(central + 34, 1) }
    }

    // Same ZIP64 layout as the real FTP POS Loader archive, even for small payloads.
    private fun zip64(source: ByteArray): ByteArray {
        val little = java.nio.ByteOrder.LITTLE_ENDIAN
        val original = java.nio.ByteBuffer.wrap(source).order(little)
        val end = source.size - 22
        val count = original.getShort(end + 10).toInt() and 65535
        val start = original.getInt(end + 16)
        val output = ByteArrayOutputStream().apply { write(source, 0, start) }
        var cursor = start
        repeat(count) {
            val nameSize = original.getShort(cursor + 28).toInt() and 65535
            val extraSize = original.getShort(cursor + 30).toInt() and 65535
            val commentSize = original.getShort(cursor + 32).toInt() and 65535
            val header = source.copyOfRange(cursor, cursor + 46)
            java.nio.ByteBuffer.wrap(header).order(little).putInt(20, -1).putInt(24, -1).putInt(42, -1).putShort(34, -1).putShort(30, (extraSize + 32).toShort())
            output.write(header); output.write(source, cursor + 46, nameSize)
            output.write(java.nio.ByteBuffer.allocate(32).order(little).putShort(1).putShort(28)
                .putLong(original.getInt(cursor + 24).toLong() and 0xffffffffL)
                .putLong(original.getInt(cursor + 20).toLong() and 0xffffffffL)
                .putLong(original.getInt(cursor + 42).toLong() and 0xffffffffL).putInt(0).array())
            output.write(source, cursor + 46 + nameSize, extraSize + commentSize)
            cursor += 46 + nameSize + extraSize + commentSize
        }
        val record = output.size()
        output.write(java.nio.ByteBuffer.allocate(56).order(little).putInt(0x06064b50).putLong(44).putShort(45).putShort(45)
            .putInt(0).putInt(0).putLong(count.toLong()).putLong(count.toLong()).putLong((record - start).toLong()).putLong(start.toLong()).array())
        output.write(java.nio.ByteBuffer.allocate(20).order(little).putInt(0x07064b50).putInt(0).putLong(record.toLong()).putInt(1).array())
        val footer = source.copyOfRange(end, source.size)
        java.nio.ByteBuffer.wrap(footer).order(little).putShort(4, -1).putShort(6, -1).putInt(12, -1).putInt(16, -1)
        output.write(footer)
        return output.toByteArray()
    }

    @Test fun `POS Loader archive selects remote OS package and only compatible OS and boot`() {
        val remote = zip(mapOf(
            "os-d270.zip" to zip(mapOf("RELEASE.TXT" to "Product : OS\nVersion : 3.1.45-r13454\nModel : PAXD270_PAXQ25S\n".toByteArray())),
            "boot.zip" to zip(mapOf("RELEASE.TXT" to "Product : BOOT\nVersion : 8.1.12.274\nModel : PAXD270\n".toByteArray())),
            "os-q25.zip" to zip(mapOf("RELEASE.TXT" to "Product : OS\nVersion : 2.7.181-r13278\nModel : PAXQ25\n".toByteArray())),
        ))
        val source = zip(mapOf("POS Loader и OS/OS_RemoteUpdatePkg_1.2.2.0.zip" to remote, "POS Loader и OS/POS Loader 4.1.zip" to zip(mapOf("setup.exe" to byteArrayOf(1)))))
        val result = PaxArchiveCatalog.prepareProlin(ByteArrayInputStream(source), File(temporary.root, "prolin"), PaxModel.D270)
        assertEquals("3.1.45-r13454", result.expectedOs)
        assertEquals("8.1.12.274", result.expectedBoot)
        assertNull(result.application)
        assertFalse(File(result.directory, "os-q25.zip").exists())
        assertEquals(result, PaxArchiveCatalog.read(result.directory, PaxModel.D270))
    }

    @Test fun `separate Prolin and firmware produce self contained model specific installation bundle`() {
        val firmware = prepare(files("Pax_Prolin_TTF"), PaxModel.Q25)
        val prolinBytes = zip(mapOf("q25-os.zip" to zip(mapOf("RELEASE.TXT" to "Product : OS\nVersion : 2.7.181-r13278\nModel : PAXQ25\n".toByteArray()))))
        val prolin = PaxArchiveCatalog.prepareProlin(ByteArrayInputStream(prolinBytes), File(temporary.root, "os"), PaxModel.Q25)
        val result = PaxArchiveCatalog.combine(firmware, prolin, File(temporary.root, "combined"), PaxModel.Q25)
        assertEquals("9.8.7.123", result.expectedMainApp)
        assertEquals("2.7.181-r13278", result.expectedOs)
        assertEquals(2, result.appData.size)
        firmware.directory.deleteRecursively(); prolin.directory.deleteRecursively()
        assertEquals(result, PaxArchiveCatalog.read(result.directory, PaxModel.Q25))
        assertTrue(runCatching { PaxArchiveCatalog.combine(result, null, File(temporary.root, "wrong"), PaxModel.D270) }.isFailure)
    }

    @Test fun `ambiguous OS versions and S210 Prolin are rejected without a partial import`() {
        val os = zip(mapOf("RELEASE.TXT" to "Product : OS\nVersion : 1.2.3\nModel : PAXQ25\n".toByteArray()))
        val source = zip(mapOf("one.zip" to os, "two.zip" to os))
        val destination = File(temporary.root, "ambiguous-os")
        assertTrue(runCatching { PaxArchiveCatalog.prepareProlin(ByteArrayInputStream(source), destination, PaxModel.Q25) }.isFailure)
        assertFalse(destination.exists())
        assertTrue(runCatching { PaxArchiveCatalog.prepareProlin(ByteArrayInputStream(source), destination, PaxModel.S210) }.isFailure)
    }

    @Test fun `FTP folder wrapper is unpacked to find the model inside one nested archive`() {
        val result = prepare(mapOf("FTP folder/base.zip" to zip(files()), "FTP folder/readme.txt" to "notes".toByteArray()))
        assertEquals(PaxModel.D230, result.model)
        assertEquals("9.8.7.123", result.expectedMainApp)
    }

    @Test fun `ambiguous nested firmware archives are not selected arbitrarily`() {
        val result = runCatching { prepare(mapOf("one.zip" to zip(files()), "two.zip" to zip(files()))) }
        assertTrue(result.isFailure)
    }

    @Test fun `Windows ZIP Unicode Path names are read before nested model discovery`() {
        val charset = java.nio.charset.Charset.forName("CP866")
        val bytes = ByteArrayOutputStream().also { output ->
            ZipOutputStream(output, charset).use { zip ->
                val name = "Прошивки ЦР/полный комплект.zip"
                val raw = name.toByteArray(charset)
                val utf8 = name.toByteArray(Charsets.UTF_8)
                val crc = java.util.zip.CRC32().apply { update(raw) }.value
                val extra = java.nio.ByteBuffer.allocate(9 + utf8.size).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    .putShort(0x7075).putShort((5 + utf8.size).toShort()).put(1.toByte()).putInt(crc.toInt()).put(utf8).array()
                zip.putNextEntry(ZipEntry(name).apply { this.extra = extra })
                zip.write(zip(files())); zip.closeEntry()
            }
        }.toByteArray()
        val result = PaxArchiveCatalog.prepare(ByteArrayInputStream(bytes), File(temporary.root, "windows"), PaxModel.D230)
        assertEquals("9.8.7.123", result.expectedMainApp)
    }

    @get:Rule val temporary = TemporaryFolder()

    private fun zip(files: Map<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { zip -> files.forEach { (path, bytes) ->
            zip.putNextEntry(ZipEntry(path)); zip.write(bytes); zip.closeEntry()
        } }
    }.toByteArray()
    private fun app(version: String = "9.8.7.123") = zip(mapOf("appinfo" to "[app]\nid=MAINAPP\nartwork=\nversion=$version\n".toByteArray(), "UNIPOS" to byteArrayOf(1, 2, 3)))
    private fun files(folder: String = "Pax_Prolin_D230", prefix: String = "wrapper/base/") = linkedMapOf(
        "$prefix$folder/script_VSS.list" to "[app-remove]\napp1=MAINAPP\n[app0]\npackage=changed-name.aip\n[app1]\nid=MAINAPP\nappdata2=second.mod\nappdata1=first.mod\n[ipkg-install]\nfile2=two.tar.gz\nfile1=one.tar.gz\n".toByteArray(),
        "$prefix$folder/changed-name.aip" to app(),
        "$prefix$folder/second.mod" to byteArrayOf(2),
        "$prefix$folder/first.mod" to byteArrayOf(1),
        "$prefix$folder/two.tar.gz" to byteArrayOf(4),
        "$prefix$folder/one.tar.gz" to byteArrayOf(3),
    )
    private fun prepare(files: Map<String, ByteArray>, model: PaxModel = PaxModel.D230) =
        PaxArchiveCatalog.prepare(ByteArrayInputStream(zip(files)), File(temporary.root, "import-${System.nanoTime()}"), model)

    @Test fun `selected model owns all files and script order survives renamed version`() {
        val source = files().apply { putAll(files("Pax_Prolin_Nuxiums")); put("tools/setup.exe", byteArrayOf(99)) }
        val result = prepare(source)
        assertEquals("9.8.7.123", result.expectedMainApp)
        assertEquals(listOf("second.mod", "first.mod"), result.appData.map { it.name })
        assertEquals(listOf("two.tar.gz", "one.tar.gz"), result.options.map { it.name })
        assertNull(result.os); assertNull(result.boot)
        assertFalse(result.hasProlin)
        assertEquals(result, PaxArchiveCatalog.read(result.directory, PaxModel.D230))
        assertFalse(File(result.directory, "tools/setup.exe").exists())
    }

    @Test fun `missing selected payload never falls back to another model`() {
        val source = files().apply { remove("wrapper/base/Pax_Prolin_D230/changed-name.aip"); putAll(files("Pax_Prolin_Nuxiums")) }
        assertTrue(runCatching { prepare(source) }.isFailure)
    }

    @Test fun `mainapp only is valid and unproven versions stay unknown`() {
        val result = prepare(mapOf("Pax_Prolin_TTF/script_VSS.list" to "[app0]\npackage=next.aip\n".toByteArray(), "Pax_Prolin_TTF/next.aip" to zip(mapOf("binary" to byteArrayOf(1)))), PaxModel.Q25)
        assertNotNull(result.application); assertNull(result.expectedMainApp); assertNull(result.expectedOs)
        assertTrue(result.appData.isEmpty()); assertTrue(result.options.isEmpty())
    }

    @Test fun `OS and boot come only from compatible release metadata inside model folder`() {
        val source = files().apply {
            put("wrapper/base/Pax_Prolin_D230/os.zip", zip(mapOf("RELEASE.TXT" to "Product : OS\nVersion : 7.8\nModel : PAXD230\n".toByteArray())))
            put("wrapper/base/Pax_Prolin_D230/boot.zip", zip(mapOf("RELEASE.TXT" to "Product : BOOT\nVersion : 1.2\nModel : PAXD230\n".toByteArray())))
            put("wrapper/base/Pax_Prolin_Nuxiums/other-os.zip", zip(mapOf("RELEASE.TXT" to "Product : OS\nVersion : 3.1\nModel : PAXD270\n".toByteArray())))
        }
        val result = prepare(source)
        assertEquals("os.zip", result.os?.name); assertEquals("boot.zip", result.boot?.name)
        assertEquals("7.8", result.expectedOs); assertEquals("1.2", result.expectedBoot)
        source["wrapper/base/Pax_Prolin_D230/os.zip"] = zip(mapOf("RELEASE.TXT" to "Product : OS\nVersion : 7.8\nModel : PAXD270\n".toByteArray()))
        assertTrue(runCatching { prepare(source) }.isFailure)
    }

    @Test fun `ambiguous model directories and duplicate stages are rejected`() {
        val source = files().apply { putAll(files(prefix = "another/")) }
        assertTrue(runCatching { prepare(source) }.isFailure)
        val duplicates = files().apply {
            val os = zip(mapOf("RELEASE.TXT" to "Product : OS\nVersion : 1\nModel : PAXD230\n".toByteArray()))
            put("wrapper/base/Pax_Prolin_D230/a.zip", os); put("wrapper/base/Pax_Prolin_D230/b.zip", os)
        }
        assertTrue(runCatching { prepare(duplicates) }.isFailure)
    }

    @Test fun `unknown script commands and escaping references are rejected`() {
        for (script in listOf("[shell]\nrun=cmd.exe\n", "[app0]\npackage=../Pax_Prolin_Nuxiums/changed-name.aip\n", "[app0]\npackage=changed-name.aip\n[app1]\nid=OTHER\n")) {
            assertTrue(runCatching { prepare(files().apply { put("wrapper/base/Pax_Prolin_D230/script_VSS.list", script.toByteArray()) }) }.isFailure)
        }
    }

    @Test fun `read revalidates hash model and rejects symlink replacement`() {
        val result = prepare(files())
        assertTrue(runCatching { PaxArchiveCatalog.read(result.directory, PaxModel.D270) }.isFailure)
        result.application!!.file.writeBytes(byteArrayOf(0))
        assertTrue(runCatching { PaxArchiveCatalog.read(result.directory, PaxModel.D230) }.isFailure)
        val second = prepare(files())
        val file = second.application!!.file
        file.delete(); Files.createSymbolicLink(file.toPath(), temporary.newFile().toPath())
        assertTrue(runCatching { PaxArchiveCatalog.read(second.directory, PaxModel.D230) }.isFailure)
    }

    @Test fun `malicious ZIP paths collisions symlinks and limits leave no partial import`() {
        for (path in listOf("../escape", "/escape", "a\\b", "A", "a/child")) {
            val source = files().apply { put("a", byteArrayOf(1)); put(path, byteArrayOf(2)) }
            assertTrue(path, runCatching { prepare(source) }.isFailure)
        }
        val bytes = zip(files())
        val offset = (0..bytes.size - 46).first { bytes[it] == 0x50.toByte() && bytes[it + 1] == 0x4b.toByte() && bytes[it + 2] == 1.toByte() && bytes[it + 3] == 2.toByte() }
        bytes[offset + 40] = 0xff.toByte(); bytes[offset + 41] = 0xa1.toByte()
        val destination = File(temporary.root, "symlink")
        assertTrue(runCatching { PaxArchiveCatalog.prepare(ByteArrayInputStream(bytes), destination, PaxModel.D230) }.isFailure)
        assertFalse(destination.exists())
    }

    @Test fun `existing destination remains intact on rejected import`() {
        val destination = temporary.newFolder("existing")
        File(destination, "keep").writeText("keep")
        assertTrue(runCatching { PaxArchiveCatalog.prepare(ByteArrayInputStream(zip(files())), destination, PaxModel.D230) }.isFailure)
        assertEquals("keep", File(destination, "keep").readText())
    }

    @Test fun `signed AIP trailer is preserved while appinfo is read without extraction`() {
        val signed = app("4.5.6") + ByteArray(269) + "SIGNED_VER:00001".toByteArray()
        val result = prepare(files().apply { put("wrapper/base/Pax_Prolin_D230/changed-name.aip", signed) })
        assertEquals("4.5.6", result.expectedMainApp)
        assertArrayEquals(signed, result.application!!.file.readBytes())
    }

    @Test fun `S210 supports confirmed APP and OS in either supplied source folder`() {
        for (folder in listOf("soft PP pax s200", "soft PP pax s210")) {
            val result = prepare(mapOf("wrap/$folder/APP/RU_INPAS_S210_V_2.APP" to byteArrayOf(1), "wrap/$folder/OS/runthos-S210.BIN" to byteArrayOf(2)), PaxModel.S210)
            assertEquals("RU_INPAS_S210_V_2.APP", result.application?.name)
            assertEquals("runthos-S210.BIN", result.os?.name)
            assertNull(result.expectedMainApp); assertNull(result.expectedOs)
        }
    }

    @Test fun `S210 ignores flat S200 but rejects two confirmed S210 folders`() {
        val source = linkedMapOf("wrap/soft PP pax s200/S200V5.APP" to byteArrayOf(0), "wrap/soft PP pax s210/APP/S210.APP" to byteArrayOf(1))
        assertEquals("S210.APP", prepare(source, PaxModel.S210).application?.name)
        source["wrap/soft PP pax s200/APP/S210.APP"] = byteArrayOf(2)
        assertTrue(runCatching { prepare(source, PaxModel.S210) }.isFailure)
    }

    @Test fun `oversized declared entry is rejected before extraction`() {
        val bytes = zip(files())
        val offset = (0..bytes.size - 46).first { bytes[it] == 0x50.toByte() && bytes[it + 1] == 0x4b.toByte() && bytes[it + 2] == 1.toByte() && bytes[it + 3] == 2.toByte() }
        // A 512 MiB uncompressed length, without allocating the advertised payload.
        bytes[offset + 24] = 0; bytes[offset + 25] = 0; bytes[offset + 26] = 0; bytes[offset + 27] = 0x20
        val destination = File(temporary.root, "oversized")
        assertTrue(runCatching { PaxArchiveCatalog.prepare(ByteArrayInputStream(bytes), destination, PaxModel.D230) }.isFailure)
        assertFalse(destination.exists())
    }

    @Test fun `S210 never substitutes S200 components`() {
        val source = mapOf("wrap/soft PP pax s200/s200FW.ini" to "[pinpad]\nVersion=1.01.34\nAPP=S200.APP\nFirmware=S200.MON\n".toByteArray(), "wrap/soft PP pax s200/S200.APP" to byteArrayOf(1), "wrap/soft PP pax s200/S200.MON" to byteArrayOf(2))
        assertTrue(runCatching { prepare(source, PaxModel.S210) }.isFailure)
    }
}
