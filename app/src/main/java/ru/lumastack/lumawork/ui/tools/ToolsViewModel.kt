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
    val firmware: PaxFirmwareBundle? = null, val firmwareBusy: Boolean = true,
    val installation: PaxInstallState = PaxInstallState(), val globalInstallBusy: Boolean = false,
)
class ToolsViewModel(context: Context, transfers: FtpTransfers, private val owner: String) : ViewModel() {
    private val context = context.applicationContext as android.app.Application
    private val localFiles = FtpLocalToolFiles(transfers)
    private val repository = ToolsRepository(localFiles, FileToolsStore(context))
    private val firmwareStore = PaxFirmwareStore(context)
    private val mutable = MutableStateFlow(ToolsUiState())
    val state = mutable.asStateFlow()
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
                    runCatching { PaxFirmwareBundleReader.read(firmwareStore.checkedBundleDirectory(owner, path)) }.getOrNull()
                } }
                mutable.update { it.copy(firmware = bundle, installation = saved) }
                repository.recordInstallation(owner, saved)
            } catch (_: Exception) { notice("Не удалось прочитать подготовленный комплект PAX. Выберите ZIP заново.") }
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
    fun importFirmware(uri: Uri) = prepareFirmware { requireNotNull(context.contentResolver.openInputStream(uri)) { "Не удалось открыть ZIP." } }
    fun prepareFirmwareFromFtp(id: String) = prepareFirmware { localFiles.open(owner, id) }
    private fun prepareFirmware(open: suspend () -> InputStream) {
        if (state.value.firmwareBusy || PaxFirmwareRuntime.state.value.active) return
        mutable.update { it.copy(firmwareBusy = true, inspected = null, checkMessage = null) }
        viewModelScope.launch {
            try {
                val bundle = withContext(Dispatchers.IO) {
                    val destination = File(firmwareStore.ownerDirectory(owner), "bundle-${UUID.randomUUID()}")
                    open().use { PaxFirmwareBundleReader.prepare(it, destination) }
                }
                val ready = PaxInstallState(owner = owner, phase = PaxInstallPhase.Ready, bundleDirectory = bundle.directory.path,
                    detail = "Проверены все 10 файлов полного комплекта D270.", updatedAt = System.currentTimeMillis())
                withContext(Dispatchers.IO) { firmwareStore.write(ready) }
                mutable.update { it.copy(firmware = bundle, installation = ready) }
                notice("Полный комплект D270 проверен и подготовлен.")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { notice(failure.message ?: "Не удалось подготовить полный комплект D270.") }
            finally { mutable.update { it.copy(firmwareBusy = false) } }
        }
    }
    fun startFirmware() {
        try {
            check(!state.value.firmwareBusy && !PaxFirmwareRuntime.state.value.active)
            val bundle = requireNotNull(state.value.firmware) { "Сначала подготовьте полный комплект." }
            val equipment = usb.state.value.devices.singleOrNull { it.hasPermission && isPaxUsbCandidate(it) }
                ?: error("Подключите один PAX и разрешите USB-доступ.")
            check(usb.state.value.probingKey == null) { "Дождитесь завершения чтения паспорта." }
            val passport = requireNotNull(usb.state.value.passports[equipment.key]) { "Сначала прочитайте паспорт PAX во вкладке «Устройство»." }
            check(passport.model == "D270" && passport.os?.startsWith("Nuxiums") == true && passport.target == "Inpas") { "Нужен PAX D270 / Nuxiums / Inpas." }
            val fingerprint = requireNotNull(passport.fingerprint) { "Идентичность терминала не подтверждена." }
            usb.cancelProbe()
            PaxFirmwareService.start(context, owner, bundle.directory.path, equipment.key, fingerprint)
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
