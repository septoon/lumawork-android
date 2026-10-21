package ru.lumastack.lumawork.ui.ftp

import android.content.*
import android.os.Build
import android.provider.DocumentsContract
import ru.lumastack.lumawork.ui.shell.AppHeaderBackHandler as BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import ru.lumastack.lumawork.R
import ru.lumastack.lumawork.data.ftp.*
import ru.lumastack.lumawork.domain.model.AppSession
import ru.lumastack.lumawork.ui.adaptive.AppWidthClass
import ru.lumastack.lumawork.ui.theme.AppRefreshContainer
import ru.lumastack.lumawork.ui.shell.AppHeaderSegments
import androidx.compose.material.icons.automirrored.filled.List
import ru.lumastack.lumawork.ui.theme.AppTextField
import ru.lumastack.lumawork.ui.theme.AppSurfaces
import ru.lumastack.lumawork.ui.theme.AppCard
import ru.lumastack.lumawork.ui.theme.AppPanel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FtpRoute(session: AppSession, startOffline: Boolean, repository: FtpRepository, widthClass: AppWidthClass, modifier: Modifier = Modifier) {
    key(session.user.id, session.token, startOffline) { FtpRouteContent(session, startOffline, repository, widthClass, modifier) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FtpRouteContent(session: AppSession, startOffline: Boolean, repository: FtpRepository, widthClass: AppWidthClass, modifier: Modifier) {
    val model: FtpViewModel = viewModel(key = "ftp-${session.user.id}-${session.token.hashCode()}-$startOffline", factory = FtpViewModel.factory(repository, session, startOffline))
    val state by model.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lifecycleState by lifecycle.currentStateFlow.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var pendingDirectoryPath by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingDirectoryName by rememberSaveable { mutableStateOf("") }
    var pendingDirectoryIsFolder by rememberSaveable { mutableStateOf(false) }
    var deleteRecord by remember { mutableStateOf<FtpTransfer?>(null) }
    fun copyPath(path: String) {
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Путь FTP", path))
        if (Build.VERSION.SDK_INT < 33) model.notice("Путь FTP скопирован.")
    }
    val openRecord: (FtpTransfer, Boolean) -> Unit = { record, share -> scope.launch {
        try {
            require(record.ownerId == session.user.id)
            val uri = if (record.action == FtpTransferAction.SaveToFolder && record.documentUri != null) record.documentUri.toUri()
                else repository.transfers?.savedFile(session.user.id, record.id)?.let { FileProvider.getUriForFile(context, "${context.packageName}.files", it) }
                    ?: error("Скачанный файл больше не найден на устройстве.")
            val mime = ftpMimeType(record.fileName, record.folderArchive)
            val intent = if (share) Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
                else Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
            intent.clipData = ClipData.newRawUri(record.fileName, uri)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (share && record.presentationPending) repository.transfers?.acknowledgeShare(session.user.id, record.id)
            context.startActivity(if (share) Intent.createChooser(intent, "Поделиться файлом") else intent)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: ActivityNotFoundException) { model.notice("На устройстве нет приложения для этого формата. Воспользуйтесь действием «Поделиться файлом».") }
        catch (_: Exception) { model.notice("Не удалось открыть файл. Проверьте, что он доступен на устройстве.") }
    } }
    val nativeFiles = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        result.data?.data?.let { uri ->
            try { context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, context.contentResolver.getType(uri) ?: "application/octet-stream")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
            catch (_: ActivityNotFoundException) { model.notice("На устройстве нет приложения для этого формата.") }
        }
    }
    fun showFiles(record: FtpTransfer? = null) {
        try {
            val authority = "${context.packageName}.ftp.documents"
            val documentId = if (record == null || record.action == FtpTransferAction.Share) "root:${ftpOwnerKey(session.user.id)}" else "file:${ftpOwnerKey(session.user.id)}:${record.id}"
            val uri = if (record?.action == FtpTransferAction.SaveToFolder && record.documentUri != null) record.documentUri.toUri()
                else DocumentsContract.buildDocumentUri(authority, documentId)
            nativeFiles.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE)
                .putExtra(DocumentsContract.EXTRA_INITIAL_URI, uri))
        } catch (_: ActivityNotFoundException) { model.notice("Файловый менеджер недоступен на этом устройстве.") }
    }
    val directoryPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val item = pendingDirectoryPath?.let { FtpItem(pendingDirectoryName, it, if (pendingDirectoryIsFolder) FtpItemType.Directory else FtpItemType.File) }
        pendingDirectoryPath = null
        if (uri != null && item != null) {
            try {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                model.download(item, FtpTransferAction.SaveToFolder, uri.toString())
            } catch (_: SecurityException) { model.notice("Не удалось получить доступ к выбранной папке. Выберите другую папку.") }
        }
    }
    LaunchedEffect(model, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                try {
                    if (model.state.value.transfers.any { it.folderArchive && it.state.isActive && !it.downloadComplete } && !startOffline)
                        FtpArchiveDownloadService.start(context)
                    model.refreshTransfers()
                    if (model.state.value.transfers.any { it.state.isActive && it.downloadComplete && it.action == FtpTransferAction.SaveToFolder }) {
                        if (!FtpTransferJobService.schedule(context)) repository.transfers?.finishPending()
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { model.notice("Не удалось обновить историю загрузок.") }
                delay(1_000)
            }
        }
    }
    LaunchedEffect(state.openRecord?.id) { state.openRecord?.let { openRecord(it, false); model.consumePreview() } }
    val pendingShare = state.transfers.firstOrNull { it.state == FtpTransferState.Completed && it.action == FtpTransferAction.Share && it.presentationPending }
    var presentedShareId by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(pendingShare?.id, state.queueOpen, state.selected, lifecycleState) {
        if (pendingShare != null && presentedShareId != pendingShare.id && state.selected == null && !state.queueOpen && lifecycleState.isAtLeast(Lifecycle.State.RESUMED)) {
            presentedShareId = pendingShare.id
            openRecord(pendingShare, true)
        }
    }
    LaunchedEffect(state.notice) { state.notice?.let { message -> snackbar.showSnackbar(message); model.notice(null) } }
    BackHandler(state.selected == null && !state.queueOpen && state.section == FtpSection.Catalog && state.path != "/", model::up)
    AppHeaderSegments(listOf("FTP", "Загрузки"), state.section.ordinal, { model.section(FtpSection.entries[it]) }, actions = {
        val activeCount = state.transfers.count { it.state.isActive }
        IconButton(onClick = model::showQueue) {
            BadgedBox(badge = { if (activeCount > 0) Badge { Text(activeCount.toString()) } }) {
                Icon(Icons.AutoMirrored.Filled.List, "Очередь${if (activeCount > 0) " ($activeCount)" else ""}")
            }
        }
    })
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = if (widthClass == AppWidthClass.Expanded) 1180.dp else 720.dp).fillMaxSize()) {
            if (state.section == FtpSection.Catalog) {
                FtpBreadcrumbBar(state.path, model::navigate)
                FtpCatalog(state, model, ::copyPath)
            } else FtpSavedDownloads(state, { model.downloadedSearch(it) }, { model.section(FtpSection.Catalog) },
                { openRecord(it, false) }, { openRecord(it, true) }, { showFiles(it) }, { deleteRecord = it }, { showFiles() })
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
    state.selected?.let { item ->
        ModalBottomSheet(containerColor = AppSurfaces.panel, tonalElevation = 0.dp, scrimColor = AppSurfaces.scrim, onDismissRequest = model::closeSelected, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SelectionContainer(Modifier.weight(1f)) { Text(item.name + if (item.isDirectory) ".zip" else "", style = MaterialTheme.typography.titleLarge) }
                    IconButton(onClick = model::closeSelected) { Icon(Icons.Default.Close, "Закрыть детали") }
                }
                item.sizeText?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                TextButton(onClick = { copyPath(item.path); model.closeSelected() }) { Text("Скопировать путь FTP") }
                TextButton(onClick = { model.download(item, FtpTransferAction.Share) }, enabled = !startOffline) { Text("Поделиться файлом") }
                HorizontalDivider()
                TextButton(onClick = { model.download(item, FtpTransferAction.Download) }, enabled = !startOffline) { Text("Скачать") }
                TextButton(onClick = { pendingDirectoryPath = item.path; pendingDirectoryName = item.name; pendingDirectoryIsFolder = item.isDirectory; model.closeSelected(); directoryPicker.launch(null) }, enabled = !startOffline) { Text("Сохранить в выбранную папку") }
                Text("«Скачать» сохраняет файл в папку «Загрузки FTP» приложения «Инженер».", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (state.queueOpen) ModalBottomSheet(containerColor = AppSurfaces.panel, tonalElevation = 0.dp, scrimColor = AppSurfaces.scrim, onDismissRequest = model::hideQueue, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.9f)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Загрузки FTP", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                IconButton(onClick = model::hideQueue) { Icon(Icons.Default.Close, "Закрыть очередь") }
            }
            LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (state.transfers.isEmpty()) item { FtpMessage("Загрузок пока нет", "Активные и завершённые передачи появятся здесь.") }
                items(state.transfers, key = { it.id }) { record -> FtpTransferCard(record,
                    { openRecord(record, false) }, { openRecord(record, true) }, { showFiles(record) },
                    { model.cancel(record) }, {
                        if (record.action == FtpTransferAction.Download && record.state == FtpTransferState.Completed) deleteRecord = record else model.remove(record)
                    }) }
            }
        }
    }
    deleteRecord?.let { record -> AlertDialog(onDismissRequest = { deleteRecord = null },
        title = { Text("Удалить файл с устройства?") }, text = { Text(record.fileName) },
        confirmButton = { TextButton(onClick = { model.deleteSaved(record); deleteRecord = null }) { Text("Удалить файл", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { deleteRecord = null }) { Text("Отмена") } }) }
}

@Composable
private fun FtpBreadcrumbBar(path: String, navigate: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { navigate("/") }) { Icon(Icons.Default.Home, "В начало FTP") }
        ftpBreadcrumbs(path).forEachIndexed { index, (name, target) ->
            if (index > 0) Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { navigate(target) }, enabled = target != path) { Text(name, maxLines = 1) }
        }
    }
}

@Composable
private fun FtpCatalog(state: FtpUiState, model: FtpViewModel, copyPath: (String) -> Unit) {
    AppRefreshContainer(Modifier.fillMaxSize(), isRefreshing = state.isLoading, onRefresh = model::refresh, enabled = !state.offline) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { AppTextField(state.search, model::search, "Поиск в папке", search = true, singleLine = true, modifier = Modifier.fillMaxWidth()) }
            if (state.path == "/" && state.search.isBlank()) item {
                AppCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Избранное${state.snapshot.favoritePaths.size.takeIf { it > 0 }?.let { " • $it" }.orEmpty()}", style = MaterialTheme.typography.titleMedium)
                    if (state.snapshot.favoritePaths.isEmpty()) Text("Отмечайте папки звёздочкой для быстрого доступа.", style = MaterialTheme.typography.bodySmall)
                    state.snapshot.favoritePaths.forEach { path -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { model.navigate(path) }, modifier = Modifier.weight(1f)) { Column(Modifier.fillMaxWidth()) {
                            Text(path.substringAfterLast('/'), fontWeight = FontWeight.Medium); Text(path, style = MaterialTheme.typography.bodySmall)
                        } }
                        IconButton(onClick = { model.favorite(path) }) { Icon(Icons.Default.Star, "Убрать $path из избранного", tint = MaterialTheme.colorScheme.primary) }
                    } }
                } }
            }
            item { Text(if (state.path == "/") "Каталог" else state.path.substringAfterLast('/'), style = MaterialTheme.typography.titleLarge) }
            if (state.offline) item { Text("Сохранённый каталог. Скачанные файлы доступны в разделе «Загрузки».", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            state.errorMessage?.let { message -> item { FtpMessage("Не удалось загрузить каталог", message) { TextButton(onClick = model::refresh) { Text("Повторить") } } } }
            if (state.isLoading && state.items.isEmpty()) item { CircularProgressIndicator(Modifier.padding(24.dp)) }
            if (!state.isLoading && state.items.isEmpty() && state.errorMessage == null) item { FtpMessage(if (state.search.isBlank()) "Каталог пуст" else "Ничего не найдено",
                if (state.offline && state.snapshot.directories[state.path] == null) "Эта папка ещё не сохранена на устройстве." else "Файлы отсутствуют или ничего не найдено.") }
            items(state.items, key = FtpItem::path) { item -> FtpRemoteRow(item, item.path in state.snapshot.favoritePaths,
                state.transfers.firstOrNull { it.remotePath == normalizeFtpPath(item.path) && it.state.isActive },
                { model.open(item) }, { model.selectActions(item) }, { model.favorite(item.path) }, { copyPath(item.path) }) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FtpRemoteRow(item: FtpItem, favorite: Boolean, active: FtpTransfer?, open: () -> Unit, actions: () -> Unit, toggleFavorite: () -> Unit, copy: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box {
        AppPanel(Modifier.fillMaxWidth().combinedClickable(onClick = open, onLongClick = { menu = true })) {
            Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(painterResource(if (item.isDirectory) R.drawable.ic_nav_folder_open else R.drawable.ic_nav_assignment), null, tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f)) {
                    Text(item.name, fontWeight = FontWeight.SemiBold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Text(listOfNotNull(item.sizeText, item.modifiedAt).joinToString(" • "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    active?.let { Text("${it.state.title}${it.progress?.let { value -> " • ${(value * 100).toInt()} %" }.orEmpty()}", style = MaterialTheme.typography.labelSmall) }
                }
                if (item.isDirectory) IconButton(onClick = toggleFavorite) { Icon(Icons.Default.Star, if (favorite) "Убрать папку из избранного" else "Добавить папку в избранное", tint = if (favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline) }
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Действия: ${item.name}") }
            }
        }
        DropdownMenu(menu, { menu = false }) {
            DropdownMenuItem({ Text(if (item.isDirectory) "Скачать папку ZIP" else "Действия с файлом") }, { menu = false; actions() })
            if (item.isDirectory) DropdownMenuItem({ Text(if (favorite) "Убрать из избранного" else "Добавить в избранное") }, { menu = false; toggleFavorite() })
            DropdownMenuItem({ Text("Скопировать путь FTP") }, { menu = false; copy() })
        }
    }
}

@Composable
private fun FtpSavedDownloads(state: FtpUiState, search: (String) -> Unit, openFtp: () -> Unit, open: (FtpTransfer) -> Unit,
    share: (FtpTransfer) -> Unit, files: (FtpTransfer) -> Unit, delete: (FtpTransfer) -> Unit, rootFiles: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { AppTextField(state.downloadedSearch, search, "Поиск в загрузках", search = true, singleLine = true, modifier = Modifier.fillMaxWidth()) }
        item { Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("На устройстве", style = MaterialTheme.typography.titleLarge)
                Text("${state.savedDownloads.size} файлов • ${ftpFileSize(state.savedDownloads.sumOf { it.bytesWritten })}", style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick = rootFiles) { Icon(painterResource(R.drawable.ic_nav_folder_open), "Открыть папку загрузок в Файлах") }
        } }
        if (state.savedDownloads.isEmpty()) item { FtpMessage("Скачанных файлов пока нет", "Они появятся здесь и будут доступны без подключения к FTP.") { TextButton(onClick = openFtp) { Text("Открыть FTP") } } }
        else if (state.visibleDownloads.isEmpty()) item { FtpMessage("Ничего не найдено", "В загрузках нет файлов по этому запросу.") }
        items(state.visibleDownloads, key = { it.id }) { record -> FtpTransferCard(record, { open(record) }, { share(record) }, { files(record) }, {}, { delete(record) }) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FtpTransferCard(record: FtpTransfer, open: () -> Unit, share: () -> Unit, files: () -> Unit, cancel: () -> Unit, remove: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box {
        AppCard(Modifier.fillMaxWidth().combinedClickable(onClick = { if (record.state == FtpTransferState.Completed) open() }, onLongClick = { menu = true })) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(record.fileName, Modifier.weight(1f), fontWeight = FontWeight.SemiBold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    if (record.canCancel) IconButton(onClick = cancel) { Icon(Icons.Default.Close, "Отменить загрузку") }
                    else if (!record.state.isActive) IconButton(onClick = remove) { Icon(if (record.action == FtpTransferAction.Download && record.state == FtpTransferState.Completed) Icons.Default.Delete else Icons.Default.Close,
                        if (record.action == FtpTransferAction.Download && record.state == FtpTransferState.Completed) "Удалить файл с устройства" else "Скрыть загрузку") }
                    if (record.state == FtpTransferState.Completed) IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Действия с файлом") }
                }
                Text("${if (record.isFinalizing) "Сохранение файла…" else record.state.title} • ${ftpFileSize(record.bytesWritten)}${record.totalBytes?.let { " / ${ftpFileSize(it)}" }.orEmpty()}", style = MaterialTheme.typography.bodySmall)
                if (record.state.isActive) record.progress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()) } ?: LinearProgressIndicator(Modifier.fillMaxWidth())
                if (record.state == FtpTransferState.Completed) Text(FTP_DATE.format(Instant.ofEpochMilli(record.createdAt)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                record.errorMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            }
        }
        DropdownMenu(menu, { menu = false }) {
            if (record.state == FtpTransferState.Completed) {
                DropdownMenuItem({ Text("Открыть") }, { menu = false; open() })
                DropdownMenuItem({ Text("Поделиться файлом") }, { menu = false; share() })
                DropdownMenuItem({ Text("Показать в Файлах") }, { menu = false; files() })
            } else if (record.canCancel) DropdownMenuItem({ Text("Отменить загрузку") }, { menu = false; cancel() })
        }
    }
}
@Composable
private fun FtpMessage(title: String, message: String, action: @Composable () -> Unit = {}) {
    AppCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium); Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant); action()
    } }
}
private fun ftpFileSize(value: Long): String = when { value >= 1_073_741_824 -> "%.1f ГБ".format(value / 1_073_741_824.0); value >= 1_048_576 -> "%.1f МБ".format(value / 1_048_576.0); value >= 1024 -> "%.1f КБ".format(value / 1024.0); else -> "$value Б" }
private val FTP_DATE = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.forLanguageTag("ru")).withZone(ZoneId.systemDefault())
