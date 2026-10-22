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
import ru.lumastack.lumawork.data.ftp.FtpTransfers
import ru.lumastack.lumawork.data.tools.*
import java.io.File
import java.io.InputStream
import java.util.UUID

data class ToolsUiState(
    val files: List<LocalToolFile> = emptyList(), val snapshot: ToolsSnapshot = ToolsSnapshot(),
    val loading: Boolean = true, val checking: Boolean = false, val notice: String? = null,
    val inspected: LocalToolFile? = null, val checkMessage: String? = null,
    val firmware: PaxFirmwarePackage? = null, val firmwareBusy: Boolean = true, val firmwareError: String? = null,
    val pinpadExpectedOs: String = "",
    val paxModel: PaxModel = PaxModel.D270, val installMode: PaxInstallMode = PaxInstallMode.Full,
    val installation: PaxInstallState = PaxInstallState(), val globalInstallBusy: Boolean = false,
)
class ToolsViewModel(context: Context, transfers: FtpTransfers, private val owner: String) : ViewModel() {
    private val context = context.applicationContext as android.app.Application
    private val localFiles = FtpLocalToolFiles(transfers)
    private val repository = ToolsRepository(localFiles, FileToolsStore(context))
    private val firmwareStore = PaxFirmwareStore(context)
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
                mutable.update { it.copy(firmware = bundle, pinpadExpectedOs = saved.expectedPinpadOs ?: expectedPinpadOs(bundle?.os).orEmpty(), installation = saved, paxModel = saved.model, installMode = saved.mode) }
                repository.recordInstallation(owner, saved)
            } catch (_: Exception) { notice("Не удалось прочитать подготовленный комплект PAX. Выберите ZIP, RAR или 7z заново.") }
            finally { mutable.update { it.copy(firmwareBusy = false) } }
            PaxFirmwareRuntime.state.collect { current ->
                mutable.update { it.copy(globalInstallBusy = current.active,
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
    fun importFirmware(uri: Uri) = prepareFirmware { requireNotNull(context.contentResolver.openInputStream(uri)) { "Не удалось открыть архив." } }
    fun prepareFirmwareFromFtp(id: String) = prepareFirmware { localFiles.open(owner, id) }
    private fun prepareFirmware(open: suspend () -> InputStream) {
        if (state.value.firmwareBusy || PaxFirmwareRuntime.state.value.active) return
        preparedPackages.remove(state.value.paxModel)
        mutable.update { it.copy(firmware = null, firmwareBusy = true, firmwareError = null, inspected = null, checkMessage = null) }
        viewModelScope.launch {
            try {
                val selectedModel = state.value.paxModel
                val bundle = withContext(Dispatchers.IO) {
                    val destination = File(firmwareStore.ownerDirectory(owner), "bundle-${UUID.randomUUID()}")
                    try { open().use { PaxArchiveCatalog.prepare(it, destination, selectedModel) } }
                    catch (failure: Exception) {
                        if (selectedModel != PaxModel.D270) throw failure
                        // Retain support for the previously imported, fingerprinted full D270 capture kit.
                        try { open().use { PaxFirmwareBundleReader.prepare(it, destination).asPackage() } }
                        catch (_: Exception) { throw failure }
                    }
                }
                preparedPackages[bundle.model] = bundle
                if (bundle.model == PaxModel.S210) pinpadExpectations[bundle.directory.path] = expectedPinpadOs(bundle.os).orEmpty()
                val ready = PaxInstallState(owner = owner, phase = PaxInstallPhase.Ready, bundleDirectory = bundle.directory.path,
                    detail = "Комплект ${selectedModel.title} подготовлен.", model = selectedModel, updatedAt = System.currentTimeMillis())
                if (!state.value.installation.canResume) withContext(Dispatchers.IO) { firmwareStore.write(ready) }
                mutable.update { it.copy(firmware = bundle, pinpadExpectedOs = expectedPinpadOs(bundle.os).orEmpty(), installation = if (it.installation.canResume) it.installation else ready,
                    installMode = if (!bundle.hasOs && it.installMode == PaxInstallMode.Full) PaxInstallMode.MainApp else it.installMode) }
                notice("Комплект ${selectedModel.title} подготовлен. Доступные операции определены по его составу.")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                val message = failure.message ?: "Не удалось подготовить комплект выбранной модели."
                mutable.update { it.copy(firmwareError = message) }
                notice(message)
            }
            finally { mutable.update { it.copy(firmwareBusy = false) } }
        }
    }
    fun selectPaxModel(model: PaxModel) {
        if (state.value.firmwareBusy || PaxFirmwareRuntime.state.value.active) return
        val cached = preparedPackages[model]
        mutable.update { it.copy(paxModel = model, firmware = cached, firmwareError = null,
            pinpadExpectedOs = if (model == PaxModel.S210) cached?.let { bundle ->
                pinpadExpectations[bundle.directory.path] ?: expectedPinpadOs(bundle.os)
            }.orEmpty() else it.pinpadExpectedOs,
            installMode = if (model == PaxModel.S210 && it.installMode == PaxInstallMode.RemoveMainApp) PaxInstallMode.Full else it.installMode) }
    }
    fun selectInstallMode(mode: PaxInstallMode) {
        if (state.value.firmwareBusy || PaxFirmwareRuntime.state.value.active) return
        if (state.value.paxModel == PaxModel.S210 && mode == PaxInstallMode.RemoveMainApp) return
        mutable.update { it.copy(installMode = mode) }
    }
    fun setPinpadExpectedOs(value: String) {
        if (state.value.firmwareBusy || PaxFirmwareRuntime.state.value.active) return
        val clean = value.trim()
        if (clean.length <= 96 && clean.all { it.isLetterOrDigit() || it in ".-_()" }) {
            state.value.firmware?.takeIf { it.model == PaxModel.S210 }?.let { pinpadExpectations[it.directory.path] = clean }
            mutable.update { it.copy(pinpadExpectedOs = clean) }
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
    override fun onCleared() { usb.close() }
    companion object {
        fun factory(context: Context, transfers: FtpTransfers, owner: String) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ToolsViewModel(context.applicationContext, transfers, owner) as T
        }
    }
}
