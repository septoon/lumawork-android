package ru.lumastack.lumawork.data.tools

import java.io.ByteArrayInputStream
import java.io.IOException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ToolsRepositoryTest {
    private val files = TestFiles()
    private val store = TestToolsStore()
    private fun repository() = ToolsRepository(files, store) { 100L }

    @Test fun `selection restores by stable FTP id after process restart`() = runBlocking {
        val selected = repository().select("owner", "ftp-id")
        val restored = repository().refresh("owner")
        assertEquals(selected, restored.snapshot.selection)
        assertEquals("ftp-id", selected.file.id)
        assertEquals(64, selected.sha256.length)
        assertEquals(100L, selected.selectedAt)
        assertTrue(restored.snapshot.history.any { it.kind == ToolsEventKind.HashVerified })
    }
    @Test fun `new valid choice replaces previous but failed choice preserves it`() = runBlocking {
        repository().select("owner", "ftp-id")
        files.entries += files.entries.single().copy(id = "second")
        repository().select("owner", "second")
        assertEquals("second", repository().refresh("owner").snapshot.selection?.file?.id)
        assertTrue(runCatching { repository().select("owner", "missing") }.isFailure)
        assertEquals("second", repository().refresh("owner").snapshot.selection?.file?.id)
    }
    @Test fun `deletion invalidates saved choice without deleting FTP data`() = runBlocking {
        repository().select("owner", "ftp-id"); files.entries = emptyList()
        val result = repository().refresh("owner")
        assertNull(result.snapshot.selection)
        assertNotNull(result.notice)
        assertTrue(result.snapshot.history.any { it.kind == ToolsEventKind.SelectionInvalidated })
    }
    @Test fun `same size and date cannot hide modified content`() = runBlocking {
        repository().select("owner", "ftp-id"); files.bytes = "evil".toByteArray()
        val result = repository().refresh("owner")
        assertNull(result.snapshot.selection)
        assertTrue(result.notice!!.contains("измен"))
    }
    @Test fun `changed size date or locator invalidates saved choice`() = runBlocking {
        for (changed in listOf(files.entries.single().copy(size = 5), files.entries.single().copy(modifiedAt = 2),
            files.entries.single().copy(locator = "file:///moved"))) {
            files.entries = listOf(LocalToolFile("ftp-id", "update.zip", 4, 1, 1, "file:///managed"))
            repository().select("owner", "ftp-id"); files.entries = listOf(changed)
            assertNull(repository().refresh("owner").snapshot.selection)
        }
    }
    @Test fun `unreadable or revoked source clears saved choice`() = runBlocking {
        for (failure in listOf(SecurityException("revoked URI"), IOException("unavailable URI"))) {
            files.failure = null; repository().select("owner", "ftp-id"); files.failure = failure
            assertNull(repository().refresh("owner").snapshot.selection)
        }
    }
    @Test fun `partial FTP download cannot be selected`() = runBlocking {
        files.entries = listOf(files.entries.single().copy(downloading = true))
        assertTrue(runCatching { repository().select("owner", "ftp-id") }.isFailure)
        assertNull(store.values["owner"]?.selection)
    }
    @Test fun `unavailable FTP storage invalidates selection and resets selected operation`() = runBlocking {
        repository().select("owner", "ftp-id")
        files.listFailure = SecurityException("storage revoked")
        val result = repository().refresh("owner")
        assertNull(result.snapshot.selection)
        assertEquals(UpdatePhase.Idle, result.snapshot.operation.phase)
        assertNotNull(result.notice)
    }
    @Test fun `manual reset clears selected operation state`() = runBlocking {
        repository().select("owner", "ftp-id")
        repository().clearSelection("owner")
        val result = repository().refresh("owner")
        assertNull(result.snapshot.selection)
        assertEquals(UpdatePhase.Idle, result.snapshot.operation.phase)
    }
    @Test fun `actual byte count must match metadata`() = runBlocking {
        files.bytes = byteArrayOf(1)
        assertTrue(runCatching { repository().select("owner", "ftp-id") }.isFailure)
    }
    @Test fun `selection and history are isolated by account`() = runBlocking {
        repository().select("owner", "ftp-id")
        assertNull(repository().refresh("other").snapshot.selection)
        assertTrue(repository().refresh("other").snapshot.history.isEmpty())
    }
    @Test fun `saved selection never grants compatibility to another terminal`() = runBlocking {
        repository().select("owner", "ftp-id")
        for (device in listOf("p8", "mf960", "unknown")) {
            assertFalse(repository().checkForTransfer("owner", "ftp-id", device).canTransfer)
        }
        assertEquals("ftp-id", repository().refresh("owner").snapshot.selection?.file?.id)
    }
    @Test fun `restart interrupts active copying and never resumes USB automatically`() = runBlocking {
        store.values["owner"] = ToolsSnapshot(operation = UpdateOperation(UpdatePhase.Transferring, "dev", 100, 25))
        val result = repository().refresh("owner")
        assertEquals(UpdatePhase.Interrupted, result.snapshot.operation.phase)
        assertFalse(result.snapshot.operation.canDisconnect)
    }
    @Test fun `history binding preserves real SimpleOne identifiers locally`() = runBlocking {
        repository().select("owner", "ftp-id")
        val event = repository().refresh("owner").snapshot.history.first()
        repository().bindRequest("owner", event.id, ToolRequestRef("sys123", "INC123"))
        assertEquals("sys123", repository().refresh("owner").snapshot.history.first().request?.sysId)
        assertTrue(repository().refresh("other").snapshot.history.isEmpty())
    }
}
private class TestToolsStore : ToolsStore {
    val values = mutableMapOf<String, ToolsSnapshot>()
    override suspend fun read(owner: String): ToolsSnapshot = values[owner]?.let { Json.decodeFromString<ToolsSnapshot>(Json.encodeToString(it)) } ?: ToolsSnapshot()
    override suspend fun write(owner: String, snapshot: ToolsSnapshot) { values[owner] = snapshot }
}
private class TestFiles : LocalToolFiles {
    var entries = listOf(LocalToolFile("ftp-id", "update.zip", 4, 1, 1, "file:///managed"))
    var bytes = "good".toByteArray()
    var failure: Exception? = null
    var listFailure: Exception? = null
    override suspend fun list(owner: String) = listFailure?.let { throw it } ?: entries
    override suspend fun open(owner: String, id: String) = failure?.let { throw it } ?: ByteArrayInputStream(bytes)
}
