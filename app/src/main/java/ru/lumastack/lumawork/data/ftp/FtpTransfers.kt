package ru.lumastack.lumawork.data.ftp

import ru.lumastack.lumawork.data.simpleone.SimpleOneAccess
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

@Serializable enum class FtpTransferAction { Download, Share, SaveToFolder }
@Serializable enum class FtpTransferState(val title: String) {
    Preparing("Подготовка"), Downloading("Загрузка"), Completed("Завершено"), Failed("Ошибка"), Cancelled("Отменено");
    val isActive: Boolean get() = this == Preparing || this == Downloading
}
@Serializable data class FtpTransfer(
    val id: String, val ownerId: String, val remotePath: String, val fileName: String,
    val folderArchive: Boolean, val action: FtpTransferAction, val createdAt: Long,
    val downloadId: Long = -1, val state: FtpTransferState = FtpTransferState.Preparing,
    val bytesWritten: Long = 0, val totalBytes: Long? = null, val errorMessage: String? = null,
    val treeUri: String? = null, val documentUri: String? = null, val presentationPending: Boolean = false,
    val isFinalizing: Boolean = false, val downloadComplete: Boolean = false,
) {
    val canCancel: Boolean get() = state.isActive && !isFinalizing
    val progress: Float? get() = totalBytes?.takeIf { it > 0 }?.let { (bytesWritten.toDouble() / it).toFloat().coerceIn(0f, 1f) }
}
@Serializable data class FtpTransferProgress(val state: FtpTransferState, val bytes: Long = 0, val total: Long? = null, val error: String? = null)
interface FtpTransferStore {
    suspend fun read(): List<FtpTransfer>
    suspend fun write(records: List<FtpTransfer>)
}
interface FtpTransferGateway {
    suspend fun enqueue(token: String, record: FtpTransfer): Long
    suspend fun progress(record: FtpTransfer): FtpTransferProgress?
    suspend fun remove(record: FtpTransfer)
    suspend fun file(record: FtpTransfer): File?
    suspend fun saveToFolder(record: FtpTransfer, destinationCreated: suspend (String) -> Unit, isActive: suspend () -> Boolean): String
}

/** Owns persistent transfer state. Network byte transfers belong to Android, independently of screen lifetime. */
class FtpTransfers(private val store: FtpTransferStore, private val gateway: FtpTransferGateway, private val access: SimpleOneAccess = SimpleOneAccess.DenyAll) {
    suspend fun pendingArchives(): List<FtpTransfer> = mutex.withLock { store.read().filter { it.folderArchive && it.state.isActive && !it.downloadComplete && access.isAuthorized(it.ownerId) } }
    suspend fun hasPendingFolderCopies(): Boolean = mutex.withLock { store.read().any { it.action == FtpTransferAction.SaveToFolder && it.state.isActive && access.isAuthorized(it.ownerId) } }
    suspend fun records(owner: String): List<FtpTransfer> = access.protect(owner) { mutex.withLock { store.read().filter { it.ownerId == owner }.sortedByDescending { it.createdAt } } }
    suspend fun start(owner: String, token: String, item: FtpItem, action: FtpTransferAction, treeUri: String? = null): FtpTransfer = mutex.withLock { withContext(NonCancellable) {
        access.require(owner)
        require(owner.isNotBlank() && token.isNotBlank()) { "Войдите в приложение, чтобы скачать файл." }
        require(action != FtpTransferAction.SaveToFolder || treeUri?.startsWith("content://") == true) { "Выберите папку для сохранения." }
        val existing = store.read()
        val path = normalizeFtpPath(item.path)
        existing.firstOrNull { it.ownerId == owner && it.remotePath == path && it.state.isActive }?.let { return@withContext it }
        val name = safeFtpFileName(item.name) + if (item.isDirectory) ".zip" else ""
        val record = FtpTransfer(UUID.randomUUID().toString(), owner, path, name, item.isDirectory, action, System.currentTimeMillis(), treeUri = treeUri)
        persist(existing + record)
        var task: Long? = null
        try {
            task = gateway.enqueue(token, record)
            access.require(owner)
            val enqueued = record.copy(downloadId = task)
            persist(existing + enqueued)
            enqueued
        } catch (error: Exception) {
            if (task != null) gateway.remove(record.copy(downloadId = task))
            persist(existing + record.copy(state = FtpTransferState.Failed, errorMessage = "Не удалось начать загрузку. Попробуйте ещё раз."))
            throw error
        }
    } }
    suspend fun refresh(owner: String): List<FtpTransfer> = access.protect(owner) { mutex.withLock {
        val previous = store.read()
        val updated = previous.map { record ->
            if (record.ownerId != owner) record
            else if (record.state == FtpTransferState.Completed && record.action == FtpTransferAction.Download && gateway.file(record) == null)
                record.copy(state = FtpTransferState.Failed, errorMessage = "Скачанный файл больше не найден на устройстве.")
            else if (!record.state.isActive) record else {
                val progress = gateway.progress(record)
                if (progress == null) record.copy(state = FtpTransferState.Failed, errorMessage = "Загрузка прервана. Скачайте файл ещё раз.")
                else {
                    val state = if (progress.state == FtpTransferState.Completed && record.action == FtpTransferAction.SaveToFolder)
                        FtpTransferState.Downloading else progress.state
                    record.copy(state = state, bytesWritten = progress.bytes, totalBytes = progress.total,
                        errorMessage = progress.error, downloadComplete = progress.state == FtpTransferState.Completed, presentationPending = state == FtpTransferState.Completed && record.action == FtpTransferAction.Share)
                }
            }
        }
        if (updated != previous) persist(updated)
        updated.filter { it.ownerId == owner }.sortedByDescending { it.createdAt }
    } }
    suspend fun cancel(owner: String, id: String) = mutex.withLock {
        val all = store.read(); val record = all.firstOrNull { it.ownerId == owner && it.id == id } ?: return@withLock
        if (!record.canCancel) return@withLock
        persist(all.map { if (it.id == id) it.copy(state = FtpTransferState.Cancelled, errorMessage = null) else it })
        gateway.remove(record)
    }
    suspend fun remove(owner: String, id: String) = mutex.withLock {
        access.require(owner)
        val all = store.read(); val record = all.firstOrNull { it.ownerId == owner && it.id == id } ?: return@withLock
        if (record.state.isActive || (record.action == FtpTransferAction.Download && record.state == FtpTransferState.Completed)) return@withLock
        gateway.remove(record) // Only the managed staging file; never the selected folder's document.
        persist(all.filterNot { it.id == id })
    }
    suspend fun deleteSaved(owner: String, id: String) = mutex.withLock {
        access.require(owner)
        val all = store.read()
        val record = all.firstOrNull { it.id == id && it.ownerId == owner && it.action == FtpTransferAction.Download && it.state == FtpTransferState.Completed }
            ?: error("Скачанный файл не найден.")
        gateway.remove(record)
        persist(all.filterNot { it.id == id })
    }
    suspend fun savedFile(owner: String, id: String): File? = access.protect(owner) { mutex.withLock {
        store.read().firstOrNull { it.id == id && it.ownerId == owner && it.state == FtpTransferState.Completed }?.let { gateway.file(it) }
    } }
    suspend fun pendingShare(owner: String): FtpTransfer? = records(owner).firstOrNull {
        it.action == FtpTransferAction.Share && it.state == FtpTransferState.Completed && it.presentationPending
    }
    suspend fun acknowledgeShare(owner: String, id: String) = update(owner, id) { it.copy(presentationPending = false) }

    /** Called by a system job, including after process recreation. A partially copied document is resumed in place. */
    suspend fun finishPending(): Boolean {
        if (!finalization.tryLock()) return false
        try {
            revokeUnauthorized()
            val owners = mutex.withLock { store.read().map { it.ownerId }.distinct().filter(access::isAuthorized) }
            for (owner in owners) {
                refresh(owner)
                for (record in records(owner).filter { it.action == FtpTransferAction.SaveToFolder && it.state.isActive }) {
                    if (gateway.progress(record)?.state != FtpTransferState.Completed) continue
                    try {
                        update(owner, record.id) { if (it.state.isActive) it.copy(isFinalizing = true) else it }
                        if (records(owner).none { it.id == record.id && it.state.isActive }) continue
                        val uri = gateway.saveToFolder(record,
                            destinationCreated = { document -> update(owner, record.id) { if (it.state.isActive) it.copy(documentUri = document) else it } },
                            isActive = { access.isAuthorized(owner) && records(owner).any { it.id == record.id && it.state.isActive } })
                        access.require(owner)
                        update(owner, record.id) { if (it.state.isActive) it.copy(state = FtpTransferState.Completed, documentUri = uri, errorMessage = null, isFinalizing = false) else it }
                        gateway.remove(record)
                    } catch (error: CancellationException) { throw error }
                    catch (_: Exception) { update(owner, record.id) { if (it.state.isActive) it.copy(state = FtpTransferState.Failed, isFinalizing = false,
                        errorMessage = "Не удалось сохранить файл в выбранную папку. Проверьте доступ и свободное место.") else it } }
                }
            }
            return true
        } finally { finalization.unlock() }
    }
    /** Cancel queued downloads/exports on logout, including jobs restored after process death. */
    suspend fun revokeUnauthorized() = mutex.withLock {
        val previous = store.read()
        val updated = previous.map { record ->
            if (record.state.isActive && !access.isAuthorized(record.ownerId)) {
                gateway.remove(record)
                record.copy(state = FtpTransferState.Cancelled, presentationPending = false, isFinalizing = false)
            } else record
        }
        if (updated != previous) store.write(updated)
    }
    private suspend fun persist(records: List<FtpTransfer>) {
        val retained = records.filter { it.state.isActive || it.presentationPending || (it.action == FtpTransferAction.Download && it.state == FtpTransferState.Completed) }
        val ids = retained.map { it.id }.toSet()
        val history = records.filterNot { it.id in ids }.sortedByDescending { it.createdAt }
        val retryCleanup = mutableListOf<FtpTransfer>()
        for (record in history.drop(50)) {
            try { gateway.remove(record) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { retryCleanup += record }
        }
        store.write(retained + history.take(50) + retryCleanup)
    }
    private suspend fun update(owner: String, id: String, transform: (FtpTransfer) -> FtpTransfer) = mutex.withLock {
        persist(store.read().map { if (it.ownerId == owner && it.id == id) transform(it) else it })
    }
    companion object { private val mutex = Mutex(); private val finalization = Mutex() }
}

fun normalizeFtpPath(raw: String): String {
    require(!raw.contains('\u0000')) { "Некорректный путь FTP." }
    val parts = mutableListOf<String>()
    raw.replace('\\', '/').split('/').forEach { when (it) { "", "." -> Unit; ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex); else -> parts += it } }
    return "/" + parts.joinToString("/")
}
fun ftpBreadcrumbs(path: String): List<Pair<String, String>> {
    val parts = normalizeFtpPath(path).split('/').filter(String::isNotEmpty)
    return listOf("Корень FTP" to "/") + parts.mapIndexed { index, name -> name to "/${parts.take(index + 1).joinToString("/")}" }
}
fun safeFtpFileName(raw: String): String {
    var value = raw.replace(Regex("[\\x00-\\x1f/\\\\:*?\"<>|]"), "_").trim().trimStart('.').ifBlank { "ftp-download" }
    while (value.toByteArray(Charsets.UTF_8).size > 180) value = value.dropLast(1)
    return value
}
internal fun ftpOwnerKey(owner: String): String = MessageDigest.getInstance("SHA-256").digest(owner.toByteArray()).joinToString("") { "%02x".format(it) }
internal fun managedFtpFile(root: File, record: FtpTransfer): File {
    UUID.fromString(record.id)
    require(record.fileName.isNotBlank() && record.fileName !in listOf(".", "..") && record.fileName.none { it == '/' || it == '\\' || it < ' ' } && record.fileName.toByteArray().size <= 240) { "Некорректное имя скачанного файла." }
    val owner = File(root, ftpOwnerKey(record.ownerId)).canonicalFile
    val directory = File(owner, record.id).canonicalFile
    val file = File(directory, record.fileName).canonicalFile
    require(owner.parentFile == root.canonicalFile && directory.parentFile == owner && file.parentFile == directory) { "Файл находится за пределами загрузок FTP." }
    return file
}
