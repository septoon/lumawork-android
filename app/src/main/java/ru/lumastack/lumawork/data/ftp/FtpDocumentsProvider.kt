package ru.lumastack.lumawork.data.ftp

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import java.io.FileNotFoundException
import kotlinx.coroutines.runBlocking
import ru.lumastack.lumawork.LumaWorkApplication

/** Readable FTP downloads in the native Files picker; every query is scoped to the current app account. */
class FtpDocumentsProvider : DocumentsProvider() {
    private val container get() = (context!!.applicationContext as LumaWorkApplication).container
    private val owner get() = container.authRepository.session?.user?.id
    private val transfers get() = container.ftpTransfers
    override fun onCreate() = true
    override fun queryRoots(projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: ROOT_COLUMNS)
        cursor.setNotificationUri(context!!.contentResolver, android.provider.DocumentsContract.buildRootsUri("${context!!.packageName}.ftp.documents"))
        val id = owner ?: return cursor
        cursor.newRow().add(Root.COLUMN_ROOT_ID, ftpOwnerKey(id)).add(Root.COLUMN_DOCUMENT_ID, "root:${ftpOwnerKey(id)}")
            .add(Root.COLUMN_TITLE, "Загрузки FTP • Инженер").add(Root.COLUMN_SUMMARY, "Скачанные файлы")
            .add(Root.COLUMN_FLAGS, Root.FLAG_LOCAL_ONLY).add(Root.COLUMN_MIME_TYPES, "*/*")
        return cursor
    }
    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor = MatrixCursor(projection ?: DOCUMENT_COLUMNS).apply {
        addDocument(this, documentId)
    }
    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        val cursor = MatrixCursor(projection ?: DOCUMENT_COLUMNS)
        val id = requireOwner(parentDocumentId)
        cursor.setNotificationUri(context!!.contentResolver, android.provider.DocumentsContract.buildChildDocumentsUri("${context!!.packageName}.ftp.documents", parentDocumentId))
        if (parentDocumentId != "root:${ftpOwnerKey(id)}") throw FileNotFoundException()
        runBlocking { transfers.records(id) }.filter { it.action == FtpTransferAction.Download && it.state == FtpTransferState.Completed }.forEach {
            if (runBlocking { transfers.savedFile(id, it.id) } != null) addDocument(cursor, "file:${ftpOwnerKey(id)}:${it.id}")
        }
        return cursor
    }
    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean = runCatching {
        val id = requireOwner(parentDocumentId)
        parentDocumentId == "root:${ftpOwnerKey(id)}" && requireOwner(documentId) == id && documentId.startsWith("file:") && record(documentId) != null
    }.getOrDefault(false)
    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("Файл доступен только для чтения.")
        val id = requireOwner(documentId)
        val record = record(documentId) ?: throw FileNotFoundException()
        val file = runBlocking { transfers.savedFile(id, record.id) } ?: throw FileNotFoundException()
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }
    override fun deleteDocument(documentId: String) {
        val id = requireOwner(documentId); val record = record(documentId) ?: throw FileNotFoundException()
        runBlocking { transfers.deleteSaved(id, record.id) }
        context!!.contentResolver.notifyChange(android.provider.DocumentsContract.buildRootsUri("${context!!.packageName}.ftp.documents"), null)
    }
    private fun requireOwner(documentId: String): String {
        val id = owner ?: throw FileNotFoundException()
        if (documentId.split(':').getOrNull(1) != ftpOwnerKey(id)) throw FileNotFoundException()
        return id
    }
    private fun record(documentId: String): FtpTransfer? {
        val id = requireOwner(documentId)
        if (!documentId.startsWith("file:")) return null
        val recordId = documentId.substringAfterLast(':')
        return runBlocking { transfers.records(id) }.firstOrNull { it.id == recordId && it.action == FtpTransferAction.Download && it.state == FtpTransferState.Completed }
    }
    private fun addDocument(cursor: MatrixCursor, documentId: String) {
        val id = requireOwner(documentId)
        if (documentId == "root:${ftpOwnerKey(id)}") {
            cursor.newRow().add(Document.COLUMN_DOCUMENT_ID, documentId).add(Document.COLUMN_DISPLAY_NAME, "Загрузки FTP")
                .add(Document.COLUMN_MIME_TYPE, Document.MIME_TYPE_DIR).add(Document.COLUMN_FLAGS, 0)
            return
        }
        val record = record(documentId) ?: throw FileNotFoundException()
        val file = runBlocking { transfers.savedFile(id, record.id) } ?: throw FileNotFoundException()
        cursor.newRow().add(Document.COLUMN_DOCUMENT_ID, documentId).add(Document.COLUMN_DISPLAY_NAME, record.fileName)
            .add(Document.COLUMN_MIME_TYPE, ftpMimeType(record.fileName, record.folderArchive)).add(Document.COLUMN_SIZE, file.length())
            .add(Document.COLUMN_LAST_MODIFIED, file.lastModified()).add(Document.COLUMN_FLAGS, Document.FLAG_SUPPORTS_DELETE)
    }
    companion object {
        private val ROOT_COLUMNS = arrayOf(Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_SUMMARY, Root.COLUMN_FLAGS, Root.COLUMN_MIME_TYPES)
        private val DOCUMENT_COLUMNS = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS)
    }
}
