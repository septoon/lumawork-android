package ru.lumastack.lumawork.ui.timereport

import ru.lumastack.lumawork.ui.theme.AppContentHorizontalPadding
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import ru.lumastack.lumawork.BuildConfig
import ru.lumastack.lumawork.data.timereport.TimeReportRepository
import ru.lumastack.lumawork.domain.timereport.TimeReportEntry
import ru.lumastack.lumawork.domain.timereport.timeReportMonthGroups
import ru.lumastack.lumawork.ui.adaptive.AppWidthClass
import ru.lumastack.lumawork.ui.requests.RequestsActions
import ru.lumastack.lumawork.ui.requests.RequestsBrowser
import ru.lumastack.lumawork.ui.simpleone.SimpleOneUiState
import ru.lumastack.lumawork.ui.shell.AppHeaderActions
import ru.lumastack.lumawork.ui.theme.AppButton
import ru.lumastack.lumawork.ui.theme.AppFormDialog
import ru.lumastack.lumawork.ui.theme.AppTextField

@Composable
fun TimeReportRoute(
    ownerUserId: String,
    startOffline: Boolean,
    simpleOneState: SimpleOneUiState,
    repository: TimeReportRepository,
    widthClass: AppWidthClass,
    requestsActions: RequestsActions,
    modifier: Modifier = Modifier,
) {
    val model: TimeReportViewModel = viewModel(key = "time-report-$ownerUserId-$startOffline",
        factory = TimeReportViewModel.factory(repository, ownerUserId, startOffline))
    val state by model.state.collectAsStateWithLifecycle()
    val userId = simpleOneState.snapshot.currentUser?.sysId.orEmpty()
    var loginOpen by rememberSaveable(ownerUserId) { mutableStateOf(false) }
    var loginSubmitted by remember(ownerUserId) { mutableStateOf(false) }
    var browser by remember(ownerUserId) { mutableStateOf<Pair<String, String>?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var fileBusy by remember { mutableStateOf(false) }
    val snackbars = remember { SnackbarHostState() }
    val groups = state.monthGroups
    LaunchedEffect(userId, simpleOneState.isAuthorized, simpleOneState.snapshot.sessionFingerprint) {
        model.activate(userId, simpleOneState.isAuthorized, simpleOneState.snapshot.sessionFingerprint)
    }
    LaunchedEffect(loginSubmitted, simpleOneState.isLoading, simpleOneState.isResolvingUser, userId, simpleOneState.snapshot.sessionFingerprint) {
        if (loginSubmitted && !simpleOneState.isLoading && !simpleOneState.isResolvingUser &&
            simpleOneState.isAuthorized && userId.isNotBlank()) {
            if (model.loadAfterActivation()) {
                loginSubmitted = false
                loginOpen = false
            }
        }
    }
    LaunchedEffect(state.errorMessage, state.notice) {
        if (state.snapshot != null) (state.errorMessage ?: state.notice)?.let {
            snackbars.showSnackbar(it)
            model.clearMessage()
        }
    }
    fun refresh() {
        if (fileBusy) return
        if (startOffline || state.isAuthorized) model.refresh()
        else { requestsActions.onDismissConnection(); loginOpen = true }
    }
    fun openBrowser(entry: TimeReportEntry? = null) {
        if (startOffline) { model.reportInputError("Для открытия SimpleOne требуется подключение к сети."); return }
        if (entry != null && entry.simpleOneRecordId.isBlank()) {
            model.reportInputError("У этой архивной строки нет ID SimpleOne. Обновите трудозатраты из SimpleOne."); return
        }
        scope.launch {
            val auth = requestsActions.browserAuthKey()
            if (auth.isNullOrBlank()) { model.reportInputError("Сначала войдите в SimpleOne."); loginOpen = true }
            else {
                val url = BuildConfig.SIMPLEONE_API_ORIGIN.removeSuffix("/v1").trimEnd('/').toHttpUrl().newBuilder()
                    .addPathSegments("record/itsm_tchnsrv_time_report")
                entry?.let { url.addPathSegment(it.simpleOneRecordId) }
                browser = url.addQueryParameter("form_view", "Внешняя система").build().toString() to auth
            }
        }
    }
    val importFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
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
                    model.importXlsx(bytes)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { model.reportInputError(error.message ?: "Не удалось загрузить XLSX.") }
                finally { fileBusy = false }
            }
        }
    }
    val exportFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(XLSX_TYPE)) { uri ->
        if (uri != null) {
            fileBusy = true
            scope.launch {
                try {
                    val bytes = model.exportXlsx()
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) } ?: error("Не удалось сохранить файл.")
                    }
                    snackbars.showSnackbar("Экспорт трудозатрат XLSX готов.")
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { model.reportInputError(error.message ?: "Не удалось сохранить XLSX.") }
                finally { fileBusy = false }
            }
        }
    }
    Box(modifier.fillMaxSize()) {
        if (state.snapshot == null) {
            Box(Modifier.fillMaxSize().padding(horizontal = AppContentHorizontalPadding, vertical = 24.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    AppButton(onClick = ::refresh, enabled = !state.isInitializing && !state.isLoading && !simpleOneState.isInitializing) {
                        if (state.isLoading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text("Загрузить трудозатраты")
                    }
                    state.progress?.let { Text(it) }
                    state.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            }
        } else TimeReportScreen(state, widthClass, ::refresh, model::openMonth, model::closeMonth,
            model::deleteAll, model::deleteRange,
            toolbar = {
                if (!state.isAuthorized && !startOffline) ru.lumastack.lumawork.ui.requests.SimpleOneConnectionCard(
                    simpleOneState.copy(isLoading = simpleOneState.isLoading || simpleOneState.isResolvingUser),
                    requestsActions.copy(onSignIn = { loginSubmitted = true; requestsActions.onSignInIdentityOnly() }),
                )
            }, headerActions = { deleteRange, deleteAll ->
                var menuOpen by remember { mutableStateOf(false) }
                var monthsOpen by remember { mutableStateOf(false) }
                val busy = state.isLoading || state.isDeleting || fileBusy
                AppHeaderActions(title = {
                    Box(Modifier.widthIn(max = 280.dp)) {
                        TextButton(onClick = { monthsOpen = true }, modifier = Modifier.semantics { contentDescription = "Период трудозатрат" }) {
                            Text(state.filterMonthId?.let(::timeReportMonthTitle) ?: "Весь период",
                                modifier = Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Icon(Icons.Default.KeyboardArrowDown, null, Modifier.padding(start = 4.dp).size(18.dp))
                        }
                        DropdownMenu(expanded = monthsOpen, onDismissRequest = { monthsOpen = false }) {
                            DropdownMenuItem(text = { Text("Весь период") }, onClick = { model.filterMonth(null); monthsOpen = false })
                            groups.forEach { group -> DropdownMenuItem(text = { Text(timeReportMonthTitle(group.monthId)) },
                                onClick = { model.filterMonth(group.monthId); monthsOpen = false }) }
                        }
                    }
                }) {
                    Box {
                        IconButton(onClick = { menuOpen = true }, enabled = !busy) { Icon(Icons.Default.MoreVert, "Меню трудозатрат", Modifier.rotate(90f)) }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(text = { Text("Создать") }, onClick = { menuOpen = false; openBrowser() }, enabled = !startOffline)
                            DropdownMenuItem(text = { Text("Обновить из SimpleOne") }, onClick = { menuOpen = false; refresh() }, enabled = !startOffline)
                            DropdownMenuItem(text = { Text("Обновить XLSX") }, onClick = { menuOpen = false; importFile.launch(arrayOf(XLSX_TYPE)) })
                            DropdownMenuItem(text = { Text("Экспорт XLSX") }, enabled = state.snapshot?.entries?.isNotEmpty() == true,
                                onClick = { menuOpen = false; exportFile.launch("Инженер-time-report-${LocalDate.now()}.xlsx") })
                            HorizontalDivider()
                            DropdownMenuItem(text = { Text("Удалить за период…") }, enabled = groups.isNotEmpty(),
                                onClick = { menuOpen = false; deleteRange() })
                            DropdownMenuItem(text = { Text("Удалить все трудозатраты", color = MaterialTheme.colorScheme.error) },
                                enabled = state.snapshot?.entries?.isNotEmpty() == true, onClick = { menuOpen = false; deleteAll() })
                        }
                    }
                }
            }, onOpenEntry = ::openBrowser)
        SnackbarHost(snackbars, Modifier.align(Alignment.BottomCenter))
    }
    if (loginOpen) AppFormDialog("Вход в SimpleOne", { loginOpen = false; loginSubmitted = false; requestsActions.onDismissConnection() },
        "Войти и загрузить", { loginSubmitted = true; requestsActions.onSignInIdentityOnly() },
        isBusy = simpleOneState.isLoading || simpleOneState.isResolvingUser,
        confirmEnabled = simpleOneState.username.isNotBlank() && simpleOneState.password.isNotBlank(),
        errorMessage = simpleOneState.errorMessage, maxWidth = 520.dp) {
        item { AppTextField(simpleOneState.username, requestsActions.onUsernameChange, "Логин", singleLine = true) }
        item { AppTextField(simpleOneState.password, requestsActions.onPasswordChange, "Пароль", singleLine = true, visualTransformation = PasswordVisualTransformation()) }
    }
    browser?.let { (url, auth) -> RequestsBrowser(url, auth) { browser = null } }
}

private const val XLSX_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
private fun timeReportMonthTitle(id: String): String = runCatching {
    LocalDate.parse("$id-01").format(DateTimeFormatter.ofPattern("LLLL yyyy", Locale.forLanguageTag("ru")))
        .replaceFirstChar { it.titlecase(Locale.forLanguageTag("ru")) }
}.getOrDefault(id)
