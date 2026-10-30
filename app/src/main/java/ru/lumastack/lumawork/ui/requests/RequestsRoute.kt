package ru.lumastack.lumawork.ui.requests

import ru.lumastack.lumawork.data.requests.ClientPersonalCommentsRepository
import ru.lumastack.lumawork.ui.theme.AppContentHorizontalPadding
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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import ru.lumastack.lumawork.data.simpleone.SimpleOneRepository
import ru.lumastack.lumawork.domain.model.AppSession
import ru.lumastack.lumawork.domain.requests.*
import ru.lumastack.lumawork.domain.simpleone.RequestsMode
import ru.lumastack.lumawork.ui.adaptive.AppWidthClass
import ru.lumastack.lumawork.ui.simpleone.SimpleOneUiState
import ru.lumastack.lumawork.ui.shell.AppHeaderActions
import ru.lumastack.lumawork.ui.theme.AppFormDialog
import ru.lumastack.lumawork.ui.theme.AppTextField

internal val LocalClientPersonalCommentsRepository = staticCompositionLocalOf<ClientPersonalCommentsRepository> {
    error("ClientPersonalCommentsRepository is provided by AppShell")
}

@Composable
fun RequestsRoute(session: AppSession, repository: SimpleOneRepository, state: SimpleOneUiState,
    widthClass: AppWidthClass, actions: RequestsActions) {
    key(session.user.id, session.token) { RequestsRouteContent(session, repository, state, widthClass, actions) }
}

@Composable
private fun RequestsRouteContent(session: AppSession, repository: SimpleOneRepository, state: SimpleOneUiState,
    widthClass: AppWidthClass, actions: RequestsActions) {
    val json = remember { Json { ignoreUnknownKeys = true; encodeDefaults = true } }
    val records = remember(state.activeRequests, state.closedArchive.requests) { state.activeRequests + state.closedArchive.requests }
    val comments = rememberClientPersonalCommentsController(LocalClientPersonalCommentsRepository.current, state.isOffline, records)
    val commentRequests = remember { mutableStateMapOf<String, ru.lumastack.lumawork.domain.simpleone.SimpleOneRequest>() }
    val commentLookupLimit = remember { Semaphore(4) }
    var terminal by rememberSaveable { mutableStateOf<String?>(null) }
    Box(Modifier.fillMaxSize()) {
    RequestsScreen(state, widthClass, actions,
        toolbar = { RequestsToolbar(state, actions, comments) },
        personalComment = { request ->
            val key = "${request.id}|${request.sysUpdatedAt}"
            val resolved = commentRequests[key] ?: request
            LaunchedEffect(request, comments.comments, state.isOffline, state.isAuthorized) {
                if (!state.isOffline && state.isAuthorized && comments.comments.isNotEmpty() && request.sysId.isNotBlank() &&
                    request.clientCommentTIN().isEmpty() && comments.commentFor(request) == null && key !in commentRequests) {
                    try {
                        val detail = commentLookupLimit.withPermit { repository.cachedRequestDetails(session.user.id, request) }
                        if (detail != null) commentRequests[key] = detail
                    } catch (error: CancellationException) { throw error }
                    catch (_: Exception) { /* The add action remains available when SimpleOne is unavailable. */ }
                }
            }
            comments.commentFor(resolved)?.let { comment ->
                ClientPersonalCommentBlock(comment, onEdit = if (comments.canEdit(resolved)) ({ comments.openEditor(resolved) }) else null)
            } ?: run {
                if (comments.canEdit(resolved)) TextButton(onClick = { comments.openEditor(resolved) }) { Text("Добавить личный комментарий") }
            }
        },
        onOpenTerminal = { terminal = it },
        detail = { request -> RequestsDetailContent(request, state, actions, comments, session,
            repository, json, onOpenTerminal = { terminal = it }) })
    ClientPersonalCommentsHost(comments, Modifier.align(Alignment.BottomCenter))
    }
    if (state.showConnection) RequestsLoginDialog(state, actions)
    terminal?.let { id -> WarehouseRequestsDialog(id, repository, session.user.id, state.isOffline, { terminal = null }) }
}

@Composable
private fun RequestsLoginDialog(state: SimpleOneUiState, actions: RequestsActions) {
    AppFormDialog("Вход в SimpleOne", actions.onDismissConnection, "Войти и загрузить", actions.onSignIn,
        isBusy = state.isLoading, confirmEnabled = state.username.isNotBlank() && state.password.isNotBlank(),
        errorMessage = state.errorMessage, maxWidth = 520.dp) {
        item(key = "simpleone:username") { AppTextField(state.username, actions.onUsernameChange, "Логин", singleLine = true, enabled = !state.isLoading) }
        item(key = "simpleone:password") { AppTextField(state.password, actions.onPasswordChange, "Пароль", singleLine = true,
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
    val refreshingComments by comments.repository.isRefreshing.collectAsState()
    val archiveBusy = state.isClosedLoading || state.isRestoringClosedArchive || fileBusy
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
            IconButton(onClick = { expanded = true }) { Icon(Icons.Default.MoreVert, "Меню заявок", Modifier.rotate(90f)) }
            DropdownMenu(expanded, { expanded = false }) {
                fun act(action: () -> Unit) { expanded = false; action() }
                DropdownMenuItem({ Text("Обновить активные из SimpleOne") }, { act(actions.onRefreshActive) }, enabled = !state.isOffline && !state.isRefreshingActive)
                DropdownMenuItem({ Text("Обновить личные комментарии") }, { act { scope.launch { comments.repository.refresh({ comments.offline }, force = true) } } }, enabled = !state.isOffline && !refreshingComments)
                DropdownMenuItem({ Text("Обновить закрытые из SimpleOne") }, { act(actions.onRefreshClosed) }, enabled = !state.isOffline && !archiveBusy)
                DropdownMenuItem({ Text("Комментарий") }, { act { comments.openEditor() } }, enabled = !state.isOffline)
                DropdownMenuItem({ Text("Выйти из SimpleOne") }, { act(actions.onSignOut) }, enabled = state.isAuthorized)
                HorizontalDivider()
                DropdownMenuItem({ Text(if (state.closedArchive.requests.isEmpty()) "Загрузить заявки" else "Обновить заявки") },
                    { act { import.launch(arrayOf("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")) } }, enabled = !archiveBusy)
                DropdownMenuItem({ Text("Экспорт заявок XLSX") }, { act { export.launch("Инженер.xlsx") } }, enabled = !archiveBusy && state.closedArchive.requests.isNotEmpty())
                if (state.mode == RequestsMode.Closed) {
                    HorizontalDivider()
                    DropdownMenuItem({ Text("Удалить за период…") }, { act { cleanupPeriod = true } }, enabled = !archiveBusy && dates.isNotEmpty())
                    DropdownMenuItem({ Text("Удалить все закрытые заявки", color = MaterialTheme.colorScheme.error) }, { act { cleanupAll = true } }, enabled = !archiveBusy && state.closedArchive.requests.isNotEmpty())
                }
            }
        }
    }
    Column {
        if (fileBusy) {
            CircularProgressIndicator(Modifier.padding(horizontal = AppContentHorizontalPadding).size(20.dp), strokeWidth = 2.dp)
        }
        val error = if (state.mode == RequestsMode.Active) state.errorMessage else state.closedErrorMessage
        error?.let { Text(it, Modifier.padding(horizontal = AppContentHorizontalPadding), color = MaterialTheme.colorScheme.error) }
        if (state.mode == RequestsMode.Closed && !state.initialArchiveReady && !archiveBusy) {
            TextButton(onClick = actions.onRefreshClosed, enabled = !state.isOffline && state.isAuthorized) { Text("Загрузить закрытый архив") }
        }
        message?.let { text -> Row(Modifier.padding(horizontal = AppContentHorizontalPadding), verticalAlignment = Alignment.CenterVertically) {
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
