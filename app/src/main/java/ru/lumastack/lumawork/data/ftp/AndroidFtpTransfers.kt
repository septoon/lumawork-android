package ru.lumastack.lumawork.data.ftp

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import androidx.core.net.toUri
import java.io.File
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl

class FileFtpTransferStore(context: Context, private val json: Json) : FtpTransferStore {
    private val file = File(context.noBackupFilesDir, "ftp-transfers-v1.json")
    override suspend fun read(): List<FtpTransfer> = withContext(Dispatchers.IO) {
        if (!file.exists()) emptyList() else try { json.decodeFromString<List<FtpTransfer>>(file.readText()) }
        catch (_: Exception) { error("Не удалось прочитать историю загрузок FTP.") }
    }
    override suspend fun write(records: List<FtpTransfer>) = withContext(Dispatchers.IO) {
        val temporary = File.createTempFile("ftp-transfers-", ".tmp", file.parentFile)
        try {
            temporary.writeText(json.encodeToString(records))
            check(temporary.renameTo(file)) { "Не удалось сохранить историю загрузок FTP." }
        } finally { temporary.delete() }
    }
}

class AndroidFtpTransferGateway(private val context: Context, origin: String, private val access: ru.lumastack.lumawork.data.simpleone.SimpleOneAccess) : FtpTransferGateway {
    private val base = origin.trimEnd('/').toHttpUrl().also { require(it.isHttps) }
    private val manager: DownloadManager get() = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    private val root: File get() = File(requireNotNull(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)) { "Хранилище недоступно." }, "ftp-downloads")
    internal val archives = FtpArchiveDownloads(root = { root }, client = archiveHttpClient().newBuilder()
        .addInterceptor(ru.lumastack.lumawork.data.simpleone.SimpleOneAccessInterceptor(access)).build())
    internal suspend fun downloadArchive(record: FtpTransfer, token: String, onProgress: (FtpTransferProgress) -> Unit) = archives.download(record, token, downloadUrl(record), onProgress)
    override suspend fun enqueue(token: String, record: FtpTransfer): Long = withContext(Dispatchers.IO) {
        val soSession = access.require(record.ownerId)
        val target = managedFtpFile(root, record)
        check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs()) { "Не удалось создать папку загрузок FTP." }
        if (record.folderArchive) {
            archives.prepare(record)
            try { FtpArchiveDownloadService.start(context, force = true) }
            catch (failure: Exception) { archives.remove(record); throw failure }
            return@withContext -2L
        }
        val url = downloadUrl(record)
        val request = DownloadManager.Request(url.toString().toUri())
            .addRequestHeader(ru.lumastack.lumawork.data.simpleone.SimpleOneAccess.HEADER, soSession.authKey)
            .addRequestHeader("Authorization", "Bearer $token").addRequestHeader("Cache-Control", "no-cache")
            .setTitle(record.fileName).setDescription("Инженер • FTP")
            .setDestinationUri(Uri.fromFile(target)).setMimeType(ftpMimeType(record.fileName, record.folderArchive))
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        manager.enqueue(request)
    }
    override suspend fun progress(record: FtpTransfer): FtpTransferProgress? = withContext(Dispatchers.IO) {
        if (record.folderArchive) return@withContext archives.progress(record)
        if (record.downloadId < 0) return@withContext null
        manager.query(DownloadManager.Query().setFilterById(record.downloadId))?.use { cursor ->
            if (!cursor.moveToFirst() || !matches(cursor, record)) return@use null
            val bytes = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)).coerceAtLeast(0)
            val total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)).takeIf { it >= 0 }
            val state = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
            when (state) {
                DownloadManager.STATUS_SUCCESSFUL -> if (file(record) != null) FtpTransferProgress(FtpTransferState.Completed, bytes, total)
                    else FtpTransferProgress(FtpTransferState.Failed, bytes, total, "Скачанный файл больше не найден на устройстве.")
                DownloadManager.STATUS_FAILED -> FtpTransferProgress(FtpTransferState.Failed, bytes, total, when (reason) {
                    401, 403 -> "Сессия приложения истекла. Войдите снова и повторите загрузку."
                    404 -> "Файл больше не находится по этому пути. Обновите каталог."
                    DownloadManager.ERROR_INSUFFICIENT_SPACE -> "Недостаточно свободного места на устройстве."
                    else -> "Не удалось скачать файл. Проверьте подключение и повторите загрузку."
                })
                DownloadManager.STATUS_PENDING -> FtpTransferProgress(FtpTransferState.Preparing, bytes, total)
                else -> FtpTransferProgress(FtpTransferState.Downloading, bytes, total)
            }
        }
    }
    override suspend fun file(record: FtpTransfer): File? = withContext(Dispatchers.IO) { managedFtpFile(root, record).takeIf { it.isFile } }
    override suspend fun remove(record: FtpTransfer) = withContext(Dispatchers.IO) {
        val target = managedFtpFile(root, record) // Validate our storage boundary before any removal.
        if (record.folderArchive) archives.remove(record)
        if (record.downloadId >= 0) {
            val matches = manager.query(DownloadManager.Query().setFilterById(record.downloadId))?.use { it.moveToFirst() && matches(it, record) } == true
            if (matches) manager.remove(record.downloadId)
        }
        if (target.exists()) check(target.delete()) { "Не удалось удалить файл с устройства." }
        target.parentFile?.delete() // Only an empty, per-transfer directory.
        Unit
    }
    private fun downloadUrl(record: FtpTransfer): okhttp3.HttpUrl = base.newBuilder()
        .addPathSegments(if (record.folderArchive) "api/v2/ftp/download-folder" else "api/v2/ftp/download")
        .addQueryParameter("path", record.remotePath).build()
    private fun matches(cursor: android.database.Cursor, record: FtpTransfer): Boolean {
        if (cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_URI)) != downloadUrl(record).toString()) return false
        val local = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI))?.toUri()
        if (local == null) return true // A pending task may not have allocated its file yet.
        return local.scheme == "file" && local.path?.let { File(it).canonicalFile == managedFtpFile(root, record) } == true
    }
    override suspend fun saveToFolder(record: FtpTransfer, destinationCreated: suspend (String) -> Unit, isActive: suspend () -> Boolean): String = withContext(Dispatchers.IO) {
        val source = file(record) ?: error("Скачанный файл не найден.")
        val resolver = context.contentResolver
        val tree = requireNotNull(record.treeUri).toUri()
        require(tree.scheme == "content")
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val destination = record.documentUri?.toUri() ?: (DocumentsContract.createDocument(resolver, parent,
            ftpMimeType(record.fileName, record.folderArchive), record.fileName) ?: error("Не удалось создать файл в выбранной папке.")).also { created ->
                try { withContext(NonCancellable) { destinationCreated(created.toString()) } }
                catch (failure: Exception) { runCatching { DocumentsContract.deleteDocument(resolver, created) }; throw failure }
            }
        check(isActive()) { "Загрузка отменена." }
        try {
        resolver.openOutputStream(destination, "wt")?.use { output ->
            source.inputStream().use { input ->
                val buffer = ByteArray(1024 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    check(isActive()) { "Загрузка отменена." }
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
            }
        } ?: error("Папка назначения недоступна.")
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            runCatching { DocumentsContract.deleteDocument(resolver, destination) }
            throw failure
        }
        destination.toString()
    }
}

fun ftpMimeType(name: String, folderArchive: Boolean = false): String = if (folderArchive) "application/zip" else
    MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"
