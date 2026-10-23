package ru.lumastack.lumawork.data.tools

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MtkFirmwarePackageTest {
    @get:Rule val temp = TemporaryFolder()
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    private val image = byteArrayOf(1, 2, 3)
    private val agentBytes = byteArrayOf(4, 5, 6, 7)
    private val scatter = "platform: MT6761\nproject: P8KC\nstorage: EMMC\n".toByteArray()
    private fun profile() = MtkPackageProfile("fixture", listOf(
        MtkImageSpec("preloader", "preloader_P8KC.bin", 3, sha(image)),
        MtkImageSpec("preloader_backup", "preloader_P8KC.bin", 3, sha(image)),
        MtkImageSpec("logo", "logo-verified.bin", 3, sha(image))), sha(scatter),
        MtkAgentProfile(4, sha(agentBytes), MtkAgentStage(0, 2, sha(agentBytes.take(2).toByteArray()), 0x200000, 0),
            MtkAgentStage(2, 2, sha(agentBytes.drop(2).toByteArray()), 0x40000000, 0)))
    private fun zip(entries: List<Pair<String, ByteArray>>) = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { zip -> entries.forEach { (name, bytes) -> zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() } }
    }.toByteArray()
    private fun entries() = listOf("emmc/MT6761_Android_scatter.txt" to scatter, "emmc/preloader_P8KC.bin" to image, "emmc/logo-verified.bin" to image)
    private fun agent() = temp.newFile().apply { writeBytes(agentBytes) }
    @Test fun verified_names_and_backup_share_original_file() {
        val pkg = MtkFirmwarePackageReader(profile()).prepare(ByteArrayInputStream(zip(entries())), File(temp.root, "prepared"), agent())
        assertEquals(listOf("preloader", "preloader_backup", "logo"), pkg.images.map { it.partitionName })
        assertEquals(pkg.images[0].file, pkg.images[1].file)
        assertEquals("logo-verified.bin", pkg.images[2].file.name)
        assertArrayEquals(image, pkg.images[2].file.readBytes())
        assertArrayEquals(agentBytes, pkg.agent.readBytes())
    }
    @Test fun rejects_bad_hash_wrong_scatter_missing_file_and_agent() {
        val cases = listOf(entries().dropLast(1), entries().map { (n,b) -> n to if (n.endsWith(".bin")) byteArrayOf(9,9,9) else b },
            entries().map { (n,b) -> n to if (n.endsWith(".txt")) String(b).replace("P8KC", "OTHER").toByteArray() else b })
        cases.forEachIndexed { i, values ->
            val destination = File(temp.root, "bad-$i")
            assertThrows(IllegalArgumentException::class.java) { MtkFirmwarePackageReader(profile()).prepare(ByteArrayInputStream(zip(values)), destination, agent()) }
            assertFalse(destination.exists())
        }
        assertThrows(IllegalArgumentException::class.java) { MtkFirmwarePackageReader(profile()).prepare(ByteArrayInputStream(zip(entries())), File(temp.root, "bad-agent"), agent().apply { writeText("bad") }) }
    }
    @Test fun rejects_traversal_absolute_and_duplicate_normalized_paths() {
        listOf("../escape", "/absolute", "C:/escape", "emmc/../escape", "emmc\\escape", "emmc//logo-verified.bin").forEach { name ->
            assertThrows(IllegalArgumentException::class.java) { MtkFirmwarePackageReader(profile()).prepare(ByteArrayInputStream(zip(entries() + (name to image))), File(temp.root, "unsafe"), agent()) }
        }
        assertThrows(IllegalArgumentException::class.java) { mtkArchivePath("emmc/a", mutableSetOf("emmc/a")) }
        assertThrows(IllegalArgumentException::class.java) {
            MtkFirmwarePackageReader(profile()).prepare(ByteArrayInputStream(zip(entries() + ("other/logo-verified.bin" to image))), File(temp.root, "duplicate"), agent())
        }
    }
    @Test fun insufficient_space_and_cancel_leave_existing_package_untouched() {
        val destination = temp.newFolder("existing"); File(destination,"marker").writeText("keep")
        assertThrows(IllegalArgumentException::class.java) { MtkFirmwarePackageReader(profile(), availableSpace = { 0L }).prepare(ByteArrayInputStream(zip(entries())), File(temp.root, "no-space"), agent()) }
        assertEquals("keep", File(destination,"marker").readText())
        val fresh = File(temp.root, "cancelled")
        assertThrows(InterruptedException::class.java) { MtkFirmwarePackageReader(profile(), checkpoint = {
            if (temp.root.listFiles()!!.any { it.name.startsWith(".mtk-staging-") }) throw InterruptedException()
        }).prepare(ByteArrayInputStream(zip(entries())), fresh, agent()) }
        assertFalse(fresh.exists())
        assertFalse(temp.root.listFiles()!!.any { it.name.startsWith(".mtk-staging-") })
    }
    @Test fun p8_profile_uses_long_file_sizes_and_both_agent_stages() {
        assertEquals(2348906824L, MTK_P8_PROFILE.images.single { it.partitionName == "super" }.size)
        assertEquals(19, MTK_P8_PROFILE.images.size)
        assertEquals(14812L, MTK_P8_PROFILE.agent.stage1.offset)
        assertEquals(335116L, MTK_P8_PROFILE.agent.stage2.size)
        assertTrue(MTK_P8_PROFILE.requiredBytes > Int.MAX_VALUE.toLong())
    }
    @Test fun directory_entry_cannot_hide_unbounded_payload_from_limits_and_cancellation() {
        assertThrows(IllegalArgumentException::class.java) {
            MtkFirmwarePackageReader(profile()).prepare(ByteArrayInputStream(zip(entries() + ("extra/" to byteArrayOf(1)))), File(temp.root,"directory-payload"), agent())
        }
    }
}
