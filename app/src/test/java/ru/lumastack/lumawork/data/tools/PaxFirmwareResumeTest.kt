package ru.lumastack.lumawork.data.tools

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PaxFirmwareResumeTest {
    @Test fun `completion alert appears only for completed operation and acknowledgement is durable`() {
        val completed = PaxInstallState(phase = PaxInstallPhase.Complete, operationId = "completed")
        assertTrue(completed.awaitingCompletionAcknowledgement)
        for (phase in PaxInstallPhase.entries.filter { it != PaxInstallPhase.Complete })
            assertFalse(completed.copy(phase = phase).awaitingCompletionAcknowledgement)
        assertFalse(completed.copy(operationId = "").awaitingCompletionAcknowledgement)
        val acknowledged = completed.copy(completionAcknowledged = true)
        val json = kotlinx.serialization.json.Json { encodeDefaults = true }
        val restored = json.decodeFromString<PaxInstallState>(json.encodeToString(PaxInstallState.serializer(), acknowledged))
        assertFalse(restored.awaitingCompletionAcknowledgement)
        val legacy = json.decodeFromString<PaxInstallState>("""{"phase":"Complete","operationId":"previous"}""")
        assertTrue(legacy.awaitingCompletionAcknowledgement)
    }

    private val calls = mutableListOf<String>()
    private var current = PaxPassport("D270", "Nuxiums3.1.24", "8.1.12.274(R)", "old", "same", "Inpas")
    private var loseReboot = false
    private var rebootArms = 0
    private var failData = false
    private val port = object : PaxFirmwarePort {
        override suspend fun passport() = current.also { calls += "passport" }
        override suspend fun upload(asset: PaxFirmwareAsset) = asset.name.also { calls += "upload:$it" }
        override suspend fun removeMainApp() { calls += "remove"; current = current.copy(mainApp = null) }
        override suspend fun installBoot(remote: String) { calls += "boot" }
        override suspend fun installOs(remote: String) { calls += "os" }
        override suspend fun installMainApp(remote: String) { calls += "app"; current = current.copy(mainApp = "new") }
        override suspend fun installAppData(remote: String) { calls += "data"; if (failData) throw PaxConnectionLostException("lost") }
        override suspend fun installOption(remote: String) { calls += "opt" }
        override suspend fun reboot() { check(rebootArms > 0); calls += "reboot"; if (loseReboot) throw PaxConnectionLostException("lost") }
        override fun close() { calls += "close" }
    }
    private val host = object : PaxFirmwareHost {
        override fun armReboot() { rebootArms++ }
        override suspend fun open(key: String) = port
        override suspend fun reconnect(previousKey: String, fingerprint: String) = port.also { calls += "reconnect" }
    }
    private fun asset(name: String) = PaxFirmwareAsset(name, File(name), "hash")
    private val bundle = PaxFirmwarePackage(File("unused"), PaxModel.D270, asset("boot"), asset("os"), asset("app"),
        listOf(asset("Config.sys")), expectedOs = "Nuxiums3.1.24", expectedMainApp = "new", expectedBoot = "8.1.12.274(R)")
    private var completed = emptyList<String>()
    private var pending: String? = null
    private fun workflow() = PaxFirmwareWorkflow(host, { _, _ -> }, { done, next -> completed = done; pending = next })

    @Test fun `transport disappears during reboot but same passport permits remaining APP`() = runBlocking {
        loseReboot = true
        workflow().execute(bundle, PaxInstallMode.Full, "usb", "same")
        assertEquals(1, calls.count { it == "reboot" })
        assertEquals(1, rebootArms)
        assertTrue(calls.indexOf("reconnect") < calls.indexOf("app"))
        assertTrue(completed.contains("os"))
    }
    @Test fun `MAINAPP-only never removes or flashes OS boot`() = runBlocking {
        workflow().execute(bundle, PaxInstallMode.MainApp, "usb", "same")
        assertTrue("app" in calls)
        assertFalse(calls.any { it in listOf("remove", "os", "boot", "reboot") })
    }
    @Test fun `remove-only needs no package and verifies absence`() = runBlocking {
        workflow().execute(null, PaxInstallMode.RemoveMainApp, "usb", "same", model = PaxModel.D270)
        assertNull(current.mainApp)
        assertEquals(1, calls.count { it == "remove" })
        assertFalse(calls.any { it.startsWith("upload:") })
    }
    @Test fun `resume retries current file but never confirmed OS boot APP`() = runBlocking {
        failData = true
        assertTrue(runCatching { workflow().execute(bundle, PaxInstallMode.Full, "usb", "same") }.isFailure)
        assertEquals("data:0", pending)
        assertTrue(completed.containsAll(listOf("boot", "os", "app")))
        calls.clear(); failData = false
        workflow().execute(bundle, PaxInstallMode.Full, "usb", "same", completed = completed, pending = pending)
        assertFalse(calls.any { it in listOf("remove", "boot", "os", "app", "reboot") })
        assertEquals(listOf("upload:Config.sys"), calls.filter { it.startsWith("upload:") })
    }
    @Test fun `resume rejects a different terminal before mutation`() = runBlocking {
        current = current.copy(fingerprint = "other")
        assertTrue(runCatching { workflow().execute(bundle, PaxInstallMode.Full, "usb", "same", completed = listOf("remove", "boot", "os", "reboot")) }.isFailure)
        assertFalse(calls.any { it.startsWith("upload:") || it == "remove" })
    }
    @Test fun `unverifiable boot metadata blocks before MAINAPP removal`() = runBlocking {
        assertTrue(runCatching { workflow().execute(bundle.copy(expectedBoot = null), PaxInstallMode.Full, "usb", "same") }.isFailure)
        assertTrue(calls.isEmpty())
    }
    @Test fun `resume will not send appdata into a replaced MAINAPP`() = runBlocking {
        failData = true
        runCatching { workflow().execute(bundle, PaxInstallMode.Full, "usb", "same") }
        current = current.copy(mainApp = "other")
        calls.clear(); failData = false
        assertTrue(runCatching { workflow().execute(bundle, PaxInstallMode.Full, "usb", "same", completed = completed, pending = pending) }.isFailure)
        assertFalse(calls.any { it.startsWith("upload:") })
    }
    @Test fun `completion checkpoint still rechecks final state on resume`() = runBlocking {
        workflow().execute(bundle, PaxInstallMode.MainApp, "usb", "same")
        current = current.copy(mainApp = "changed")
        calls.clear()
        assertTrue(runCatching { workflow().execute(bundle, PaxInstallMode.MainApp, "usb", "same", completed = completed) }.isFailure)
        assertFalse(calls.any { it.startsWith("upload:") })
    }
    @Test fun `Prolin-only does not install MAINAPP or erase it implicitly`() = runBlocking {
        workflow().execute(bundle, PaxInstallMode.Prolin, "usb", "same")
        assertTrue("os" in calls)
        assertFalse(calls.any { it in listOf("app", "remove", "data", "opt") })
    }
}
