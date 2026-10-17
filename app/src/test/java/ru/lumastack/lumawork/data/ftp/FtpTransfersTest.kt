package ru.lumastack.lumawork.data.ftp

import java.io.File
import java.util.UUID
import java.nio.file.Files
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class FtpTransfersTest {
    @get:Rule val temporary = TemporaryFolder()
    private val item = FtpItem("driver.zip", "/drivers/driver.zip", FtpItemType.File)
    @Test fun `active downloads deduplicate and completion survives repository recreation`() = runBlocking {
        val store = MemoryTransferStore(); val gateway = FakeTransferGateway()
        val transfers = FtpTransfers(store, gateway)
        val first = transfers.start("owner", "token", item, FtpTransferAction.Download)
        assertEquals(first.id, transfers.start("owner", "new-token", item, FtpTransferAction.Share).id)
        assertEquals(1, gateway.enqueued)
        gateway.progress = FtpTransferProgress(FtpTransferState.Completed, 123, 123)
        val restored = FtpTransfers(store, gateway)
        assertEquals(FtpTransferState.Completed, restored.refresh("owner").single().state)
        assertTrue(restored.records("other").isEmpty())
        restored.remove("owner", first.id)
        assertEquals(1, restored.records("owner").size) // Saved downloads are not deleted by history cleanup.
        assertEquals(0, gateway.removed)
    }
    @Test fun `screen cancellation during native enqueue still persists returned task id`() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val store = MemoryTransferStore(); val gateway = FakeTransferGateway().apply { onEnqueue = { entered.complete(Unit); release.await() } }
        val transfers = FtpTransfers(store, gateway)
        val task = launch { transfers.start("owner", "token", item, FtpTransferAction.Download) }
        entered.await(); task.cancel(); release.complete(Unit); task.join()
        assertEquals(1L, transfers.records("owner").single().downloadId)
        assertTrue(transfers.records("owner").single().state.isActive)
    }
    @Test fun `cancellation is durable and a late success cannot revive it`() = runBlocking {
        val store = MemoryTransferStore(); val gateway = FakeTransferGateway(); val transfers = FtpTransfers(store, gateway)
        val record = transfers.start("owner", "token", item, FtpTransferAction.Download)
        transfers.cancel("owner", record.id)
        gateway.progress = FtpTransferProgress(FtpTransferState.Completed, 123, 123)
        assertEquals(FtpTransferState.Cancelled, transfers.refresh("owner").single().state)
        assertEquals(1, gateway.removed)
    }
    @Test fun `share is presented only for its owner and only once`() = runBlocking {
        val store = MemoryTransferStore(); val gateway = FakeTransferGateway(); val transfers = FtpTransfers(store, gateway)
        val record = transfers.start("owner", "token", item, FtpTransferAction.Share)
        gateway.progress = FtpTransferProgress(FtpTransferState.Completed, 123, 123)
        transfers.refresh("owner")
        assertNull(transfers.pendingShare("other"))
        assertEquals(record.id, transfers.pendingShare("owner")?.id)
        transfers.acknowledgeShare("owner", record.id)
        assertNull(FtpTransfers(store, gateway).pendingShare("owner"))
    }
    @Test fun `selected-folder completion waits until copy succeeds and never deletes user documents`() = runBlocking {
        val store = MemoryTransferStore(); val gateway = FakeTransferGateway(); val transfers = FtpTransfers(store, gateway)
        val record = transfers.start("owner", "token", item, FtpTransferAction.SaveToFolder, "content://chosen/tree/1")
        gateway.progress = FtpTransferProgress(FtpTransferState.Completed, 123, 123)
        assertTrue(transfers.refresh("owner").single().state.isActive)
        transfers.finishPending()
        assertEquals(FtpTransferState.Completed, transfers.records("owner").single().state)
        assertEquals("content://chosen/document/2", transfers.records("owner").single().documentUri)
        assertTrue(runCatching { transfers.deleteSaved("owner", record.id) }.isFailure)
        assertEquals(1, gateway.removed) // Only staging was removed after the document was saved.
    }
    @Test fun `delete saved file checks owner before removing managed download`() = runBlocking {
        val store = MemoryTransferStore(); val gateway = FakeTransferGateway(); val transfers = FtpTransfers(store, gateway)
        val record = transfers.start("owner", "token", item, FtpTransferAction.Download)
        gateway.progress = FtpTransferProgress(FtpTransferState.Completed, 123, 123); transfers.refresh("owner")
        assertTrue(runCatching { transfers.deleteSaved("other", record.id) }.isFailure)
        assertEquals(0, gateway.removed)
        transfers.deleteSaved("owner", record.id)
        assertTrue(transfers.records("owner").isEmpty()); assertEquals(1, gateway.removed)
    }
    @Test fun `breadcrumbs normalize root and names cannot escape managed storage`() {
        assertEquals(listOf("/", "/one", "/one/two"), ftpBreadcrumbs("/one//two/").map { it.second })
        assertEquals("/one", normalizeFtpPath("/one/two/.."))
        assertEquals("driver.zip", safeFtpFileName("driver.zip"))
        assertFalse(safeFtpFileName("../../name").contains('/'))
    }
    @Test fun `history pruning retains saved files and cleans only old transient transfers`() = runBlocking {
        val store = MemoryTransferStore(); val gateway = FakeTransferGateway()
        val saved = FtpTransfer(UUID.randomUUID().toString(), "owner", "/saved", "saved.zip", false, FtpTransferAction.Download, 1,
            state = FtpTransferState.Completed)
        store.values = listOf(saved) + (1..60).map { FtpTransfer(UUID.randomUUID().toString(), "owner", "/$it", "$it.zip", false,
            FtpTransferAction.Share, it.toLong(), state = FtpTransferState.Failed) }
        val transfers = FtpTransfers(store, gateway)
        transfers.start("owner", "token", item, FtpTransferAction.Download)
        assertEquals(52, transfers.records("owner").size)
        assertTrue(transfers.records("owner").any { it.id == saved.id })
        assertTrue(gateway.removed >= 10)
    }
    @Test fun `failed selected-folder copy is never presented as completed`() = runBlocking {
        val store = MemoryTransferStore(); val gateway = FakeTransferGateway().apply { failSave = true }
        val transfers = FtpTransfers(store, gateway)
        transfers.start("owner", "token", item, FtpTransferAction.SaveToFolder, "content://selected/tree/1")
        gateway.progress = FtpTransferProgress(FtpTransferState.Completed, 123, 123)
        transfers.finishPending()
        assertEquals(FtpTransferState.Failed, transfers.records("owner").single().state)
        assertEquals(0, gateway.removed)
    }
    @Test fun `managed paths reject traversal and symlink escapes`() {
        val root = temporary.newFolder("downloads")
        val outside = temporary.newFolder("outside")
        val record = FtpTransfer(UUID.randomUUID().toString(), "owner", "/file", "file.zip", false, FtpTransferAction.Download, 1)
        val safe = managedFtpFile(root, record)
        assertTrue(safe.canonicalPath.startsWith(root.canonicalPath + File.separator))
        assertTrue(runCatching { managedFtpFile(root, record.copy(fileName = "../outside")) }.isFailure)
        val owner = File(root, ftpOwnerKey("owner")).apply { mkdirs() }
        Files.createSymbolicLink(File(owner, record.id).toPath(), outside.toPath())
        assertTrue(runCatching { managedFtpFile(root, record) }.isFailure)
    }

}
private class MemoryTransferStore : FtpTransferStore {
    var values = emptyList<FtpTransfer>()
    override suspend fun read() = values
    override suspend fun write(records: List<FtpTransfer>) { values = records }
}
private class FakeTransferGateway : FtpTransferGateway {
    var onEnqueue: suspend () -> Unit = {}
    var enqueued = 0; var removed = 0; var failSave = false
    var progress = FtpTransferProgress(FtpTransferState.Downloading, 10, 123)
    override suspend fun enqueue(token: String, record: FtpTransfer): Long { onEnqueue(); return (++enqueued).toLong() }
    override suspend fun progress(record: FtpTransfer) = progress
    override suspend fun remove(record: FtpTransfer) { removed++ }
    override suspend fun file(record: FtpTransfer): File? = File("/managed/${record.id}")
    override suspend fun saveToFolder(record: FtpTransfer, destinationCreated: suspend (String) -> Unit, isActive: suspend () -> Boolean): String {
        if (failSave) throw java.io.IOException("Storage unavailable")
        check(isActive()); destinationCreated("content://chosen/document/2"); return "content://chosen/document/2"
    }
}
