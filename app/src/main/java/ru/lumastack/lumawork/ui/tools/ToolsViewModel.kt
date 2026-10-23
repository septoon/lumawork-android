package ru.lumastack.lumawork.ui.tools

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.lumastack.lumawork.data.ftp.*
import kotlinx.coroutines.delay
import android.provider.OpenableColumns
import ru.lumastack.lumawork.data.tools.*
import java.io.InputStream

data class ToolsUiState(
    val files: List<LocalToolFile> = emptyList(), val snapshot: ToolsSnapshot = ToolsSnapshot(),
    val loading: Boolean = true, val checking: Boolean = false, val notice: String? = null,
    val inspected: LocalToolFile? = null, val checkMessage: String? = null,
    val firmware: PaxFirmwarePackage? = null, val firmwareBusy: Boolean = true, val firmwareError: String? = null,
    val pinpadExpectedOs: String = "",
    val paxModel: PaxModel = PaxModel.D270, val installMode: PaxInstallMode = PaxInstallMode.Full,
    val installation: PaxInstallState = PaxInstallState(), val globalInstallBusy: Boolean = false,
    val library: PaxLibrarySnapshot = PaxLibrarySnapshot(), val archiveDownload: FtpTransfer? = null,
    val archivePicker: PaxArchiveKind? = null, val ftpPath: String = "", val ftpItems: List<FtpItem> = emptyList(),
    val ftpLoading: Boolean = false, val ftpError: String? = null,
    val mtkArchivePicker: Boolean = false, val mtkDownload: FtpTransfer? = null,
)
class ToolsViewModel(context: Context, private val ftp: FtpRepository, private val owner: String, private var token: String) : ViewModel() {
    private val transfers = requireNotNull(ftp.transfers)
    private val context = context.applicationContext as android.app.Application
    private val localFiles = FtpLocalToolFiles(transfers)
    private val repository = ToolsRepository(localFiles, FileToolsStore(context))
    val mtk = MtkFirmwareController(context, owner, viewModelScope) { result ->
        try { repository.recordMtkInspection(owner, result); refresh(verifyHash = false) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { notice("Результат диагностики P8 доступен в карточке, но не сохранён в общей истории.") }
    }
    private var mtkDownloadWork: Job? = null
    private val firmwareStore = PaxFirmwareStore(context)
    private val library = PaxFirmwareLibrary(firmwareStore.ownerDirectory(owner))
    private var downloadWork: Job? = null
    private var catalogWork: Job? = null
    private val mutable = MutableStateFlow(ToolsUiState())
    val state = mutable.asStateFlow()
    private val preparedPackages = mutableMapOf<PaxModel, PaxFirmwarePackage>()
    private val pinpadExpectations = mutableMapOf<String, String>()
    private var work: Job? = null
    private var pendingRefresh = false
    private var pendingHashCheck = false
    val usb = UsbToolsMonitor(context, viewModelScope) { kind, detail, key -> viewModelScope.launch {
        try { repository.record(owner, kind, detail, deviceKey = key); refresh(verifyHash = false) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { notice("Не удалось сохранить событие USB в историю.") }
    } }
    init {
        viewModelScope.launch { terminalOperationGate.owner.collect { lease -> mutable.update { it.copy(globalInstallBusy = lease != null) } } }
        viewModelScope.launch {
            try {
                val saved = withContext(Dispatchers.IO) { firmwareStore.read(owner) }
                val bundle = saved.bundleDirectory?.let { path -> withContext(Dispatchers.IO) {
                    runCatching { readPaxPackage(firmwareStore.checkedBundleDirectory(owner, path), saved.model) }.getOrNull()
                } }
                bundle?.let {
                    preparedPackages[it.model] = it
                    if (it.model == PaxModel.S210) pinpadExpectations[it.directory.path] = saved.expectedPinpadOs ?: expectedPinpadOs(it.os).orEmpty()
                }
                val index = withContext(Dispatchers.IO) {
                    if (bundle != null) runCatching { library.adopt(bundle) }
                    library.read()
                }
                for (model in PaxModel.entries) withContext(Dispatchers.IO) { runCatching { library.bundle(model) }.getOrNull() }?.let { preparedPackages[model] = it }
                val restoredModel = index.pending?.model ?: saved.model
                val selected = preparedPackages[restoredModel] ?: bundle?.takeIf { it.model == restoredModel }
                mutable.update { it.copy(library = index, firmware = selected, pinpadExpectedOs = index.selected(restoredModel, PaxArchiveKind.Firmware)?.let { index.pinpadVersions[it] } ?: saved.expectedPinpadOs ?: expectedPinpadOs(selected?.os).orEmpty(), installation = saved, paxModel = restoredModel,
                    installMode = if (restoredModel == PaxModel.S210 && saved.mode == PaxInstallMode.RemoveMainApp) PaxInstallMode.Full else saved.mode) }
                repository.recordInstallation(owner, saved)
            } catch (_: Exception) { notice("Не удалось прочитать подготовленный комплект PAX. Выберите ZIP, RAR или 7z заново.") }
            finally { mutable.update { it.copy(firmwareBusy = false) } }
            state.value.library.pending?.let(::watchDownload)
            PaxFirmwareRuntime.state.collect { current ->
                mutable.update { it.copy(globalInstallBusy = terminalOperationGate.owner.value != null,
                    installation = if (current.owner == owner) current else it.installation) }
                if (current.owner == owner && current.operationId.isNotBlank() && !current.active) {
                    try { repository.recordInstallation(owner, current); refresh(verifyHash = false) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { notice("Результат установки доступен в карточке, но не сохранён в общей истории.") }
                }
            }
        }
    }
    fun acknowledgeFirmwareCompletion() {
        val completed = state.value.installation.takeIf { it.awaitingCompletionAcknowledgement } ?: return
        mutable.update { it.copy(installation = it.installation.copy(completionAcknowledged = true)) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { firmwareStore.acknowledgeCompletion(owner, completed.operationId) }
                val current = PaxFirmwareRuntime.state.value
                if (current.owner == owner && current.operationId == completed.operationId && current.phase == PaxInstallPhase.Complete)
                    PaxFirmwareRuntime.mutable.value = current.copy(completionAcknowledged = true)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { notice("Не удалось сохранить подтверждение завершения.") }
        }
    }
    fun updateToken(value: String) { token = value }
    fun importMtkAgent(uri: Uri) = mtk.importAgent { requireNotNull(context.contentResolver.openInputStream(uri)) { "Не удалось открыть агент." } }
    fun importMtkFirmware(uri: Uri) = mtk.preparePackage { requireNotNull(context.contentResolver.openInputStream(uri)) { "Не удалось открыть ZIP." } }
    fun prepareMtkFromFtp(id: String) = mtk.preparePackage { requireNotNull(transfers.savedFile(owner, id)) { "Файл FTP недоступен." }.inputStream() }
    fun openMtkArchivePicker() {
        if (state.value.globalInstallBusy || mtk.state.value.loading || state.value.mtkDownload != null) return
        mutable.update { it.copy(mtkArchivePicker = true, archivePicker = null, ftpItems = emptyList(), ftpError = null) }
        navigateArchiveFtp(MTK_P8_FTP_PATH)
    }
    fun downloadMtkArchive(item: FtpItem) {
        if (!state.value.mtkArchivePicker || state.value.mtkDownload != null || item.isDirectory || item.name != MTK_P8_ARCHIVE ||
            normalizeFtpPath(item.path) != "$MTK_P8_FTP_PATH/$MTK_P8_ARCHIVE") return
        closeArchivePicker()
        mtkDownloadWork = viewModelScope.launch {
            try {
                val started = transfers.start(owner, token, item, FtpTransferAction.Download)
                mutable.update { it.copy(mtkDownload = started) }
                while (true) {
                    val current = transfers.refresh(owner).firstOrNull { it.id == started.id } ?: error("Загрузка P8 не найдена.")
                    mutable.update { it.copy(mtkDownload = current) }
                    when (current.state) {
                        FtpTransferState.Completed -> { prepareMtkFromFtp(current.id); break }
                        FtpTransferState.Failed, FtpTransferState.Cancelled -> error(current.errorMessage ?: "Загрузка P8 отменена.")
                        else -> delay(2000)
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { notice(failure.message ?: "Не удалось скачать комплект P8.") }
            finally { mutable.update { it.copy(mtkDownload = null) }; refresh(verifyHash = false) }
        }
    }
    fun cancelMtkDownload() { state.value.mtkDownload?.let { record -> viewModelScope.launch { transfers.cancel(owner, record.id) } } }
    fun importFirmware(uri: Uri, kind: PaxArchiveKind = PaxArchiveKind.Firmware) {
        val name = runCatching { context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } }.getOrNull() ?: "Архив из файла"
        prepareFirmware(kind, name) { requireNotNull(context.contentResolver.openInputStream(uri)) { "Не удалось открыть архив." } }
    }
    fun prepareFirmwareFromFtp(id: String, kind: PaxArchiveKind = PaxArchiveKind.Firmware) {
        val file = state.value.files.firstOrNull { it.id == id } ?: return
        prepareFirmware(kind, file.name, file.remotePath) { localFiles.open(owner, id) }
    }
    private fun prepareFirmware(kind: PaxArchiveKind, name: String, sourcePath: String? = null, open: suspend () -> InputStream) {
        if (state.value.firmwareBusy || PaxFirmwareRuntime.state.value.active || state.value.paxModel == PaxModel.D230) return
        val selectedModel = state.value.paxModel
        mutable.update { it.copy(firmwareBusy = true, firmwareError = null, inspected = null, checkMessage = null) }
        viewModelScope.launch {
            try {
                val index = withContext(Dispatchers.IO) { open().use { library.importArchive(it, selectedModel, kind, name, sourcePath) } }
                applyLibrary(index, selectedModel)
                notice("${kind.title} для ${selectedModel.title} сохранена. Версию можно переключить в списке.")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { firmwareFailure(failure) }
            finally { mutable.update { it.copy(firmwareBusy = false) } }
        }
    }
    private suspend fun applyLibrary(index: PaxLibrarySnapshot, model: PaxModel) {
        val bundle = withContext(Dispatchers.IO) { library.bundle(model) }
        if (bundle != null) preparedPackages[model] = bundle
        mutable.update { current ->
            if (current.paxModel != model) current.copy(library = index)
            else current.copy(library = index, firmware = bundle, firmwareError = null,
                pinpadExpectedOs = if (model == PaxModel.S210) bundle?.let { index.selected(model, PaxArchiveKind.Firmware)?.let { id -> index.pinpadVersions[id] } ?: pinpadExpectations[it.directory.path] ?: expectedPinpadOs(it.os) }.orEmpty() else current.pinpadExpectedOs)
        }
    }
    fun selectArchive(id: String) {
        if (state.value.firmwareBusy || PaxFirmwareRuntime.state.value.active) return
        val entry = state.value.library.archives.firstOrNull { it.id == id && it.model == state.value.paxModel } ?: return
        mutable.update { it.copy(firmwareBusy = true, firmwareError = null) }
        viewModelScope.launch {
            try { applyLibrary(withContext(Dispatchers.IO) { library.select(id) }, entry.model) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { firmwareFailure(failure) }
            finally { mutable.update { it.copy(firmwareBusy = false) } }
        }
    }
    fun openArchivePicker(kind: PaxArchiveKind) {
        if (state.value.firmwareBusy || state.value.globalInstallBusy || state.value.paxModel == PaxModel.D230) return
        if (kind == PaxArchiveKind.Prolin && state.value.paxModel == PaxModel.S210) return
        mutable.update { it.copy(archivePicker = kind, ftpItems = emptyList(), ftpError = null) }
        navigateArchiveFtp(kind.ftpPathFor(state.value.paxModel))
    }
    fun closeArchivePicker() { catalogWork?.cancel(); mutable.update { it.copy(archivePicker = null, mtkArchivePicker = false, ftpLoading = false) } }
    fun navigateArchiveFtp(path: String) {
        val root = state.value.archivePicker?.ftpPathFor(state.value.paxModel) ?: if (state.value.mtkArchivePicker) MTK_P8_FTP_PATH else return
        val normalized = normalizeFtpPath(path)
        if (normalized != root && !normalized.startsWith(root + "/")) return
        catalogWork?.cancel()
        val samePath = state.value.ftpPath == normalized
        mutable.update { it.copy(ftpPath = normalized, ftpItems = if (samePath) it.ftpItems else emptyList(), ftpLoading = true, ftpError = null) }
        catalogWork = viewModelScope.launch {
            try {
                ftp.read(owner)?.directories?.get(normalized)?.let { cached -> mutable.update { it.copy(ftpItems = cached) } }
                val snapshot = ftp.load(owner, token, normalized)
                mutable.update { it.copy(ftpItems = snapshot.directories[normalized].orEmpty(), ftpLoading = false) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { mutable.update { it.copy(ftpLoading = false, ftpError = failure.message ?: "Не удалось открыть FTP.") } }
        }
    }
    fun downloadArchive(item: FtpItem) {
        val kind = state.value.archivePicker ?: return
        val target = state.value.paxModel
        if (state.value.firmwareBusy || state.value.globalInstallBusy || !item.name.isPaxArchiveName() || item.isDirectory) return
        val path = normalizeFtpPath(item.path)
        if (!path.startsWith(kind.ftpPathFor(target) + "/")) return
        mutable.update { it.copy(firmwareBusy = true, firmwareError = null) }
        viewModelScope.launch {
            try {
                val record = transfers.start(owner, token, item, FtpTransferAction.Download)
                val pending = PaxArchiveDownload(record.id, target, kind)
                val index = withContext(Dispatchers.IO) { library.pending(pending) }
                mutable.update { it.copy(library = index, archivePicker = null, archiveDownload = record) }
                watchDownload(pending)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { firmwareFailure(failure); mutable.update { it.copy(firmwareBusy = false) } }
        }
    }
    private fun watchDownload(pending: PaxArchiveDownload) {
        downloadWork?.cancel()
        mutable.update { it.copy(firmwareBusy = true) }
        downloadWork = viewModelScope.launch {
            try {
                while (true) {
                    val record = transfers.refresh(owner).firstOrNull { it.id == pending.transferId } ?: error("Загрузка архива не найдена. Выберите его на FTP заново.")
                    mutable.update { it.copy(archiveDownload = record) }
                    when (record.state) {
                        FtpTransferState.Completed -> {
                            mutable.update { it.copy(archiveDownload = null) }
                            withContext(Dispatchers.IO) { localFiles.open(owner, record.id).use {
                                library.importArchive(it, pending.model, pending.kind, record.fileName, record.remotePath)
                            } }
                            val index = withContext(Dispatchers.IO) { library.pending(null) }
                            applyLibrary(index, pending.model)
                            notice("${pending.kind.title} для ${pending.model.title} скачана и подготовлена.")
                            break
                        }
                        FtpTransferState.Failed, FtpTransferState.Cancelled -> error(record.errorMessage ?: "Загрузка отменена.")
                        else -> delay(2_000)
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                runCatching { withContext(Dispatchers.IO) { library.pending(null) } }.getOrNull()?.let { index -> mutable.update { it.copy(library = index) } }
                firmwareFailure(failure)
            } finally { mutable.update { it.copy(firmwareBusy = false, archiveDownload = null) } }
        }
    }
    fun cancelArchiveDownload() {
        val record = state.value.archiveDownload?.takeIf { it.canCancel } ?: return
        viewModelScope.launch { transfers.cancel(owner, record.id) }
    }
    private fun firmwareFailure(failure: Exception) {
        val message = failure.message ?: "Не удалось подготовить архив. Предыдущий комплект сохранён."
        mutable.update { it.copy(firmwareError = message) }; notice(message)
    }
    fun selectPaxModel(model: PaxModel) {
        if (state.value.firmwareBusy || PaxFirmwareRuntime.state.value.active) return
        val cached = preparedPackages[model]
        mutable.update { it.copy(paxModel = model, firmware = cached, firmwareError = null,
            pinpadExpectedOs = if (model == PaxModel.S210) cached?.let { bundle ->
                state.value.library.selected(model, PaxArchiveKind.Firmware)?.let { id -> state.value.library.pinpadVersions[id] } ?: pinpadExpectations[bundle.directory.path] ?: expectedPinpadOs(bundle.os)
            }.orEmpty() else it.pinpadExpectedOs,
            installMode = if (model == PaxModel.S210 && it.installMode == PaxInstallMode.RemoveMainApp) PaxInstallMode.Full else it.installMode) }
    }
    fun selectInstallMode(mode: PaxInstallMode) {
        if (state.value.firmwareBusy || PaxFirmwareRuntime.state.value.active) return
        if (state.value.paxModel == PaxModel.D230 || state.value.paxModel == PaxModel.S210 && mode == PaxInstallMode.RemoveMainApp) return
        mutable.update { it.copy(installMode = mode) }
    }
    fun setPinpadExpectedOs(value: String) {
        if (state.value.firmwareBusy || PaxFirmwareRuntime.state.value.active) return
        val clean = value.trim()
        if (clean.length <= 96 && clean.all { it.isLetterOrDigit() || it in ".-_()" }) {
            state.value.firmware?.takeIf { it.model == PaxModel.S210 }?.let { pinpadExpectations[it.directory.path] = clean }
            mutable.update { it.copy(pinpadExpectedOs = clean) }
            state.value.library.selected(PaxModel.S210, PaxArchiveKind.Firmware)?.let { id -> viewModelScope.launch {
                try { val index = withContext(Dispatchers.IO) { library.pinpadVersion(id, clean) }; mutable.update { it.copy(library = index) } }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { notice("Не удалось сохранить ожидаемую версию OS S210.") }
            } }
        }
    }
    fun retryConnection() { PaxFirmwareRuntime.retryConnection(); usb.scan() }
    fun startFirmware() = launchFirmware(resume = false)
    fun resumeFirmware() = launchFirmware(resume = true)
    private fun launchFirmware(resume: Boolean) {
        try {
            check(!state.value.firmwareBusy && !PaxFirmwareRuntime.state.value.active)
            val saved = state.value.installation
            val model = if (resume) saved.model else state.value.paxModel
            val mode = if (resume) saved.mode else state.value.installMode
            check(model != PaxModel.D230) { "Скоро.." }
            val path = if (mode == PaxInstallMode.RemoveMainApp) null else if (resume) saved.bundleDirectory else state.value.firmware?.takeIf { it.model == model }?.directory?.path
            check(mode == PaxInstallMode.RemoveMainApp || path != null) { "Сначала выберите ZIP этой модели." }
            val equipment = usb.state.value.devices.singleOrNull { it.hasPermission &&
                if (model == PaxModel.S210) isPaxPinpadCandidate(it) else isPaxXcbCandidate(it) }
                ?: error("Подключите один PAX и разрешите USB-доступ.")
            check(usb.state.value.probingKey == null) { "Дождитесь завершения чтения паспорта." }
            val fingerprint = if (model == PaxModel.S210) {
                val info = usb.state.value.pinpads[equipment.key]
                val beforeOs = mode != PaxInstallMode.MainApp && (!resume || "pinpad-os" !in saved.completedActions)
                if (!resume) check(if (beforeOs) equipment.key in usb.state.value.pinpadOsBoot else info?.fingerprint != null) {
                    if (beforeOs) "Подключите S210 с белым экраном и проверьте подключение." else "Прочитайте данные S210 в системном меню."
                }
                info?.fingerprint ?: saved.deviceFingerprint.takeIf { resume }
            }
            else {
                val passport = usb.state.value.passports[equipment.key]
                if (passport != null) check(passport.model == model.name && passport.target == "Inpas") { "Модель или платформа PAX не совпала с выбранной." }
                passport?.fingerprint ?: saved.deviceFingerprint.takeIf { resume }
                    ?: error("Сначала прочитайте паспорт PAX в шаге «Подключение».")
            }
            usb.cancelProbe()
            PaxFirmwareService.start(context, owner, path, equipment.key, fingerprint, model, mode, resume, state.value.pinpadExpectedOs.takeIf { it.isNotBlank() })
        } catch (failure: Exception) { notice(failure.message ?: "Не удалось начать установку PAX.") }
    }
    fun refresh(verifyHash: Boolean = true) {
        if (work?.isActive == true) {
            pendingRefresh = true; pendingHashCheck = pendingHashCheck || verifyHash
            return
        }
        work = viewModelScope.launch {
            mutable.update { it.copy(loading = true) }
            try { apply(repository.refresh(owner, verifyHash)) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { notice("Не удалось прочитать файлы FTP или историю инструментов. Обновите список.") }
            finally { mutable.update { it.copy(loading = false) }; finishWork() }
        }
    }
    fun inspect(file: LocalToolFile) {
        if (file.downloading || work?.isActive == true) return
        mutable.update { it.copy(inspected = file, checkMessage = null) }
        if (state.value.snapshot.selection?.file?.id == file.id) check(file.id)
    }
    fun choose(id: String) = action {
        repository.select(owner, id)
        val result = repository.checkForTransfer(owner, id, currentDevice())
        apply(repository.refresh(owner))
        mutable.update { it.copy(checkMessage = result.message) }
    }
    fun check(id: String) = action {
        val result = repository.checkForTransfer(owner, id, currentDevice())
        apply(repository.refresh(owner))
        mutable.update { it.copy(checkMessage = result.message) }
    }
    fun clearSelection() = action { repository.clearSelection(owner); apply(repository.refresh(owner)) }
    fun bind(event: String, request: ToolRequestRef?) = action {
        repository.bindRequest(owner, event, request); apply(repository.refresh(owner))
    }
    fun closeFile() { if (!state.value.checking) mutable.update { it.copy(inspected = null, checkMessage = null) } }
    fun notice(value: String?) { mutable.update { it.copy(notice = value) } }
    private fun currentDevice() = usb.state.value.devices.singleOrNull { it.hasPermission }?.key
    private fun apply(result: ToolsFilesResult) { mutable.update {
        it.copy(files = result.files, snapshot = result.snapshot, notice = result.notice ?: it.notice,
            inspected = it.inspected?.let { file -> result.files.firstOrNull { current -> current.id == file.id } ?: file })
    } }
    private fun action(block: suspend () -> Unit): Boolean {
        if (work?.isActive == true) return false
        work = viewModelScope.launch {
            mutable.update { it.copy(checking = true) }
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { notice(error.message ?: "Не удалось проверить файл или сохранить выбор.") }
            finally { mutable.update { it.copy(checking = false) }; finishWork() }
        }
        return true
    }
    private fun finishWork() {
        work = null
        if (pendingRefresh) {
            val hash = pendingHashCheck
            pendingRefresh = false; pendingHashCheck = false
            refresh(verifyHash = hash)
        }
    }
    override fun onCleared() { mtk.close(); usb.close() }
    companion object {
        fun factory(context: Context, ftp: FtpRepository, owner: String, token: String) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ToolsViewModel(context.applicationContext, ftp, owner, token) as T
        }
    }
}
