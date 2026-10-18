package ru.lumastack.lumawork.data.tools

import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import ru.lumastack.lumawork.data.ftp.*

class FtpLocalToolFilesTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun `adapter exposes same managed FTP bytes without enqueue or copying`() = runBlocking {
        val file = temporary.newFile("update.zip").apply { writeText("firmware") }
        val store = LocalFtpStore(listOf(record("owner"), record("other")))
        val gateway = LocalFtpGateway(file)
        val ftp = FtpTransfers(store, gateway)
        val source = FtpLocalToolFiles(ftp)
        val listed = source.list("owner")
        assertEquals(1, listed.size)
        assertEquals(file.toURI().toString(), listed.single().locator)
        assertEquals("firmware", source.open("owner", listed.single().id).bufferedReader().use { it.readText() })
        assertTrue(runCatching { source.open("other", listed.single().id) }.isFailure)
        assertEquals(0, gateway.enqueued)
    }
    @Test fun `share staging and user folder exports are not mistaken for managed downloads`() = runBlocking {
        val file = temporary.newFile("update.zip").apply { writeText("firmware") }
        val entries = listOf(record("owner"), record("owner").copy(action = FtpTransferAction.Share),
            record("owner").copy(action = FtpTransferAction.SaveToFolder))
        assertEquals(1, FtpLocalToolFiles(FtpTransfers(LocalFtpStore(entries), LocalFtpGateway(file))).list("owner").size)
    }
    @Test fun `partial download appears with downloading flag and cannot be opened`() = runBlocking {
        val file = temporary.newFile("partial")
        val entry = record("owner").copy(state = FtpTransferState.Downloading)
        val source = FtpLocalToolFiles(FtpTransfers(LocalFtpStore(listOf(entry)), LocalFtpGateway(file)))
        assertTrue(source.list("owner").single().downloading)
        assertTrue(runCatching { source.open("owner", entry.id) }.isFailure)
    }
    @Test fun `deleted managed FTP file is removed from tools listing`() = runBlocking {
        val file = temporary.newFile("deleted").apply { delete() }
        val source = FtpLocalToolFiles(FtpTransfers(LocalFtpStore(listOf(record("owner"))), LocalFtpGateway(file)))
        assertTrue(source.list("owner").isEmpty())
    }
    private fun record(owner: String) = FtpTransfer(UUID.randomUUID().toString(), owner, "/firmware/update.zip", "update.zip", false,
        FtpTransferAction.Download, 1, state = FtpTransferState.Completed)
}
private class LocalFtpStore(var records: List<FtpTransfer>) : FtpTransferStore {
    override suspend fun read() = records
    override suspend fun write(records: List<FtpTransfer>) { this.records = records }
}
private class LocalFtpGateway(private val file: File) : FtpTransferGateway {
    var enqueued = 0
    override suspend fun enqueue(token: String, record: FtpTransfer) = (++enqueued).toLong()
    override suspend fun file(record: FtpTransfer): File? = file.takeIf { it.isFile }
    override suspend fun progress(record: FtpTransfer) = FtpTransferProgress(FtpTransferState.Downloading, 1, 100)
    override suspend fun remove(record: FtpTransfer) { error("Tools must not remove FTP files") }
    override suspend fun saveToFolder(record: FtpTransfer, destinationCreated: suspend (String) -> Unit, isActive: suspend () -> Boolean): String = error("not used")
}
