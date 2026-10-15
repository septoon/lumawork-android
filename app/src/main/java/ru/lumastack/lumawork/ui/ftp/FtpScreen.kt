package ru.lumastack.lumawork.ui.ftp

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.lumastack.lumawork.data.ftp.FtpFailure
import ru.lumastack.lumawork.data.ftp.FtpItem
import ru.lumastack.lumawork.data.ftp.FtpRepository
import ru.lumastack.lumawork.data.ftp.FtpSnapshot
import ru.lumastack.lumawork.domain.model.AppSession
import ru.lumastack.lumawork.ui.adaptive.AppWidthClass
import ru.lumastack.lumawork.ui.theme.LumaBackgroundBottomDark
import ru.lumastack.lumawork.ui.theme.LumaBackgroundBottomLight
import ru.lumastack.lumawork.ui.theme.LumaBackgroundTopDark
import ru.lumastack.lumawork.ui.theme.LumaBackgroundTopLight

data class FtpUiState(
    val snapshot: FtpSnapshot = FtpSnapshot(),
    val path: String = "/",
    val search: String = "",
    val selected: FtpItem? = null,
    val isLoading: Boolean = false,
    val downloadingPath: String? = null,
    val errorMessage: String? = null,
    val downloaded: File? = null,
) {
    val items: List<FtpItem> get() {
        val values = snapshot.directories[path].orEmpty()
        val query = search.trim()
        return if (query.isEmpty()) values else values.filter { it.name.contains(query, true) }
    }
}

class FtpViewModel(
    private val repository: FtpRepository,
    private val session: AppSession,
    private val startOffline: Boolean,
) : ViewModel() {
    private val mutableState = MutableStateFlow(FtpUiState())
    val state = mutableState.asStateFlow()
    init { viewModelScope.launch {
        repository.read(session.user.id)?.let { mutableState.update { state -> state.copy(snapshot = it) } }
        if (!startOffline) load("/", false)
    } }
    fun search(value: String) { mutableState.update { it.copy(search = value) } }
    fun open(item: FtpItem) {
        if (item.isDirectory) load(item.path, true) else mutableState.update { it.copy(selected = item) }
    }
    fun up() {
        val current = mutableState.value.path.trimEnd('/')
        if (current.isEmpty()) return
        load(current.substringBeforeLast('/', "").ifBlank { "/" }, true)
    }
    fun refresh() = load(mutableState.value.path, true)
    fun download(item: FtpItem) { viewModelScope.launch {
        mutableState.update { it.copy(downloadingPath = item.path, errorMessage = null, downloaded = null) }
        try {
            val file = repository.download(session.token, item)
            mutableState.update { it.copy(downloadingPath = null, downloaded = file) }
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) { mutableState.update { it.copy(downloadingPath = null, errorMessage = error.userMessage()) } }
    } }
    fun clearDownloaded() { mutableState.update { it.copy(downloaded = null) } }
    private fun load(path: String, selectPath: Boolean) { viewModelScope.launch {
        if (selectPath) mutableState.update { it.copy(path = path, search = "", selected = null) }
        mutableState.update { it.copy(isLoading = true, errorMessage = null) }
        try {
            val snapshot = repository.load(session.user.id, session.token, path)
            mutableState.update { it.copy(snapshot = snapshot, isLoading = false) }
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) { mutableState.update { it.copy(isLoading = false, errorMessage = error.userMessage()) } }
    } }
    private fun Throwable.userMessage() = (this as? FtpFailure)?.message ?: "FTP временно недоступен."
    companion object { fun factory(repository: FtpRepository, session: AppSession, offline: Boolean) = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = FtpViewModel(repository, session, offline) as T
    } }
}

@Composable
fun FtpRoute(session: AppSession, startOffline: Boolean, repository: FtpRepository, widthClass: AppWidthClass, modifier: Modifier = Modifier) {
    val model: FtpViewModel = viewModel(key = "ftp-${session.user.id}", factory = FtpViewModel.factory(repository, session, startOffline))
    val state by model.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    state.downloaded?.let { file ->
        LaunchedEffect(file) {
            shareFile(context, file)
            model.clearDownloaded()
        }
    }
    FtpScreen(state, widthClass, model::search, model::open, model::up, model::refresh, model::download, modifier)
}

@Composable
private fun FtpScreen(
    state: FtpUiState, widthClass: AppWidthClass, onSearch: (String) -> Unit, onOpen: (FtpItem) -> Unit,
    onUp: () -> Unit, onRefresh: () -> Unit, onDownload: (FtpItem) -> Unit, modifier: Modifier,
) {
    val dark = MaterialTheme.colorScheme.background == LumaBackgroundTopDark
    val background = remember(dark) { Brush.verticalGradient(if (dark) listOf(LumaBackgroundTopDark, LumaBackgroundBottomDark) else listOf(LumaBackgroundTopLight, LumaBackgroundBottomLight)) }
    Box(modifier.fillMaxSize().background(background), contentAlignment = Alignment.TopCenter) {
        if (widthClass == AppWidthClass.Compact) {
            FtpList(state, onSearch, onOpen, onUp, onRefresh, onDownload, Modifier.fillMaxSize().widthIn(max = 680.dp))
        } else {
            Row(Modifier.fillMaxSize().widthIn(max = 1180.dp).padding(20.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                FtpList(state, onSearch, onOpen, onUp, onRefresh, onDownload, Modifier.weight(.48f).fillMaxHeight())
                Surface(Modifier.weight(.52f).fillMaxHeight(), shape = RoundedCornerShape(26.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = .97f)) {
                    val selected = state.selected
                    if (selected == null) FtpStatus("Выберите файл", "Для папки доступно скачивание ZIP через кнопку в строке.")
                    else Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text(selected.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text(selected.path, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(selected.sizeText ?: selected.size?.let(::fileSize).orEmpty())
                        Button(onClick = { onDownload(selected) }, enabled = state.downloadingPath == null, modifier = Modifier.fillMaxWidth()) { Text("Скачать и открыть") }
                    }
                }
            }
        }
    }
}

@Composable private fun FtpList(state: FtpUiState, onSearch: (String) -> Unit, onOpen: (FtpItem) -> Unit, onUp: () -> Unit, onRefresh: () -> Unit, onDownload: (FtpItem) -> Unit, modifier: Modifier) {
    LazyColumn(modifier, contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (state.path != "/") TextButton(onClick = onUp) { Text("Назад") }
            Column(Modifier.weight(1f)) { Text(state.path, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis); Text("Файлы Сервионики", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            TextButton(onClick = onRefresh) { Text("Обновить") }
        } }
        item { OutlinedTextField(state.search, onSearch, label = { Text("Поиск в папке") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
        state.errorMessage?.let { item { FtpStatus("Ошибка", it) } }
        if (state.isLoading && state.items.isEmpty()) item { Row(Modifier.fillMaxWidth().padding(30.dp), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp) } }
        if (!state.isLoading && state.items.isEmpty()) item { FtpStatus("Каталог пуст", "Файлы отсутствуют или ничего не найдено.") }
        items(state.items, key = FtpItem::path) { item -> Surface(Modifier.fillMaxWidth().clickable { onOpen(item) }, shape = RoundedCornerShape(17.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = .97f)) {
            Row(Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (item.isDirectory) "П" else "Ф", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 12.dp))
                Column(Modifier.weight(1f)) { Text(item.name, fontWeight = FontWeight.SemiBold, maxLines = 2); Text(item.sizeText ?: item.modifiedAt.orEmpty(), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
                if (item.isDirectory) TextButton(onClick = { onDownload(item) }, enabled = state.downloadingPath == null) { Text(if (state.downloadingPath == item.path) "…" else "ZIP") }
            }
        } }
    }
}
@Composable private fun FtpStatus(title: String, message: String) { Column(Modifier.fillMaxSize().padding(26.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) { Text(title, fontWeight = FontWeight.Bold); Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
private fun fileSize(value: Long): String = when { value >= 1_048_576 -> "%.1f МБ".format(value / 1_048_576.0); value >= 1024 -> "%.1f КБ".format(value / 1024.0); else -> "$value Б" }
private fun shareFile(context: Context, file: File) { runCatching {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "application/octet-stream"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }, "Открыть файл"))
} }
