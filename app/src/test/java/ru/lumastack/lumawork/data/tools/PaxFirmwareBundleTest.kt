package ru.lumastack.lumawork.data.tools

import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.util.zip.CRC32
import java.nio.file.Files

class PaxFirmwareBundleTest {
    @get:Rule val temporary = TemporaryFolder()
    private val names = listOf("boot-8.1.12.274R_20240729_SIG.zip", "Nuxiums-std-3.1.24.12234R-Inpas_SIG.zip", "TPTP_BASE_3.0.65.014_RU_NUX_sign.aip", "Config.sys", "agent.mod", "cah.mod", "kdh.mod", "RootCA1-ITS_RootCA0.pem", "fonts_sig.tar.gz", "libbt-2.9.12_SIG.tar.gz")
    private val script = """[app-remove]
app1=MAINAPP
[app0]
package=${names[2]}
[app1]
id=MAINAPP
appdata1=Config.sys
appdata2=agent.mod
appdata3=cah.mod
appdata4=kdh.mod
appdata5=RootCA1-ITS_RootCA0.pem
[ipkg-install]
file1=fonts_sig.tar.gz
file2=libbt-2.9.12_SIG.tar.gz
"""
    private fun files(): LinkedHashMap<String, ByteArray> = linkedMapOf<String, ByteArray>().apply {
        names.forEach { put(it, if (it.endsWith(".zip")) zip(mapOf("signed.bin" to "synthetic".toByteArray())) else "synthetic-$it".toByteArray()) }
        put("Pax_Prolin_Nuxiums/script_VSS.list", script.toByteArray())
    }
    private fun profile(files: Map<String, ByteArray>) = names.associateWith { name -> files.getValue(name).let { PaxFirmwareFingerprint(it.size.toLong(), hash(it)) } }
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun zip(files: Map<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { out -> ZipOutputStream(out).use { zip -> files.forEach { (name, bytes) -> zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() } } }.toByteArray()
    private fun prepare(files: Map<String, ByteArray>, expected: Map<String, PaxFirmwareFingerprint> = profile(files), limits: PaxBundleLimits = PaxBundleLimits()) = PaxFirmwareBundleReader.prepare(ByteArrayInputStream(zip(files)), File(temporary.root, "attempt-${System.nanoTime()}"), expected, limits)
    @Test fun `complete bundle preserves original bytes and installation order`() {
        val files = files(); val bundle = prepare(files)
        assertEquals(names, (listOf(bundle.boot, bundle.os, bundle.application) + bundle.appData + bundle.options).map { it.name })
        assertEquals(names.sumOf { files.getValue(it).size.toLong() }, bundle.totalBytes)
        assertArrayEquals(files.getValue(names[0]), bundle.boot.file.readBytes())
        assertEquals(bundle, PaxFirmwareBundleReader.read(bundle.directory, profile(files)))
    }
    @Test fun `missing boot or dependency is rejected before installer can receive bundle`() {
        for (missing in listOf(names[0], names[1], names[4])) {
            val files = files(); val expected = profile(files); files.remove(missing)
            assertTrue(runCatching { prepare(files, expected) }.isFailure)
        }
    }
    @Test fun `tampered payload and wrong production profile are rejected`() {
        val files = files(); val expected = profile(files); files[names[4]] = "altered".toByteArray()
        assertTrue(runCatching { prepare(files, expected) }.isFailure)
        val valid = prepare(files(), profile(files()))
        assertTrue(runCatching { PaxFirmwareBundleReader.read(valid.directory) }.isFailure)
    }
    @Test fun `script cannot request other commands models paths or duplicate dependencies`() {
        for (bad in listOf(script.replace("MAINAPP", "OTHER"), script + "[shell]\nrun=anything\n", script.replace("Config.sys", "../Config.sys"), script.replace("appdata2=agent.mod", "appdata2=Config.sys"), script.replace("[app1]", "[app1]\nunknown=value"))) {
            val files = files().apply { put("Pax_Prolin_Nuxiums/script_VSS.list", bad.toByteArray()) }
            assertTrue(runCatching { prepare(files) }.isFailure)
        }
    }
    @Test fun `ambiguous nuxiums scripts and other-model-only scripts are rejected`() {
        val files = files(); files["another/Pax_Prolin_Nuxiums/script_VSS.list"] = script.toByteArray()
        assertTrue(runCatching { prepare(files) }.isFailure)
        files.remove("another/Pax_Prolin_Nuxiums/script_VSS.list")
        files["Pax_Prolin/script_VSS.list"] = files.remove("Pax_Prolin_Nuxiums/script_VSS.list")!!
        assertTrue(runCatching { prepare(files) }.isFailure)
    }
    @Test fun `zip traversal absolute paths and canonical collisions are rejected`() {
        for (path in listOf("../escaped", "/absolute", "C:/windows", "x/../alias", "folder\\evil", "CONFIG.SYS")) {
            val files = files().apply { put(path, byteArrayOf(1)) }
            assertTrue(path, runCatching { prepare(files) }.isFailure)
        }
        assertFalse(File(temporary.root, "escaped").exists())
    }
    @Test fun `size and entry limits reject extraction and clean attempt`() {
        val files = files()
        for (limits in listOf(PaxBundleLimits(fileBytes = 3), PaxBundleLimits(totalBytes = 20), PaxBundleLimits(entries = 2))) {
            val destination = File(temporary.root, "limited-${System.nanoTime()}")
            assertTrue(runCatching { PaxFirmwareBundleReader.prepare(ByteArrayInputStream(zip(files)), destination, profile(files), limits) }.isFailure)
            assertFalse(destination.exists())
        }
    }
    @Test fun `nested nuxiums source selects only its dependencies and omits unrelated windows tools`() {
        val flat = files(); val expected = profile(flat)
        val nested = linkedMapOf<String, ByteArray>()
        flat.forEach { (name, bytes) -> nested[if (name in names.drop(2)) "Pax_Prolin_Nuxiums/$name" else name] = bytes }
        nested["Pax_Prolin/script_VSS.list"] = "[wrong-model]".toByteArray()
        nested["Pax_Prolin/Config.sys"] = "wrong-model".toByteArray()
        nested["Pax_Prolin_Nuxiums/TermAssist/TermAssist.exe"] = ByteArray(20)
        val bundle = prepare(nested, expected)
        assertEquals(11, bundle.directory.listFiles()!!.size)
        assertEquals(names[3], bundle.appData.first().name)
        assertFalse(File(bundle.directory, "TermAssist.exe").exists())
    }
    @Test fun `matching fingerprints cannot hide a damaged signed zip payload`() {
        val files = files()
        val output = ByteArrayOutputStream()
        val bytes = "synthetic-crc".toByteArray()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("payload").apply { method = ZipEntry.STORED; size = bytes.size.toLong(); compressedSize = size; crc = CRC32().apply { update(bytes) }.value })
            zip.write(bytes); zip.closeEntry()
        }
        val corrupted = output.toByteArray().apply { this[30 + "payload".length] = (this[30 + "payload".length].toInt() xor 1).toByte() }
        files[names[0]] = corrupted
        assertTrue(runCatching { prepare(files) }.isFailure)
    }
    @Test fun `unix symlink ZIP entries are rejected before extraction`() {
        val files = files(); val archive = zip(files)
        val signature = byteArrayOf(0x50, 0x4b, 0x01, 0x02)
        val central = (0..archive.size - 46).first { archive.copyOfRange(it, it + 4).contentEquals(signature) }
        // UNIX file mode 0120777 in the high 16 bits of central external attributes.
        archive[central + 40] = 0xff.toByte(); archive[central + 41] = 0xa1.toByte()
        val destination = File(temporary.root, "symlink-zip")
        assertTrue(runCatching { PaxFirmwareBundleReader.prepare(ByteArrayInputStream(archive), destination, profile(files)) }.isFailure)
        assertFalse(destination.exists())
    }
    @Test fun `revalidation rejects changed files script and local symlinks`() {
        for (mutation in listOf("asset", "script", "symlink")) {
            val files = files(); val expected = profile(files); val bundle = prepare(files, expected)
            when (mutation) {
                "asset" -> bundle.application.file.appendText("altered")
                "script" -> File(bundle.directory, "script_VSS.list").appendText("[shell]\nrun=anything\n")
                "symlink" -> { val original = bundle.appData.first().file; original.delete(); Files.createSymbolicLink(original.toPath(), File(temporary.root, "outside").toPath()) }
            }
            assertTrue(mutation, runCatching { PaxFirmwareBundleReader.read(bundle.directory, expected) }.isFailure)
        }
    }
    @Test fun `failed import never removes an existing destination`() {
        val destination = temporary.newFolder("existing")
        val preserved = File(destination, "preserved").apply { writeText("keep") }
        assertTrue(runCatching { PaxFirmwareBundleReader.prepare(ByteArrayInputStream(zip(files())), destination, profile(files())) }.isFailure)
        assertEquals("keep", preserved.readText())
    }

}
