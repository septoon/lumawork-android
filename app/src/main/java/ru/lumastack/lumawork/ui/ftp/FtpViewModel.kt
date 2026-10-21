package ru.lumastack.lumawork.ui.ftp

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import ru.lumastack.lumawork.data.ftp.*
import ru.lumastack.lumawork.domain.model.AppSession

enum class FtpSection { Catalog, Downloads }
data class FtpUiState(
    val snapshot: FtpSnapshot = FtpSnapshot(), val path: String = "/", val search: String = "", val downloadedSearch: String = "",
    val section: FtpSection = FtpSection.Catalog, val selected: FtpItem? = null, val isLoading: Boolean = false,
    val transfers: List<FtpTransfer> = emptyList(), val queueOpen: Boolean = false,
    val errorMessage: String? = null, val notice: String? = null, val openRecord: FtpTransfer? = null, val offline: Boolean = false,
) {
    val items: List<FtpItem> get() = snapshot.directories[path].orEmpty().filter { search.isBlank() || it.name.contains(search.trim(), true) }
    val savedDownloads: List<FtpTransfer> get() = transfers.filter { it.action == FtpTransferAction.Download && it.state == FtpTransferState.Completed }
    val visibleDownloads: List<FtpTransfer> get() = savedDownloads.filter {
        downloadedSearch.isBlank() || it.fileName.contains(downloadedSearch.trim(), true) || it.remotePath.contains(downloadedSearch.trim(), true)
    }
}

class FtpViewModel(private val repository: FtpRepository, private val session: AppSession, private val startOffline: Boolean) : ViewModel() {
    private val mutableState = MutableStateFlow(FtpUiState(offline = startOffline))
    val state = mutableState.asStateFlow()
    private var loadJob: Job? = null
    private var pathRevision = 0L
    private var refreshingTransfers = false
    init { viewModelScope.launch {
        try { repository.read(session.user.id)?.let(::applySnapshot); refreshTransfers() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { mutableState.update { it.copy(errorMessage = "Не удалось прочитать сохранённые данные FTP.") } }
        if (!startOffline && pathRevision == 0L) navigate("/")
    } }
    private fun applySnapshot(snapshot: FtpSnapshot) { mutableState.update { if (snapshot.revision >= it.snapshot.revision) it.copy(snapshot = snapshot) else it } }
    fun search(value: String) { mutableState.update { it.copy(search = value) } }
    fun downloadedSearch(value: String) { mutableState.update { it.copy(downloadedSearch = value) } }
    fun section(value: FtpSection) { mutableState.update { it.copy(section = value, selected = null) } }
    fun open(item: FtpItem) {
        if (item.isDirectory) { navigate(item.path); return }
        val transfer = state.value.transfers.firstOrNull { it.remotePath == normalizeFtpPath(item.path) && (it.state.isActive || it.state == FtpTransferState.Completed) }
        when {
            transfer?.state?.isActive == true -> showQueue()
            transfer != null -> preview(transfer)
            else -> selectActions(item)
        }
    }
    fun navigate(path: String) {
        loadJob?.cancel(); val revision = ++pathRevision
        val normalized = normalizeFtpPath(path)
        mutableState.update { it.copy(path = normalized, search = "", selected = null, isLoading = !startOffline, errorMessage = null) }
        if (startOffline) return
        loadJob = viewModelScope.launch {
            try {
                val snapshot = repository.load(session.user.id, session.token, normalized)
                applySnapshot(snapshot)
                if (pathRevision == revision) mutableState.update { it.copy(isLoading = false) }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { if (pathRevision == revision) mutableState.update { it.copy(isLoading = false, errorMessage = error.userMessage()) } }
        }
    }
    fun up() { navigate(state.value.path.trimEnd('/').substringBeforeLast('/', "").ifBlank { "/" }) }
    fun refresh() { if (!startOffline) navigate(state.value.path) }
    fun selectActions(item: FtpItem) { mutableState.update { it.copy(selected = item) } }
    fun closeSelected() { mutableState.update { it.copy(selected = null) } }
    fun showQueue() { mutableState.update { it.copy(queueOpen = true, selected = null) } }
    fun hideQueue() { mutableState.update { it.copy(queueOpen = false) } }
    fun preview(record: FtpTransfer) { mutableState.update { it.copy(openRecord = record) } }
    fun consumePreview() { mutableState.update { it.copy(openRecord = null) } }
    fun notice(message: String?) { mutableState.update { it.copy(notice = message) } }
    fun favorite(path: String) { viewModelScope.launch {
        try { applySnapshot(repository.toggleFavorite(session.user.id, path)) }
        catch (_: Exception) { notice("Не удалось сохранить избранное.") }
    } }
    fun download(item: FtpItem, action: FtpTransferAction, treeUri: String? = null) {
        if (startOffline) { notice("Для загрузки файла требуется подключение к сети."); return }
        closeSelected()
        viewModelScope.launch {
            try {
                requireNotNull(repository.transfers) { "Загрузка файлов недоступна." }.start(session.user.id, session.token, item, action, treeUri)
                refreshTransfers()
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { notice(error.userMessage()) }
        }
    }
    suspend fun refreshTransfers() {
        if (refreshingTransfers) return
        val transfers = repository.transfers ?: return
        refreshingTransfers = true
        try {
            val records = transfers.refresh(session.user.id)
            mutableState.update { it.copy(transfers = records) }
        } finally { refreshingTransfers = false }
    }
    fun cancel(record: FtpTransfer) = transferAction { repository.transfers?.cancel(session.user.id, record.id) }
    fun remove(record: FtpTransfer) = transferAction { repository.transfers?.remove(session.user.id, record.id) }
    fun deleteSaved(record: FtpTransfer) = transferAction { repository.transfers?.deleteSaved(session.user.id, record.id); notice("Файл удалён с устройства.") }
    private fun transferAction(action: suspend () -> Unit) { viewModelScope.launch {
        try { action(); refreshTransfers() }
        catch (error: CancellationException) { throw error }
        catch (error: Exception) { notice(error.userMessage()) }
    } }
    private fun Throwable.userMessage() = (this as? FtpFailure)?.message ?: "Не удалось выполнить действие с файлом. Попробуйте ещё раз."
    companion object { fun factory(repository: FtpRepository, session: AppSession, offline: Boolean) = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = FtpViewModel(repository, session, offline) as T
    } }
}
