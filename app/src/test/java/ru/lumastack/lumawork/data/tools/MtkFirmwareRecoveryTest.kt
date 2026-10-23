package ru.lumastack.lumawork.data.tools

import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File

class MtkFirmwareRecoveryTest {
    @Test fun new_preparation_clears_previous_write_intent_but_preserves_checked_package() {
        val previous=MtkInstallState(owner="engineer",operationId="old",phase=MtkInstallPhase.WriteComplete,
            destructiveStarted=true,pendingAction="finish",agentStarted=true,packageDirectory="package-good",packageHash="sha",bootConfirmedAt=123)
        val fresh=previous.freshOperation("new",MtkInstallPhase.Preparing,false,"Проверка файлов")
        assertFalse(fresh.destructiveStarted);assertFalse(fresh.agentStarted)
        assertNull(fresh.pendingAction);assertNull(fresh.bootConfirmedAt)
        assertEquals(previous.packageDirectory,fresh.packageDirectory)
        assertEquals(MtkInstallPhase.Interrupted,fresh.recover().phase)
    }
    @Test fun destructive_intent_recovers_as_required_recovery_without_resending_commands() {
        val pending=MtkInstallState(owner="engineer").afterWriteEvent(MtkWriteEvent(MtkWritePhase.PreparingStorage,pendingAction="start-download-info"))
        assertTrue(pending.destructiveStarted)
        assertEquals(MtkInstallPhase.RecoveryRequired,pending.recover().phase)
        for (phase in listOf(MtkInstallPhase.PreparingStorage,MtkInstallPhase.WritingPartition,MtkInstallPhase.Finalizing)) {
            val value=MtkInstallState(owner="engineer",phase=phase,destructiveStarted=true,pendingAction="download:super",backupDirectory="private/backup")
            val result=value.recover()
            assertEquals(MtkInstallPhase.RecoveryRequired,result.phase)
            assertFalse(result.active);assertFalse(result.canWrite)
            assertEquals(value.pendingAction,result.pendingAction);assertEquals(value.backupDirectory,result.backupDirectory)
            assertTrue(result.detail.contains("восстановление"))
        }
    }
    @Test fun final_ack_survives_recovery_and_later_error_events() {
        val complete=MtkInstallState(phase=MtkInstallPhase.WriteComplete,destructiveStarted=true,detail=MTK_WRITE_SUCCESS)
        assertEquals(complete,complete.recover())
        assertEquals(complete,complete.afterWriteEvent(MtkWriteEvent(MtkWritePhase.PreparingStorage,pendingAction="format:0")))
    }
    @get:Rule val temp = TemporaryFolder()
    @Test fun dead_import_cleanup_preserves_only_journal_referenced_package_and_unrelated_files() {
        val root = temp.newFolder("owner")
        val keep = File(root, "package-good").apply { mkdir(); File(this,"image").writeText("keep") }
        val staging = File(root,".mtk-staging-interrupted").apply { mkdir(); File(this,"super.img").writeText("partial") }
        val orphan = File(root,"package-uncommitted").apply { mkdir() }
        val agent = File(root,".agent-interrupted").apply { writeText("partial") }
        val unrelated = File(root,"other").apply { mkdir() }
        val journal = File(root,"operation.json").apply { writeText("journal") }
        val unfinishedBackup=File(root,".mtk-backup-unfinished").apply { mkdir();File(this,"nvram.bin").writeText("partial") }
        val completeBackup=File(root,"backup-complete").apply { mkdir();File(this,"nvram.bin").writeText("keep") }
        cleanupMtkImports(root, keep)
        assertTrue(keep.isDirectory); assertEquals("keep", File(keep,"image").readText())
        assertFalse(staging.exists()); assertFalse(orphan.exists()); assertFalse(agent.exists())
        assertTrue(unrelated.isDirectory); assertEquals("journal",journal.readText())
        assertFalse(unfinishedBackup.exists());assertEquals("keep",File(completeBackup,"nvram.bin").readText())
    }
    @Test fun process_death_interrupts_diagnostics_without_automatic_retry_or_write_permission() {
        for (phase in listOf(MtkInstallPhase.WaitingForUsb, MtkInstallPhase.Permission, MtkInstallPhase.Handshake, MtkInstallPhase.Preparing, MtkInstallPhase.LoadingAgent)) {
            val recovered = MtkInstallState(owner="engineer", phase=phase, operationId="op", packageHash="sha").recover()
            assertEquals(MtkInstallPhase.Interrupted, recovered.phase)
            assertFalse(recovered.active); assertFalse(recovered.canWrite)
            assertEquals("op", recovered.operationId); assertEquals("sha", recovered.packageHash)
        }
    }
    @Test fun interrupted_ram_agent_is_distinct_from_passive_identification() {
        val recovered = MtkInstallState(phase = MtkInstallPhase.LoadingAgent, agentStarted = true).recover()
        assertEquals(MtkInstallPhase.Interrupted, recovered.phase)
        assertTrue(recovered.detail.contains("мог остаться в RAM"))
        assertFalse(recovered.canWrite)
    }
    @Test fun same_vid_pid_or_soc_never_proves_model_or_enables_download() {
        val value = MtkInstallState(phase=MtkInstallPhase.Inspected, info=MtkPreloaderInfo(0x0717,0x8a00,0xca01,0x0200,3))
        assertFalse(value.canWrite); assertFalse(value.info!!.modelConfirmed)
        assertEquals(value, value.recover())
        assertFalse(value.detail.contains("Прошивка записана"))
    }
}
