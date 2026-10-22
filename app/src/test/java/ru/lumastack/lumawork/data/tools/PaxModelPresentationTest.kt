package ru.lumastack.lumawork.data.tools

import org.junit.Assert.*
import org.junit.Test

class PaxModelPresentationTest {
    @Test fun `S210 offers only OS APP and combined operations`() {
        assertEquals(listOf(PaxInstallMode.Full, PaxInstallMode.Prolin, PaxInstallMode.MainApp), PaxModel.S210.availableModes)
        assertEquals(listOf("OS + APP", "OS", "APP"), PaxModel.S210.availableModes.map { it.titleFor(PaxModel.S210) })
        assertFalse(PaxModel.S210.availableModes.any { it == PaxInstallMode.RemoveMainApp })
        assertTrue(PaxInstallMode.entries.all { !it.titleFor(PaxModel.S210).contains("MAINAPP", true) })
        assertTrue(PaxFirmwareStep.entries.all { !it.titleFor(PaxModel.S210).contains("MAINAPP", true) })
    }
    @Test fun `XCB terminals retain all four operations and MAINAPP naming`() {
        for (model in listOf(PaxModel.D230, PaxModel.D270, PaxModel.Q25)) {
            assertEquals(PaxInstallMode.entries, model.availableModes)
            assertEquals("MAINAPP", PaxInstallMode.MainApp.titleFor(model))
            assertEquals("Удалить MAINAPP", PaxInstallMode.RemoveMainApp.titleFor(model))
        }
    }
}
