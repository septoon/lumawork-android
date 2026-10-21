package ru.lumastack.lumawork.data.tools

import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ru.lumastack.lumawork.data.ftp.FtpTransferAction
import ru.lumastack.lumawork.data.ftp.FtpTransferState
import ru.lumastack.lumawork.data.ftp.FtpTransfers

interface LocalToolFiles {
    suspend fun list(owner: String): List<LocalToolFile>
    suspend fun open(owner: String, id: String): InputStream
}
/** Shares the exact managed FTP records and files; no second download or copied firmware directory. */
class FtpLocalToolFiles(private val transfers: FtpTransfers) : LocalToolFiles {
    override suspend fun list(owner: String): List<LocalToolFile> = transfers.refresh(owner).mapNotNull { record ->
        if (record.action != FtpTransferAction.Download) return@mapNotNull null
        if (record.state.isActive) return@mapNotNull LocalToolFile(record.id, record.fileName, record.totalBytes ?: record.bytesWritten,
            0, record.createdAt, "ftp:${record.id}", downloading = true, remotePath = record.remotePath)
        if (record.state != FtpTransferState.Completed) return@mapNotNull null
        transfers.savedFile(owner, record.id)?.let { file ->
            LocalToolFile(record.id, record.fileName, file.length(), file.lastModified(), record.createdAt, file.toURI().toString(), remotePath = record.remotePath)
        }
    }
    override suspend fun open(owner: String, id: String): InputStream =
        requireNotNull(transfers.savedFile(owner, id)) { "Файл FTP отсутствует или недоступен." }.inputStream()
}
interface ToolsStore {
    suspend fun read(owner: String): ToolsSnapshot
    suspend fun write(owner: String, snapshot: ToolsSnapshot)
}
data class ToolsFilesResult(val files: List<LocalToolFile>, val snapshot: ToolsSnapshot, val notice: String? = null)

class ToolsRepository(
    private val files: LocalToolFiles, private val store: ToolsStore,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private val recoveredOwners = mutableSetOf<String>()
    suspend fun refresh(owner: String, verifyHash: Boolean = true): ToolsFilesResult = withContext(Dispatchers.IO) { mutex.withLock {
        var snapshot = read(owner)
        val entries = try { files.list(owner) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            val message = "Хранилище FTP недоступно. Сохранённый выбор сброшен. Проверьте доступ и загрузите файл через FTP."
            val invalidated = snapshot.withoutSelection().event(ToolsEventKind.SelectionInvalidated, message, snapshot.selection?.file?.id)
            store.write(owner, invalidated)
            return@withLock ToolsFilesResult(emptyList(), invalidated, message)
        }
        var notice: String? = null
        snapshot.selection?.let { selected ->
            try {
                val current = entries.firstOrNull { it.id == selected.file.id && !it.downloading }
                check(current == selected.file && (!verifyHash || fingerprint(owner, current) == selected.sha256)) {
                    "Ранее выбранный файл изменён, удалён или недоступен. Выберите файл заново."
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                notice = "Ранее выбранный файл изменён, удалён или недоступен. Выберите другой файл или загрузите его через FTP."
                snapshot = snapshot.withoutSelection().event(ToolsEventKind.SelectionInvalidated, notice, selected.file.id)
                store.write(owner, snapshot)
            }
        }
        ToolsFilesResult(entries, snapshot, notice)
    } }
    suspend fun select(owner: String, id: String): SelectedFirmware = withContext(Dispatchers.IO) { mutex.withLock {
        val file = files.list(owner).firstOrNull { it.id == id && !it.downloading }
            ?: error("Файл отсутствует или ещё загружается. Загрузите файл через FTP.")
        val sha = fingerprint(owner, file)
        check(files.list(owner).firstOrNull { it.id == id } == file) { "Файл изменён во время проверки. Повторите выбор." }
        val selection = SelectedFirmware(file, sha, now())
        store.write(owner, read(owner).copy(selection = selection, operation = UpdateOperation(UpdatePhase.Selected))
            .event(ToolsEventKind.FileSelected, "${file.name}; совместимость с терминалом не подтверждена", id)
            .event(ToolsEventKind.HashVerified, "SHA-256: $sha; соответствие официальному пакету не подтверждено", id))
        selection
    } }
    suspend fun clearSelection(owner: String) = withContext(Dispatchers.IO) { mutex.withLock {
        val state = read(owner)
        store.write(owner, state.withoutSelection().event(ToolsEventKind.SelectionInvalidated, "Выбор сброшен пользователем", state.selection?.file?.id))
    } }
    /** Always recheck file bytes. No terminal in the supplied docs has an approved write protocol/compatibility manifest. */
    suspend fun checkForTransfer(owner: String, id: String, deviceKey: String?): FirmwareCheck {
        val current = refresh(owner)
        val selected = current.snapshot.selection
        val message = when {
            selected?.file?.id != id -> "Файл изменён или недоступен. Выберите файл заново."
            deviceKey == null -> "Подключите терминал и разрешите USB-доступ."
            else -> "Совместимость файла и протокол записи для подключённого терминала не подтверждены. Передача заблокирована."
        }
        record(owner, ToolsEventKind.TransferBlocked, message, id, deviceKey)
        return FirmwareCheck(message = message)
    }
    suspend fun record(owner: String, kind: ToolsEventKind, detail: String, fileId: String? = null, deviceKey: String? = null) =
        withContext(Dispatchers.IO) { mutex.withLock {
            var state = read(owner)
            if (kind == ToolsEventKind.UsbDetached && state.operation.deviceKey == deviceKey)
                state = state.copy(operation = state.operation.detached())
            store.write(owner, state.event(kind, detail, fileId, deviceKey))
        } }
    suspend fun bindRequest(owner: String, eventId: String, request: ToolRequestRef?) = withContext(Dispatchers.IO) { mutex.withLock {
        require(request == null || (request.sysId.isNotBlank() && request.number.isNotBlank()))
        val state = read(owner)
        store.write(owner, state.copy(history = state.history.map { if (it.id == eventId) it.copy(request = request) else it }))
    } }
    suspend fun recordInstallation(owner: String, installation: PaxInstallState) = withContext(Dispatchers.IO) { mutex.withLock {
        require(installation.owner == owner)
        if (installation.operationId.isBlank() || installation.phase !in listOf(PaxInstallPhase.Complete, PaxInstallPhase.Failed, PaxInstallPhase.Interrupted)) return@withLock
        val state = read(owner)
        if (state.history.any { it.id == installation.operationId }) return@withLock
        val kind = when (installation.phase) {
            PaxInstallPhase.Complete -> ToolsEventKind.InstallationConfirmed
            PaxInstallPhase.Interrupted -> ToolsEventKind.Interrupted
            else -> ToolsEventKind.InstallationUnknown
        }
        val event = ToolsEvent(installation.operationId, installation.updatedAt, kind,
            "PAX D270 · ${installation.step?.title ?: "Подготовка"}. ${installation.detail}")
        store.write(owner, state.copy(history = (listOf(event) + state.history).take(500)))
    } }
    private suspend fun read(owner: String): ToolsSnapshot {
        require(owner.isNotBlank())
        val state = store.read(owner)
        if (owner in recoveredOwners) return state
        val operation = state.operation.recover()
        val recovered = if (operation != state.operation) state.copy(operation = operation).event(
            if (operation.phase == UpdatePhase.Interrupted) ToolsEventKind.Interrupted else ToolsEventKind.InstallationUnknown,
            "Приложение перезапущено. USB-обмен не возобновлён; повторно проверьте терминал и файл.") else state
        if (recovered != state) store.write(owner, recovered)
        recoveredOwners += owner
        return recovered
    }
    private suspend fun fingerprint(owner: String, file: LocalToolFile): String {
        val hash = MessageDigest.getInstance("SHA-256")
        var count = 0L
        files.open(owner, file.id).use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = input.read(buffer)
                if (read < 0) break
                count += read; check(count <= file.size) { "Размер файла изменён." }
                hash.update(buffer, 0, read)
            }
        }
        check(count == file.size && count > 0) { "Файл пуст или загружен не полностью." }
        return hash.digest().joinToString("") { "%02x".format(it) }
    }
    private fun ToolsSnapshot.withoutSelection() = copy(selection = null,
        operation = if (operation.phase == UpdatePhase.Selected) UpdateOperation() else operation)
    private fun ToolsSnapshot.event(kind: ToolsEventKind, detail: String, fileId: String? = null, deviceKey: String? = null) = copy(
        history = (listOf(ToolsEvent(UUID.randomUUID().toString(), now(), kind, detail, fileId, deviceKey)) + history).take(500))
}
