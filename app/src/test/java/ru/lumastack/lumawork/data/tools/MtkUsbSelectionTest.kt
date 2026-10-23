package ru.lumastack.lumawork.data.tools

import org.junit.Assert.*
import org.junit.Test

class MtkUsbSelectionTest {
    @Test fun only_captured_preloader_id_is_accepted() {
        assertTrue(isMtkPreloader(0x0e8d, 0x2000))
        assertFalse(isMtkPreloader(0x0e8d, 0x0003)); assertFalse(isMtkPreloader(0x0e8d, 0x2001))
    }
    @Test fun wait_before_attach_delayed_permission_multiple_devices_and_reused_path() {
        val selection = MtkUsbSelection()
        assertNull(selection.select(emptyList()))
        val candidate = MtkUsbCandidate("/usb/1", 1, 2, false)
        assertEquals(candidate, selection.select(listOf(candidate)))
        assertFalse(selection.canOpen(candidate, candidate))
        val granted = candidate.copy(permission = true)
        assertTrue(selection.canOpen(granted, granted))
        assertFalse(selection.canOpen(granted, null))
        assertFalse(selection.canOpen(granted, granted.copy(epoch = 3)))
        assertFalse(selection.canOpen(granted, granted.copy(deviceId = 2)))
        assertThrows(MtkProtocolException::class.java) { selection.select(listOf(granted, granted.copy(key="/usb/2"))) }
    }
}
