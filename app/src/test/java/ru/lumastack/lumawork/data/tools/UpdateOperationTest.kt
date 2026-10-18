package ru.lumastack.lumawork.data.tools

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class UpdateOperationTest {
    private fun copying() = UpdateOperation(UpdatePhase.Transferring, "device-a", 100, 0)
    @Test fun `byte completion alone never permits disconnect or reports installation`() {
        val state = copying().copied(100)
        assertEquals(UpdatePhase.Copied, state.phase)
        assertFalse(state.canDisconnect)
        assertEquals(UpdatePhase.InstallationUnknown, state.closed(false).phase)
    }
    @Test fun `safe disconnect requires complete verified copy and closed session plus autonomy evidence`() {
        val state = copying().copied(100).closed(true)
        assertTrue(state.canDisconnect)
        assertEquals(UpdatePhase.SafeToDisconnect, state.phase)
        assertEquals(UpdatePhase.TerminalUpdating, state.detached().phase)
        assertFalse(state.detached().installationConfirmed)
    }
    @Test fun `partial copy or unplug during copying is interrupted`() {
        assertTrue(runCatching { copying().copied(99) }.isFailure)
        assertEquals(UpdatePhase.Interrupted, copying().detached().phase)
        assertTrue(runCatching { copying().closed(true) }.isFailure)
    }
    @Test fun `unconfirmed autonomy never permits disconnect even after session close`() {
        val state = copying().copied(100).closed(false)
        assertFalse(state.canDisconnect)
        assertEquals(UpdatePhase.InstallationUnknown, state.detached().phase)
    }
    @Test fun `restart cannot reuse old disconnect permission or claim installation success`() {
        val saved = copying().copied(100).closed(true)
        val restored = Json.decodeFromString<UpdateOperation>(Json.encodeToString(saved)).recover()
        assertEquals(UpdatePhase.InstallationUnknown, restored.phase)
        assertFalse(restored.canDisconnect)
        assertFalse(restored.installationConfirmed)
    }
    @Test fun `installation success requires explicit protocol result for same device`() {
        val state = copying().copied(100).closed(true).detached()
        assertTrue(runCatching { state.confirmInstallation("other") }.isFailure)
        assertTrue(state.confirmInstallation("device-a").installationConfirmed)
    }
    @Test fun `different device cannot advance existing operation`() {
        assertTrue(runCatching { copying().progress("device-b", 50) }.isFailure)
        assertEquals(50L, copying().progress("device-a", 50).bytesCopied)
    }
    @Test fun `USB descriptor is candidate only and never identifies a terminal model`() {
        assertEquals(UsbInterfaceKind.MtpCandidate, classifyUsbInterface(6, 1, 1))
        assertEquals(UsbInterfaceKind.CdcAcm, classifyUsbInterface(2, 2, 1))
        assertEquals(UsbInterfaceKind.Other, classifyUsbInterface(255, 0, 0))
    }
}
