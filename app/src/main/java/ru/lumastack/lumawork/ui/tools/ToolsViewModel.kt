package ru.lumastack.lumawork.ui.tools

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.lumastack.lumawork.data.ftp.FtpTransfers
import ru.lumastack.lumawork.data.tools.*

data class ToolsUiState(
    val files: List<LocalToolFile> = emptyList(), val snapshot: ToolsSnapshot = ToolsSnapshot(),
    val loading: Boolean = true, val checking: Boolean = false, val notice: String? = null,
    val inspected: LocalToolFile? = null, val checkMessage: String? = null,
)
class ToolsViewModel(context: Context, transfers: FtpTransfers, private val owner: String) : ViewModel() {
    private val repository = ToolsRepository(FtpLocalToolFiles(transfers), FileToolsStore(context))
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
