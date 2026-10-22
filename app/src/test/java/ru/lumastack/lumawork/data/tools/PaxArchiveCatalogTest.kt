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
