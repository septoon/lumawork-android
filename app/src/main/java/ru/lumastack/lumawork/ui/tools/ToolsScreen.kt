package ru.lumastack.lumawork.ui.tools

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.lumastack.lumawork.data.ftp.FtpRepository
import ru.lumastack.lumawork.data.tools.*
import ru.lumastack.lumawork.ui.adaptive.AppWidthClass
import ru.lumastack.lumawork.ui.simpleone.SimpleOneUiState
import ru.lumastack.lumawork.ui.theme.AppRefreshContainer
import ru.lumastack.lumawork.ui.theme.AppTextField
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import ru.lumastack.lumawork.ui.shell.AppHeaderSegments
import ru.lumastack.lumawork.ui.theme.appScreenBackground
import androidx.compose.foundation.background
import ru.lumastack.lumawork.ui.theme.AppCard

private enum class ToolsSection(val title: String) { Device("Устройство"), Firmware("Файлы"), History("История") }
private enum class FileFilter(val title: String) { All("Все ZIP"), Previous("Выбран ранее") }

@Composable
fun ToolsRoute(owner: String, repository: FtpRepository, requests: SimpleOneUiState,
    widthClass: AppWidthClass, onFtp: () -> Unit, onWiki: () -> Unit, onPaxFtp: () -> Unit = onFtp,
) {
    val context = LocalContext.current
    val transfers = repository.transfers
    if (transfers == null) {
        ToolCard("Файлы FTP недоступны") { Text("Локальное хранилище FTP не подключено."); TextButton(onClick = onFtp) { Text("Загрузить через FTP") } }
        return
    }
    val model: ToolsViewModel = viewModel(key = "tools-$owner", factory = ToolsViewModel.factory(context, transfers, owner))
    val state by model.state.collectAsStateWithLifecycle()
    val paxFiles = state.files.filter { it.isPaxFirmwareSource() }
    val usb by model.usb.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var section by rememberSaveable(owner) { mutableStateOf(ToolsSection.Device) }
    var query by rememberSaveable(owner) { mutableStateOf("") }
    var filter by rememberSaveable(owner) { mutableStateOf(FileFilter.All) }
    var binding by remember(owner) { mutableStateOf<String?>(null) }
    var requestSearch by remember { mutableStateOf("") }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val importFirmware = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) model.importFirmware(uri)
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) scope.launch {
            try {
                val report = buildString {
                    appendLine("Инженер — история инструментов")
                    state.snapshot.history.forEach { event ->
                        appendLine("${toolDate(event.time)} · ${event.kind.title}")
                        appendLine(event.detail)
                        event.request?.let { appendLine("Заявка SimpleOne: ${it.number} (${it.sysId}); локальная привязка") }
                        appendLine()
                    }
                }
                withContext(Dispatchers.IO) { requireNotNull(context.contentResolver.openOutputStream(uri, "wt")).bufferedWriter().use { it.write(report) } }
                model.notice("История экспортирована.")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { model.notice("Не удалось экспортировать историю. Проверьте доступ к выбранному файлу.") }
        }
    }
    LaunchedEffect(model, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            model.usb.start(); model.usb.scan(); model.refresh()
            try {
                while (true) {
                    delay(2_000)
                    model.usb.scan()
                    if (model.state.value.files.any { it.downloading }) model.refresh(verifyHash = false)
                }
            } finally { model.usb.close() }
        }
    }
    // Entering this route again (including return from FTP) starts the lifecycle block above and revalidates the selection.
    LaunchedEffect(section) { if (section == ToolsSection.Firmware) model.refresh() }
    LaunchedEffect(state.notice) { state.notice?.let { snackbar.showSnackbar(it); model.notice(null) } }
    AppHeaderSegments(ToolsSection.entries.map { it.title }, section.ordinal, { section = ToolsSection.entries[it] })
    Scaffold(modifier = Modifier.background(appScreenBackground()), containerColor = androidx.compose.ui.graphics.Color.Transparent, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.TopCenter) {
            AppRefreshContainer(Modifier.widthIn(max = if (widthClass == AppWidthClass.Expanded) 1180.dp else 720.dp).fillMaxSize(), isRefreshing = state.loading, onRefresh = { model.usb.scan(); model.refresh() }) {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (state.snapshot.operation.phase != UpdatePhase.Idle && section != ToolsSection.Device) item {
                        Text(state.snapshot.operation.phase.title, style = MaterialTheme.typography.labelLarge)
                    }
                    when (section) {
                        ToolsSection.Device -> item {
                            ToolsDeviceWorkspace(usb, model.usb::scan, model.usb::requestPermission,
                                model.usb::probe, model.usb::cancelProbe, onWiki,
                                firmwareBusy = state.globalInstallBusy, onFirmware = { section = ToolsSection.Firmware })
                        }
                        ToolsSection.Firmware -> {
                            item { PaxFirmwarePanel(state, usb,
                                onImport = { importFirmware.launch(arrayOf("application/zip", "application/octet-stream")) },
                                onFtp = onPaxFtp, onDevice = { section = ToolsSection.Device }, onStart = model::startFirmware) }
                            item { Text("ZIP из папки прошивок PAX в FTP", style = MaterialTheme.typography.titleMedium) }
                            item { AppTextField(query, { query = it }, "Поиск комплектов PAX", search = true, singleLine = true) }
                            item { Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FileFilter.entries.forEach { item -> FilterChip(selected = filter == item, onClick = { filter = item }, label = { Text(item.title) }) }
                            } }
                            item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                TextButton(onClick = onPaxFtp) { Text("Загрузить через FTP") }
                                TextButton(onClick = { model.refresh() }, enabled = !state.loading && !state.checking) { Text("Обновить список") }
                            } }
                            if (paxFiles.isEmpty() && !state.loading) item { ToolCard("Скачанных комплектов PAX нет") {
                                Text("Загрузите ZIP из папки Verifone_Pax/Прошивки или выберите полный комплект из памяти устройства. Файлы других производителей доступны в разделе FTP.")
                                TextButton(onClick = onPaxFtp) { Text("Открыть прошивки PAX") }
                            } }
                            val visible = paxFiles.filter { file ->
                                (query.isBlank() || file.name.contains(query.trim(), true)) && when (filter) {
                                    FileFilter.All -> true
                                    FileFilter.Previous -> file.id == state.snapshot.selection?.file?.id
                                }
                            }
                            if (visible.isEmpty() && paxFiles.isNotEmpty()) item { Text("По заданным условиям файлы не найдены.") }
                            items(visible, key = { it.id }) { file ->
                                val selected = state.snapshot.selection?.takeIf { it.file.id == file.id }
                                AppCard(onClick = { model.inspect(file) }, enabled = !file.downloading && !state.checking && !state.loading, modifier = Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text(file.name, style = MaterialTheme.typography.titleMedium)
                                        if (selected != null) Text("Выбран ранее · ${toolDate(selected.selectedAt)}", color = MaterialTheme.colorScheme.primary)
                                        FileDetails(file, selected?.sha256)
                                        if (file.downloading) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Файл загружается через FTP. Выбор доступен после завершения.") }
                                        else Text("Совместимость с терминалом не подтверждена", style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                            item { ToolCard("Проверка перед передачей") {
                                Text("Имя и расширение файла не подтверждают совместимость. SHA-256 проверяет неизменность выбранных байтов; без официального эталона он не подтверждает подлинность прошивки.")
                                Text("Полная установка поддерживается только для проверенного комплекта PAX D270 в карточке выше. Остальные файлы доступны для локальной проверки.", style = MaterialTheme.typography.bodyMedium)
                            } }
                        }
                        ToolsSection.History -> {
                            item { Text("События хранятся на этом устройстве. Привязка к заявке локальная; вложения в SimpleOne не отправляются.", style = MaterialTheme.typography.bodyMedium) }
                            if (state.snapshot.history.isEmpty()) item { Text("История операций пуста.") }
                            else item { TextButton(onClick = { export.launch("Инженер-инструменты.txt") }) { Text("Экспортировать историю") } }
                            items(state.snapshot.history, key = { it.id }) { event -> ToolCard(event.kind.title) {
                                Text(toolDate(event.time), style = MaterialTheme.typography.labelMedium)
                                SelectionContainer { Text(event.detail, style = MaterialTheme.typography.bodyMedium) }
                                event.request?.let { Text("Заявка: ${it.number}") }
                                TextButton(onClick = { binding = event.id; requestSearch = "" }, enabled = !state.loading && !state.checking) { Text("Привязать к заявке SimpleOne") }
                            } }
                        }
                    }
                }
            }
        }
    }
    state.inspected?.let { file ->
        val selected = state.snapshot.selection?.takeIf { it.file.id == file.id }
        val available = state.files.any { it.id == file.id && !it.downloading }
        AlertDialog(onDismissRequest = model::closeFile, title = { Text(file.name) }, text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item { FileDetails(file, selected?.sha256) }
                if (!available) item {
                    Text("Файл отсутствует или недоступен. Выберите другой файл или загрузите его через FTP.", color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { model.closeFile(); onFtp() }) { Text("Загрузить файл через FTP") }
                }
                item { Text(state.checkMessage ?: "После выбора приложение проверит доступность и SHA-256, затем сохранит ID загрузки FTP. Совместимость и протокол записи не подтверждены; передача недоступна.") }
                if (file.name.equals("update.zip", true)) item { Text("Имя update.zip не подтверждает совместимость. Установка на терминал недоступна.", style = MaterialTheme.typography.bodyMedium) }
                if (state.checking) item { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Проверка файла и SHA-256…") }
                if (available && file.name.endsWith(".zip", true)) item {
                    TextButton(onClick = { model.prepareFirmwareFromFtp(file.id) }, enabled = !state.firmwareBusy && !state.globalInstallBusy) { Text("Подготовить полный комплект PAX") }
                }
                if (selected != null) item { TextButton(onClick = { model.clearSelection() }, enabled = !state.checking) { Text("Сбросить сохранённый выбор") } }
            }
        }, confirmButton = {
            TextButton(onClick = { if (selected == null) model.choose(file.id) else model.check(file.id) }, enabled = !state.checking && !state.loading && available) {
                Text(if (selected == null) "Выбрать и проверить" else "Проверить повторно")
            }
        }, dismissButton = { TextButton(onClick = model::closeFile, enabled = !state.checking) { Text("Закрыть") } })
    }
    binding?.let { eventId ->
        val available = (requests.activeRequests + requests.closedArchive.requests).filter { it.sysId.isNotBlank() && it.number.isNotBlank() }.distinctBy { it.sysId }
        AlertDialog(onDismissRequest = { binding = null }, title = { Text("Заявка SimpleOne") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AppTextField(requestSearch, { requestSearch = it }, "Номер заявки", search = true, singleLine = true)
                if (available.isEmpty()) Text("Нет сохранённых заявок. Сначала загрузите заявки в разделе «Заявки».")
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(available.filter { it.number.contains(requestSearch.trim(), true) }, key = { it.sysId }) { request ->
                        TextButton(onClick = { if (model.bind(eventId, ToolRequestRef(request.sysId, request.number))) binding = null }) { Text(request.number) }
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = { binding = null }) { Text("Закрыть") } },
            dismissButton = { TextButton(onClick = { if (model.bind(eventId, null)) binding = null }) { Text("Убрать привязку") } })
    }
}

@Composable internal fun ToolCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    AppCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium); content()
    } }
}
@Composable private fun FileDetails(file: LocalToolFile, hash: String?) {
    SelectionContainer { Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Размер: ${android.text.format.Formatter.formatFileSize(LocalContext.current, file.size)} · загрузка: ${toolDate(file.downloadedAt)}", style = MaterialTheme.typography.bodySmall)
        Text("SHA-256: ${hash ?: "будет рассчитан при выборе"}", style = MaterialTheme.typography.bodySmall)
    } }
}
private fun toolDate(time: Long): String = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(time))
