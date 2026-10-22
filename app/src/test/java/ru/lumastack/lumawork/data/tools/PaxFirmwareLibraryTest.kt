package ru.lumastack.lumawork.data.tools

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PaxFirmwareLibraryTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun zip(files: Map<String, ByteArray>) = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { zip -> files.forEach { (name, bytes) -> zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() } }
    }.toByteArray()
    private fun app(model: PaxModel, version: String) = zip(mapOf(
        "${model.archiveFolder}/script_VSS.list" to "[app0]\npackage=app.aip\n".toByteArray(),
        "${model.archiveFolder}/app.aip" to zip(mapOf("appinfo" to "[app]\nid=MAINAPP\nversion=$version\n".toByteArray())),
    ))
    @Test fun `model versions and selections survive restart without sharing another account`() {
        val root = temporary.newFolder("owner")
        val library = PaxFirmwareLibrary(root)
        val old = library.importArchive(ByteArrayInputStream(app(PaxModel.Q25, "3.0.1")), PaxModel.Q25, PaxArchiveKind.Firmware, "old.zip")
        val oldId = old.selected(PaxModel.Q25, PaxArchiveKind.Firmware)!!
        library.importArchive(ByteArrayInputStream(app(PaxModel.Q25, "3.0.2")), PaxModel.Q25, PaxArchiveKind.Firmware, "new.zip")
        library.importArchive(ByteArrayInputStream(app(PaxModel.D270, "3.0.3")), PaxModel.D270, PaxArchiveKind.Firmware, "base.zip")
        val restored = PaxFirmwareLibrary(root)
        assertEquals("3.0.2", restored.bundle(PaxModel.Q25)?.expectedMainApp)
        assertEquals("3.0.3", restored.bundle(PaxModel.D270)?.expectedMainApp)
        restored.select(oldId)
        assertEquals("3.0.1", PaxFirmwareLibrary(root).bundle(PaxModel.Q25)?.expectedMainApp)
        assertEquals(3, restored.read().archives.size)
        assertTrue(PaxFirmwareLibrary(temporary.newFolder("other-owner")).read().archives.isEmpty())
    }
    @Test fun `failed import or modified saved archive preserves previous selected bundle and journal`() {
        val root = temporary.newFolder("owner")
        val library = PaxFirmwareLibrary(root)
        library.importArchive(ByteArrayInputStream(app(PaxModel.Q25, "3.0.1")), PaxModel.Q25, PaxArchiveKind.Firmware, "old.zip")
        val oldId = library.read().selected(PaxModel.Q25, PaxArchiveKind.Firmware)!!
        library.importArchive(ByteArrayInputStream(app(PaxModel.Q25, "3.0.2")), PaxModel.Q25, PaxArchiveKind.Firmware, "new.zip")
        val previous = library.read()
        val journal = File(root, "installation.json").apply { writeText("pinned operation") }
        assertTrue(runCatching { library.importArchive(ByteArrayInputStream(byteArrayOf(1)), PaxModel.Q25, PaxArchiveKind.Firmware, "bad.zip") }.isFailure)
        val old = previous.archives.single { it.id == oldId }
        File(root, "${old.directory}/app.aip").writeText("changed")
        assertTrue(runCatching { library.select(oldId) }.isFailure)
        assertEquals(previous, library.read())
        assertEquals("3.0.2", library.bundle(PaxModel.Q25)?.expectedMainApp)
        assertEquals("pinned operation", journal.readText())
    }
    @Test fun `pending FTP destination survives restart and rejects S210 Prolin`() {
        val root = temporary.newFolder("owner")
        val pending = PaxArchiveDownload("download", PaxModel.Q25, PaxArchiveKind.Prolin)
        PaxFirmwareLibrary(root).pending(pending)
        assertEquals(pending, PaxFirmwareLibrary(root).read().pending)
        assertTrue(runCatching { PaxFirmwareLibrary(root).pending(pending.copy(model = PaxModel.S210)) }.isFailure)
        PaxFirmwareLibrary(root).pending(null)
        assertNull(PaxFirmwareLibrary(root).read().pending)
    }
    @Test fun `switching MAINAPP versions preserves separately selected Prolin and pinned installation snapshot`() {
        val root = temporary.newFolder("owner")
        val library = PaxFirmwareLibrary(root)
        val old = library.importArchive(ByteArrayInputStream(app(PaxModel.Q25, "3.0.1")), PaxModel.Q25, PaxArchiveKind.Firmware, "old.zip")
        val oldId = old.selected(PaxModel.Q25, PaxArchiveKind.Firmware)!!
        val os = zip(mapOf("os.zip" to zip(mapOf("RELEASE.TXT" to "Product : OS\nVersion : 2.7.181-r13278\nModel : PAXQ25\n".toByteArray()))))
        library.importArchive(ByteArrayInputStream(os), PaxModel.Q25, PaxArchiveKind.Prolin, "POS Loader и OS.zip")
        val pinned = library.bundle(PaxModel.Q25)!!
        library.importArchive(ByteArrayInputStream(app(PaxModel.Q25, "3.0.2")), PaxModel.Q25, PaxArchiveKind.Firmware, "new.zip")
        assertEquals("2.7.181-r13278", library.bundle(PaxModel.Q25)?.expectedOs)
        assertEquals("3.0.2", library.bundle(PaxModel.Q25)?.expectedMainApp)
        assertEquals("3.0.1", PaxArchiveCatalog.read(pinned.directory, PaxModel.Q25).expectedMainApp)
        val savedDirectories = root.listFiles()!!.count { it.isDirectory }
        library.select(oldId)
        assertEquals("2.7.181-r13278", library.bundle(PaxModel.Q25)?.expectedOs)
        assertEquals("3.0.1", library.bundle(PaxModel.Q25)?.expectedMainApp)
        assertEquals(savedDirectories, root.listFiles()!!.count { it.isDirectory })
    }
    @Test fun `restart after completed FTP import does not add the same pending download twice`() {
        val root = temporary.newFolder("owner")
        val library = PaxFirmwareLibrary(root)
        library.pending(PaxArchiveDownload("completed-download", PaxModel.Q25, PaxArchiveKind.Firmware))
        library.importArchive(ByteArrayInputStream(app(PaxModel.Q25, "3.0.1")), PaxModel.Q25, PaxArchiveKind.Firmware, "base.zip")
        val restarted = PaxFirmwareLibrary(root)
        restarted.importArchive(ByteArrayInputStream(app(PaxModel.Q25, "3.0.1")), PaxModel.Q25, PaxArchiveKind.Firmware, "base.zip")
        assertEquals(1, restarted.read().archives.size)
    }
}
