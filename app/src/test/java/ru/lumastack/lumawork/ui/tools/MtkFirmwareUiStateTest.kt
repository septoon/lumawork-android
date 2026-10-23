package ru.lumastack.lumawork.ui.tools

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import ru.lumastack.lumawork.data.tools.*

class MtkFirmwareUiStateTest {
    @get:Rule val temp=TemporaryFolder()
    @Test fun download_requires_checked_package_and_is_disabled_during_wait_or_recovery() {
        val (pkg,_)=mtkWritePackage(temp.root)
        val ready=MtkFirmwareUiState(installation=MtkInstallState(phase=MtkInstallPhase.Prepared,packageDirectory=pkg.directory.path,packageHash=pkg.sha256),
            firmware=pkg,agentVerified=true,loading=false)
        assertTrue(ready.canStartDownload)
        assertFalse(ready.copy(firmware=null).canStartDownload)
        assertFalse(ready.copy(agentVerified=false).canStartDownload)
        for(phase in listOf(MtkInstallPhase.WaitingForUsb,MtkInstallPhase.Permission,MtkInstallPhase.WritingPartition,MtkInstallPhase.RecoveryRequired))
            assertFalse(ready.copy(installation=ready.installation.copy(phase=phase)).canStartDownload)
    }
    @Test fun success_does_not_imply_confirmed_android_boot_or_require_reconnection() {
        val complete=MtkInstallState().afterWriteEvent(MtkWriteEvent(MtkWritePhase.WriteComplete))
        assertEquals("Прошивка записана. Можно отсоединить USB. Дождитесь загрузки терминала",complete.detail)
        assertNull(complete.bootConfirmedAt)
        assertFalse(complete.active)
        assertEquals(complete,complete.recover())
    }
    @Test fun write_speed_uses_confirmed_bytes_decimal_megabytes_and_excludes_preparation_time() {
        val speed=MtkWriteSpeed()
        assertNull(speed.sample(0,0))
        assertNull(speed.sample(2_000_000,30_000))
        assertEquals(12.0,speed.sample(14_000_000,31_000)!!,0.001)
        assertEquals(0.0,speed.sample(14_000_000,32_000)!!,0.001)
        assertNull(speed.sample(1_000_000,33_000))
        assertEquals(10.0,speed.sample(11_000_000,34_000)!!,0.001)
        assertNull(speed.sample(12_000_000,33_500))
        assertNull(speed.sample(0,35_000))
        assertNull(speed.sample(2_400_000_000,50_000))
        assertEquals(15.0,speed.sample(2_415_000_000,51_000)!!,0.001)
    }
}
