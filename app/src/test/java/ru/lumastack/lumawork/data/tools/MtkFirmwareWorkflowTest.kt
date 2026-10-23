package ru.lumastack.lumawork.data.tools

import java.io.File
import java.io.IOException
import java.io.OutputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

internal fun mtkWriteTarget()=MtkTargetInfo(MtkChipInfo(0x717,0x8a00,0xca01,0x200,1),
    MtkRamInfo(1,0x200000,0x40000,2,0x40000000,4294967296L),
    MtkEmmcInfo(1,512,0x400000,0x400000,0x400000,0,0,0,0,31289507840L),
    2097152,2097152,"GPT","a".repeat(64),68,mtkP8LayoutFixture(),MTK_P8_BOARD)

internal fun mtkWritePackage(root: File): Pair<MtkFirmwarePackage,MtkPackageProfile> {
    val source=File(root,"source").apply { mkdirs() }
    val specs=MTK_P8_PROFILE.images.map { item ->
        val bytes=("original fixture "+item.fileName).toByteArray()
        File(source,item.fileName).writeBytes(bytes)
        item.copy(size=bytes.size.toLong(),sha256=mtkSha256(bytes))
    }
    val scatter="platform: MT6761\nproject: P8KC\nstorage: EMMC\n".toByteArray()
    File(source,"MT6761_Android_scatter.txt").writeBytes(scatter)
    val agent=File(root,"agent.bin").apply { writeBytes(byteArrayOf(1,2,3,4)) }
    val profile=MTK_P8_PROFILE.copy(images=specs,scatterSha256=mtkSha256(scatter),agent=MtkAgentProfile(4,mtkSha256(agent.readBytes()),
        MtkAgentStage(0,2,mtkSha256(byteArrayOf(1,2)),0x200000,0),MtkAgentStage(2,2,mtkSha256(byteArrayOf(3,4)),0x40000000,0)))
    return MtkFirmwarePackageReader(profile).prepareDirectory(source,File(root,"package"),agent) to profile
}

class MtkFirmwareWorkflowTest {
    @get:Rule val temp=TemporaryFolder()
    private class Agent(private val events: List<MtkWriteEvent>,val failure: String?=null,
        val changedLayout: Boolean=false,val afterFinish: ()->Unit={}): MtkUpgradeAgent {
        val calls=mutableListOf<String>(); private var formats=0
        private fun call(action: String) { calls+=action; if(action.startsWith("format:") || action.startsWith("download:") || action=="start-download-info" || action=="finish") assertEquals(action,events.last().pendingAction)
            if(action==failure) throw IOException("Test disconnect") }
        override fun read(partition: MtkPartition,output: OutputStream) {
            throw AssertionError("Штатная прошивка не должна копировать служебные разделы: ${partition.name}")
        }
        override fun readLayout(): MtkGptInfo { call("layout");return mtkP8LayoutFixture().let { if(changedLayout) it.copy(partitions=it.partitions.dropLast(1)) else it } }
        override fun beginUpgrade()=call("start-download-info")
        override fun format(range: MtkFormatRange)=call("format:${formats++}")
        override fun download(image: MtkImage,confirmed: (Long)->Unit) { call("download:${image.partitionName}");confirmed(image.size) }
        override fun finish() { call("finish");afterFinish() }
    }
    @Test fun full_upgrade_starts_download_info_without_backups_then_formats_downloads_and_finalizes_once() {
        val (pkg,profile)=mtkWritePackage(temp.root)
        val events=mutableListOf<MtkWriteEvent>();val agent=Agent(events)
        val workflow=MtkFirmwareWorkflow(agent,profile=profile,event={events+=it})
        val result=workflow.execute(pkg,mtkWriteTarget())
        assertEquals(MTK_P8_PROFILE.images.map { "download:${it.partitionName}" },agent.calls.filter { it.startsWith("download:") })
        assertEquals(4,agent.calls.count { it.startsWith("format:") })
        assertEquals("start-download-info",agent.calls.first())
        assertEquals("format:0",agent.calls[1])
        assertFalse(events.any { it.phase.name=="BackingUp" })
        assertEquals("finish",agent.calls.last())
        assertEquals(MtkWritePhase.WriteComplete,events.last().phase)
        assertEquals(MTK_WRITE_SUCCESS,result.detail)
        assertThrows(IllegalStateException::class.java) { workflow.execute(pkg,mtkWriteTarget()) }
    }
    @Test fun incompatible_target_and_modified_file_stop_before_readback_or_format() {
        val (pkg,profile)=mtkWritePackage(temp.root)
        for(target in listOf(mtkWriteTarget().copy(boardName="OTHER"),mtkWriteTarget().copy(gpt=null))) {
            val events=mutableListOf<MtkWriteEvent>();val agent=Agent(events)
            assertThrows(IllegalArgumentException::class.java) { MtkFirmwareWorkflow(agent,profile=profile,event={events+=it}).execute(pkg,target) }
            assertTrue(agent.calls.isEmpty())
        }
        pkg.images.last().file.appendText("changed")
        val events=mutableListOf<MtkWriteEvent>();val agent=Agent(events)
        assertThrows(IllegalArgumentException::class.java) { MtkFirmwareWorkflow(agent,profile=profile,event={events+=it}).execute(pkg,mtkWriteTarget()) }
        assertTrue(agent.calls.isEmpty())
    }
    @Test fun failed_intent_persistence_prevents_the_next_destructive_command() {
        val (pkg,profile)=mtkWritePackage(temp.root)
        for(failure in listOf("start-download-info","format:0")) {
            val events=mutableListOf<MtkWriteEvent>();val agent=Agent(events)
            assertThrows(IOException::class.java) { MtkFirmwareWorkflow(agent,profile=profile,event={
                if(it.pendingAction==failure) throw IOException("Journal unavailable")
                events+=it
            }).execute(pkg,mtkWriteTarget()) }
            assertFalse(agent.calls.any { it.startsWith("format:") || it.startsWith("download:") })
            assertFalse(events.any { it.phase==MtkWritePhase.WriteComplete })
        }
    }
    @Test fun format_download_and_finalizer_failures_keep_pending_intent_and_never_resume() {
        val (pkg,profile)=mtkWritePackage(temp.root)
        for(failure in listOf("start-download-info","format:0","download:super","download:userdata","finish")) {
            val events=mutableListOf<MtkWriteEvent>();val agent=Agent(events,failure)
            val workflow=MtkFirmwareWorkflow(agent,profile=profile,event={events+=it})
            assertThrows(IOException::class.java) { workflow.execute(pkg,mtkWriteTarget()) }
            assertEquals(failure,agent.calls.last());assertEquals(failure,events.last().pendingAction)
            assertFalse(events.any { it.phase==MtkWritePhase.WriteComplete })
            val count=agent.calls.size
            assertThrows(IllegalStateException::class.java) { workflow.execute(pkg,mtkWriteTarget()) }
            assertEquals(count,agent.calls.size)
        }
    }
    @Test fun cancellation_or_detach_after_final_ack_does_not_erase_success() {
        val (pkg,profile)=mtkWritePackage(temp.root)
        val events=mutableListOf<MtkWriteEvent>();var detached=false
        val agent=Agent(events,afterFinish={detached=true})
        val result=MtkFirmwareWorkflow(agent,profile=profile,
            checkpoint={if(detached) throw kotlinx.coroutines.CancellationException("USB detached after ACK")},event={events+=it}).execute(pkg,mtkWriteTarget())
        assertEquals(MTK_WRITE_SUCCESS,result.detail)
        assertEquals(MtkWritePhase.WriteComplete,events.last().phase)
    }
    @Test fun altered_gpt_after_format_stops_before_any_download() {
        val (pkg,profile)=mtkWritePackage(temp.root)
        val events=mutableListOf<MtkWriteEvent>();val agent=Agent(events,changedLayout=true)
        assertThrows(IllegalArgumentException::class.java) { MtkFirmwareWorkflow(agent,profile=profile,event={events+=it}).execute(pkg,mtkWriteTarget()) }
        assertEquals("layout",agent.calls.last())
        assertFalse(agent.calls.any { it.startsWith("download:") })
    }
}
