package ru.lumastack.lumawork.data.tools

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Opt-in local artifact check. Firmware and agent never enter Git or test assets. */
class MtkP8ReferenceTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun captured_primary_and_backup_gpt_match_the_profile() {
        val root = System.getenv("UNITODI_P8_GPT_FIXTURE_DIRECTORY")?.let(::File)
        assumeTrue("Set UNITODI_P8_GPT_FIXTURE_DIRECTORY to private captured GPT replies", root?.isDirectory == true)
        val result = parseMtkGpt(File(root,"reference-pgpt.bin").readBytes(),File(root,"reference-sgpt.bin").readBytes(),31289507840L)
        assertEquals(48,result.partitions.size)
        assertTrue(result.matchesP8Profile)
    }
    @Test fun original_emmc_images_and_agent_match_the_captured_profile() {
        val source = System.getenv("UNITODI_P8_REFERENCE_DIRECTORY")?.let(::File)
        assumeTrue("Set UNITODI_P8_REFERENCE_DIRECTORY to the research folder", source?.isDirectory == true)
        val root = temp.newFolder("package")
        val emmc = File(root, "emmc").apply { mkdir() }
        (MTK_P8_PROFILE.images.map { it.fileName }.distinct() + "MT6761_Android_scatter.txt").forEach { name ->
            Files.createLink(File(emmc, name).toPath(), File(source, "emmc/$name").toPath())
        }
        val agent = File(source, "Download tool 6.4.14 (agenew)/agenew/data/Agenew FlashTool 2444/MTK_AllInOne_DA.bin")
        Files.createLink(File(root, "agent.bin").toPath(), agent.toPath())
        val pkg = MtkFirmwarePackageReader().inspect(root)
        assertEquals(19, pkg.images.size)
        assertEquals(2348906824L, pkg.images.single { it.partitionName == "super" }.size)
        assertEquals(0xa386, mtkDownloadChecksum(File(emmc, "preloader_P8KC.bin").readBytes()))
        val stage1 = ByteArray(MTK_P8_PROFILE.agent.stage1.size.toInt())
        java.io.RandomAccessFile(agent, "r").use { it.seek(MTK_P8_PROFILE.agent.stage1.offset); it.readFully(stage1) }
        assertEquals(0xa6a1, mtkSendDaChecksum(stage1))
    }
}
