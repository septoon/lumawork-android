package ru.lumastack.lumawork.data.tools

import org.junit.Assert.*
import org.junit.Test

class FirmwareResetTest {
    @Test fun p8_reset_from_every_phase_keeps_package_and_clears_previous_usb_and_write_state() {
        for (phase in MtkInstallPhase.entries) {
            val old=MtkInstallState(owner="engineer",operationId="old",phase=phase,packageDirectory="package-good",packageHash="sha",
                target=mtkWriteTarget(),info=MtkPreloaderInfo(0x717,0x8a00,0xca01,0x200,3),usbPermission=true,agentStarted=true,
                destructiveStarted=true,pendingAction="download:super",lastAcknowledgedAction="download:boot_a",
                confirmedActions=listOf("format:0"),fileName="super",bytes=123,totalBytes=456,bootConfirmedAt=99,upgradeRequested=true)
            val fresh=old.resetForNewOperation("new")
            assertEquals("new",fresh.operationId);assertEquals("engineer",fresh.owner)
            assertEquals(old.packageDirectory,fresh.packageDirectory);assertEquals(old.packageHash,fresh.packageHash)
            assertEquals(MtkInstallPhase.Prepared,fresh.phase);assertTrue(fresh.canWrite);assertFalse(fresh.active)
            assertEquals(fresh,fresh.recover())
            assertNull(fresh.target);assertNull(fresh.info);assertFalse(fresh.usbPermission);assertFalse(fresh.agentStarted)
            assertFalse(fresh.destructiveStarted);assertNull(fresh.pendingAction);assertNull(fresh.lastAcknowledgedAction)
            assertTrue(fresh.confirmedActions.isEmpty());assertNull(fresh.bootConfirmedAt);assertFalse(fresh.upgradeRequested)
            assertNull(fresh.fileName);assertEquals(0L,fresh.bytes);assertEquals(0L,fresh.totalBytes)
            assertEquals(phase,old.phase)
        }
    }
    @Test fun p8_reset_without_a_complete_package_does_not_enable_download() {
        for (state in listOf(MtkInstallState(),MtkInstallState(packageDirectory="package-only"))) {
            val fresh=state.resetForNewOperation("new")
            assertEquals(MtkInstallPhase.Idle,fresh.phase);assertFalse(fresh.canWrite)
        }
    }
    @Test fun pax_reset_for_every_model_and_phase_keeps_selection_but_discards_resume_and_identity() {
        for (model in PaxModel.entries) for (phase in PaxInstallPhase.entries) {
            val old=PaxInstallState(owner="engineer",operationId="old",model=model,mode=PaxInstallMode.Full,phase=phase,
                bundleDirectory="bundle-good",expectedPinpadOs="8.1.12.274",step=PaxFirmwareStep.OsInstall,stepNumber=3,stepCount=10,
                completedActions=listOf("remove"),pendingAction="os",recoveryRequired=true,deviceFingerprint="old-device",
                packageIdentity="old-package",legacyRecovery=true,fileName="os.zip",bytes=123,totalBytes=456,critical=true)
            val fresh=old.resetForNewOperation("new")
            assertEquals("new",fresh.operationId);assertEquals(old.owner,fresh.owner);assertEquals(model,fresh.model)
            assertEquals(old.mode,fresh.mode);assertEquals(old.bundleDirectory,fresh.bundleDirectory)
            assertEquals(old.expectedPinpadOs,fresh.expectedPinpadOs);assertEquals(PaxInstallPhase.Ready,fresh.phase)
            assertFalse(fresh.active);assertFalse(fresh.canResume);assertFalse(fresh.awaitingCompletionAcknowledgement)
            assertNull(fresh.step);assertNull(fresh.pendingAction);assertTrue(fresh.completedActions.isEmpty())
            assertNull(fresh.deviceFingerprint);assertNull(fresh.packageIdentity);assertFalse(fresh.legacyRecovery)
            assertFalse(fresh.critical);assertFalse(fresh.recoveryRequired);assertEquals(0L,fresh.bytes)
        }
    }
}
