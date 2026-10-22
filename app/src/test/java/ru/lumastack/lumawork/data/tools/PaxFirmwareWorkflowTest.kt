package ru.lumastack.lumawork.data.tools

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PaxFirmwareWorkflowTest {
    private val actions = mutableListOf<String>()
    private fun passport(identity: String = "same", app: String? = null, target: String = "Inpas") =
        PaxPassport("D270", "Nuxiums3.1.24", "8.1.12.274(R)", app, identity, target)
    private val before = Port(passport(app = "3.0.53.012"))
    private val after = Port(passport())
    private val host = object : PaxFirmwareHost {
        override suspend fun open(key: String): PaxFirmwarePort { actions += "open"; return before }
        override suspend fun reconnect(previousKey: String, fingerprint: String): PaxFirmwarePort { actions += "reconnect"; return after }
    }
    private val bundle = PaxFirmwareBundle(File("unused"), asset("boot.zip"), asset("os.zip"), asset("app.aip"),
        listOf(asset("Config.sys"), asset("agent.mod")), listOf(asset("fonts.tar.gz")))
    private fun asset(name: String) = PaxFirmwareAsset(name, File("unused/$name"), "synthetic")
    private fun workflow() = PaxFirmwareWorkflow(host) { _, _ -> }

    @Test fun `Q25 continues saved OS checkpoint when devinfo spells the revision with a dot`() = runBlocking {
        val installed = passport().copy(model = "Q25", os = "prolin-pelican-2.7.181.13278R-Inpas")
        before.current = installed; after.current = installed
        val q25 = bundle.asPackage().copy(model = PaxModel.Q25, boot = null, expectedBoot = null, expectedOs = "2.7.181-r13278")
        val result = workflow().execute(q25, PaxInstallMode.Full, "usb", "same", PaxModel.Q25,
            completed = listOf("remove", "os", "reboot"), pending = "reconnect")
        assertEquals("3.0.65.014", result.mainApp)
        assertTrue(actions.contains("app"))
        assertEquals(1, actions.count { it == "remove" })
        assertFalse(actions.any { it in listOf("os", "boot", "reboot", "upload:os.zip") })
    }

    @Test fun `PAX dotted and release revision formats match without dropping an explicit revision`() {
        assertTrue(paxVersionMatches("prolin-pelican-2.7.181.13278R-Inpas", "2.7.181-r13278"))
        assertTrue(paxVersionMatches("2.7.181-r13278", "prolin-pelican-2.7.181.13278R-Inpas"))
        assertTrue(paxVersionMatches("Nuxiums3.1.45", "3.1.45-r13454"))
        assertTrue(paxVersionMatches("8.1.12.274(R)", "8.1.12.274"))
        assertFalse(paxVersionMatches("prolin-pelican-2.7.181.13277R-Inpas", "2.7.181-r13278"))
        assertFalse(paxVersionMatches("2.7.181-r13277", "2.7.181-r13278"))
        assertFalse(paxVersionMatches("2.7.159.12409R-Inpas", "2.7.181-r13278"))
        assertFalse(paxVersionMatches("8.1.12.273(R)", "8.1.12.274"))
        assertFalse(paxVersionMatches("3.0.65", "3.0.65.014"))
        assertFalse(paxVersionMatches(null, "2.7.181-r13278"))
        assertFalse(paxVersionMatches("unknown", "2.7.181-r13278"))
    }

    @Test fun `full sequence verifies same terminal after reboot and final versions`() = runBlocking {
        val result = workflow().execute(bundle, "usb-old", "same")
        assertEquals("3.0.65.014", result.mainApp)
        assertEquals(listOf("open", "passport", "remove", "passport", "upload:boot.zip", "boot", "upload:os.zip", "os", "reboot", "close",
            "reconnect", "passport", "remove", "upload:app.aip", "app", "upload:Config.sys", "data", "upload:agent.mod", "data",
            "upload:fonts.tar.gz", "opt", "passport", "close"), actions)
    }
    @Test fun `wrong initial identity or target blocks removal`() = runBlocking {
        for (value in listOf(passport(identity = "other"), passport(target = "Other"))) {
            actions.clear(); before.current = value
            assertTrue(runCatching { workflow().execute(bundle, "usb", "same") }.isFailure)
            assertEquals(listOf("open", "passport", "close"), actions)
        }
    }
    @Test fun `another terminal after reboot never receives MAINAPP commands`() = runBlocking {
        after.current = passport(identity = "other")
        assertTrue(runCatching { workflow().execute(bundle, "usb", "same") }.isFailure)
        assertEquals(listOf("reconnect", "passport", "close"), actions.takeLast(3))
        assertFalse(actions.contains("app"))
    }
    @Test fun `wrong OS or boot after reboot blocks second removal and application upload`() = runBlocking {
        for (value in listOf(passport().copy(os = "Nuxiums3.1.21"), passport().copy(boot = "old"))) {
            actions.clear(); after.current = value
            assertTrue(runCatching { workflow().execute(bundle, "usb", "same") }.isFailure)
            assertEquals(1, actions.count { it == "remove" })
            assertFalse(actions.contains("upload:app.aip"))
            assertEquals("close", actions.last())
        }
    }
    @Test fun `failed boot install stops and closes without OS transfer or retries`() = runBlocking {
        before.failBoot = true
        assertTrue(runCatching { workflow().execute(bundle, "usb", "same") }.isFailure)
        assertEquals(listOf("boot", "close"), actions.takeLast(2))
        assertEquals(1, actions.count { it == "boot" })
        assertFalse(actions.contains("os"))
    }
    @Test fun `installed bytes cannot replace final version verification`() = runBlocking {
        after.installWrongVersion = true
        assertTrue(runCatching { workflow().execute(bundle, "usb", "same") }.isFailure)
        assertTrue(actions.contains("opt")); assertEquals("close", actions.last())
    }
    private inner class Port(var current: PaxPassport) : PaxFirmwarePort {
        var failBoot = false
        var installWrongVersion = false
        override suspend fun passport(): PaxPassport { actions += "passport"; return current }
        override suspend fun upload(asset: PaxFirmwareAsset): String { actions += "upload:${asset.name}"; return "/tmp/${asset.name}" }
        override suspend fun removeMainApp() { actions += "remove"; current = current.copy(mainApp = null) }
        override suspend fun installBoot(remote: String) { actions += "boot"; if (failBoot) error("boot failed") }
        override suspend fun installOs(remote: String) { actions += "os" }
        override suspend fun installMainApp(remote: String) { actions += "app"; current = current.copy(mainApp = if (installWrongVersion) "wrong" else "3.0.65.014") }
        override suspend fun installAppData(remote: String) { actions += "data" }
        override suspend fun installOption(remote: String) { actions += "opt" }
        override suspend fun reboot() { actions += "reboot" }
        override fun close() { actions += "close" }
    }
}
