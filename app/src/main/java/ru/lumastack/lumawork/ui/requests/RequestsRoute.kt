package ru.lumastack.lumawork.ui.requests

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import ru.lumastack.lumawork.BuildConfig
import ru.lumastack.lumawork.data.simpleone.SimpleOneRepository
import ru.lumastack.lumawork.domain.model.AppSession
import ru.lumastack.lumawork.domain.requests.*
import ru.lumastack.lumawork.domain.simpleone.RequestsMode
import ru.lumastack.lumawork.ui.adaptive.AppWidthClass
import ru.lumastack.lumawork.ui.simpleone.SimpleOneUiState
import ru.lumastack.lumawork.ui.shell.AppHeaderActions
import ru.lumastack.lumawork.ui.theme.AppButton
import ru.lumastack.lumawork.ui.theme.AppFormDialog
import ru.lumastack.lumawork.ui.theme.AppTextField

@Composable
fun RequestsRoute(session: AppSession, repository: SimpleOneRepository, state: SimpleOneUiState,
    widthClass: AppWidthClass, actions: RequestsActions) {
    key(session.user.id, session.token) { RequestsRouteContent(session, repository, state, widthClass, actions) }
}

@Composable
private fun RequestsRouteContent(session: AppSession, repository: SimpleOneRepository, state: SimpleOneUiState,
    widthClass: AppWidthClass, actions: RequestsActions) {
    if (!state.initialArchiveReady) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                AppButton(onClick = actions.onInitialLoad, enabled = !state.isInitializing && !state.isClosedLoading && !state.isLoading) {
                    if (state.isClosedLoading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text("Загрузить заявки")
                }
                if (state.initialLoadRequested) {
                    state.archiveProgress?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    if (!state.showConnection) state.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            }
        }
        if (state.showConnection) RequestsLoginDialog(state, actions)
        return
    }
    if (!state.isAuthorized && !state.isOffline) {
        Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 520.dp)) { SimpleOneConnectionCard(state, actions) }
        }
        return
    }
    val json = remember { Json { ignoreUnknownKeys = true; encodeDefaults = true } }
    val records = remember(state.activeRequests, state.closedArchive.requests) { state.activeRequests + state.closedArchive.requests }
    val comments = rememberClientPersonalCommentsController(session, state.isOffline, records, BuildConfig.API_ORIGIN, json)
    var terminal by rememberSaveable { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val screenActions = actions.copy(onRefresh = {
        actions.onRefresh()
        if (state.mode == RequestsMode.Active) scope.launch { comments.repository.refresh({ comments.offline }, force = true) }
    })
    Box(Modifier.fillMaxSize()) {
    RequestsScreen(state, widthClass, screenActions,
        toolbar = { RequestsToolbar(state, actions, comments) },
        personalComment = { request -> comments.commentFor(request)?.let { comment ->
            ClientPersonalCommentBlock(comment, onEdit = if (comments.canEdit(request)) ({ comments.openEditor(request) }) else null)
        } ?: run {
            if (comments.canEdit(request)) TextButton(onClick = { comments.openEditor(request) }) { Text("Добавить личный комментарий") }
        } },
        onOpenTerminal = { terminal = it },
        detail = { request -> RequestsDetailContent(request, state, actions, comments, session,
            repository, json, onOpenTerminal = { terminal = it }) })
    ClientPersonalCommentsHost(comments, Modifier.align(Alignment.BottomCenter))
    }
    terminal?.let { id -> WarehouseRequestsDialog(id, repository, session.user.id, state.isOffline, { terminal = null }) }
}

@Composable
private fun RequestsLoginDialog(state: SimpleOneUiState, actions: RequestsActions) {
    AppFormDialog("Вход в SimpleOne", actions.onDismissConnection, "Войти и загрузить", actions.onSignIn,
        isBusy = state.isLoading, confirmEnabled = state.username.isNotBlank() && state.password.isNotBlank(),
        errorMessage = state.errorMessage, maxWidth = 520.dp) {
        item { AppTextField(state.username, actions.onUsernameChange, "Логин", singleLine = true, enabled = !state.isLoading) }
        item { AppTextField(state.password, actions.onPasswordChange, "Пароль", singleLine = true,
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), enabled = !state.isLoading) }
    }
}

@Composable
private fun RequestsToolbar(state: SimpleOneUiState, actions: RequestsActions, comments: ClientPersonalCommentsController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf(false) }
    var fileBusy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var cleanupAll by remember { mutableStateOf(false) }
    var cleanupPeriod by remember { mutableStateOf(false) }
    var pendingPeriod by remember { mutableStateOf<ClosedRequestsPeriod?>(null) }
    var confirmPeriod by remember { mutableStateOf(false) }
    val busy = state.isLoading || state.isClosedLoading || fileBusy
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            fileBusy = true
            scope.launch {
                try {
                    val bytes = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            val output = ByteArrayOutputStream()
                            val buffer = ByteArray(8192)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                require(output.size() + count <= 30 * 1024 * 1024) { "Размер файла превышает 30 МиБ." }
                                output.write(buffer, 0, count)
                            }
                            output.toByteArray()
                        } ?: error("Не удалось открыть файл.")
                    }
                    actions.onImportArchive(bytes)
                } catch (error: CancellationException) { throw error }
                catch (error: Exception) { message = error.message ?: "Не удалось загрузить XLSX." }
                finally { fileBusy = false }
            }
        }
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")) { uri ->
        if (uri != null) {
            fileBusy = true
            scope.launch {
                try {
                    val bytes = actions.onExportArchive()
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) } ?: error("Не удалось сохранить файл.")
                    }
                    message = "Заявки сохранены в XLSX."
                } catch (error: CancellationException) { throw error }
                catch (error: Exception) { message = error.message ?: "Не удалось сохранить XLSX." }
                finally { fileBusy = false }
            }
        }
    }
    // Dates have already been parsed once along with the cached search index.
    val dates = remember(state.closedArchiveItems) { state.closedArchiveItems.mapNotNull { it.day } }
    AppHeaderActions {
        Box {
            IconButton(onClick = { expanded = true }, enabled = !busy) { Icon(Icons.Default.MoreVert, "Меню заявок", Modifier.rotate(90f)) }
            DropdownMenu(expanded, { expanded = false }) {
                fun act(action: () -> Unit) { expanded = false; action() }
                DropdownMenuItem({ Text("Обновить SimpleOne") }, { act { actions.onRefreshActive(); scope.launch { comments.repository.refresh({ comments.offline }, force = true) } } }, enabled = !state.isOffline)
                DropdownMenuItem({ Text("Обновить закрытые из SimpleOne") }, { act(actions.onRefreshClosed) }, enabled = !state.isOffline)
                DropdownMenuItem({ Text("Комментарий") }, { act { comments.openEditor() } }, enabled = !state.isOffline)
                DropdownMenuItem({ Text("Выйти из SimpleOne") }, { act(actions.onSignOut) }, enabled = state.isAuthorized)
                HorizontalDivider()
                DropdownMenuItem({ Text(if (state.closedArchive.requests.isEmpty()) "Загрузить заявки" else "Обновить заявки") },
                    { act { import.launch(arrayOf("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")) } })
                DropdownMenuItem({ Text("Экспорт заявок XLSX") }, { act { export.launch("Инженер.xlsx") } }, enabled = state.closedArchive.requests.isNotEmpty())
                if (state.mode == RequestsMode.Closed) {
                    HorizontalDivider()
                    DropdownMenuItem({ Text("Удалить за период…") }, { act { cleanupPeriod = true } }, enabled = dates.isNotEmpty())
                    DropdownMenuItem({ Text("Удалить все закрытые заявки", color = MaterialTheme.colorScheme.error) }, { act { cleanupAll = true } }, enabled = state.closedArchive.requests.isNotEmpty())
                }
            }
        }
    }
    Column {
        if (busy) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.End) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        }
        state.archiveProgress?.let { Text(it, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall) }
        state.errorMessage?.let { Text(it, Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error) }
        message?.let { text -> Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { message = null }) { Text("Закрыть") }
        } }
    }
    if (cleanupPeriod && dates.isNotEmpty()) ClosedRequestsPeriodDialog(ClosedRequestsPeriod(dates.min(), dates.max()), null,
        { cleanupPeriod = false }, { selected -> cleanupPeriod = false; if (selected != null) { pendingPeriod = selected; confirmPeriod = true } })
    if (cleanupAll || confirmPeriod) AlertDialog(onDismissRequest = { cleanupAll = false; confirmPeriod = false },
        title = { Text(if (cleanupAll) "Удалить все закрытые заявки с устройства?" else "Удалить заявки за выбранный период?") },
        text = {
            val formatter = java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy")
            val period = pendingPeriod
            val count = if (cleanupAll) state.closedArchive.requests.size else dates.count { period != null && it in period }
            Text("Заявок к удалению: $count. " + if (cleanupAll) {
                "Локальный архив будет удалён. Данные в SimpleOne не изменятся и могут загрузиться снова при следующем обновлении."
            } else {
                "Период: ${period?.start?.format(formatter)} — ${period?.end?.format(formatter)}. Данные в SimpleOne не изменятся и могут загрузиться снова при следующем обновлении. Заявки без даты сохранятся."
            })
        },
        confirmButton = { TextButton(onClick = { actions.onCleanupArchive(if (cleanupAll) null else pendingPeriod); cleanupAll = false; confirmPeriod = false }) { Text("Удалить", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { cleanupAll = false; confirmPeriod = false }) { Text("Отмена") } })
}
