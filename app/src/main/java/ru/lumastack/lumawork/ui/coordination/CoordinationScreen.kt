package ru.lumastack.lumawork.ui.coordination

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import ru.lumastack.lumawork.ui.shell.AppHeaderBackHandler as BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import ru.lumastack.lumawork.R
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import ru.lumastack.lumawork.BuildConfig
import ru.lumastack.lumawork.data.coordination.CoordinationRepository
import ru.lumastack.lumawork.data.coordination.GroupClosedRequestsState
import ru.lumastack.lumawork.data.simpleone.SimpleOneRepository
import ru.lumastack.lumawork.domain.coordination.*
import ru.lumastack.lumawork.domain.model.AppSession
import ru.lumastack.lumawork.domain.simpleone.SimpleOneRequest
import ru.lumastack.lumawork.ui.adaptive.AppWidthClass
import ru.lumastack.lumawork.ui.requests.*
import ru.lumastack.lumawork.ui.simpleone.SimpleOneUiState
import ru.lumastack.lumawork.ui.theme.AppRefreshContainer
import ru.lumastack.lumawork.ui.shell.AppHeaderSegments
import ru.lumastack.lumawork.ui.theme.AppCard

@Composable
fun CoordinationRoute(ownerUserId: String, startOffline: Boolean, simpleOneState: SimpleOneUiState,
    coordinationRepository: CoordinationRepository, simpleOneRepository: SimpleOneRepository, widthClass: AppWidthClass,
    modifier: Modifier = Modifier, session: AppSession? = null, requestsActions: RequestsActions? = null,
) {
    key(ownerUserId, session?.token, startOffline) {
        CoordinationRouteContent(ownerUserId, startOffline, simpleOneState, coordinationRepository, simpleOneRepository, widthClass, modifier, session, requestsActions)
    }
}

@Composable
private fun CoordinationRouteContent(owner: String, offline: Boolean, simpleOne: SimpleOneUiState,
    repository: CoordinationRepository, simpleOneRepository: SimpleOneRepository, width: AppWidthClass,
    modifier: Modifier, session: AppSession?, requestsActions: RequestsActions?,
) {
    val model: CoordinationViewModel = viewModel(key = "coordination-$owner-$offline", factory = CoordinationViewModel.factory(repository, simpleOneRepository, owner, offline))
    val state by model.state.collectAsStateWithLifecycle()
    val archive = repository.groupClosedRepository
    val unscopedArchiveState = archive?.state?.collectAsStateWithLifecycle()?.value
    val archiveState = unscopedArchiveState?.takeIf { it.ownerUserId == owner && it.simpleOneUserId == simpleOne.snapshot.currentUser?.sysId } ?: GroupClosedRequestsState()
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val holder = rememberSaveableStateHolder()
    val json = remember { Json { ignoreUnknownKeys = true; encodeDefaults = true } }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val user = simpleOne.snapshot.currentUser
    LaunchedEffect(user?.sysId, simpleOne.isAuthorized, simpleOne.isInitializing) {
        if (!simpleOne.isInitializing && user == null && simpleOne.isAuthorized && !offline) requestsActions?.onResolveCurrentUser?.invoke()
        model.activate(user?.sysId.orEmpty(), simpleOne.isAuthorized)
    }
    LaunchedEffect(state.isAuthorized, state.simpleOneUserId, archiveState.requiresAuthorization) {
        if (!offline && simpleOne.isAuthorized && ((!state.isAuthorized && state.simpleOneUserId.isNotBlank()) || archiveState.requiresAuthorization)) requestsActions?.onSignOut?.invoke()
    }
    LaunchedEffect(state.isArchiveOpen, user?.sysId, simpleOne.isAuthorized, lifecycle) {
        if (state.isArchiveOpen && archive != null) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            archive.activate(owner, user?.sysId.orEmpty(), simpleOne.isAuthorized, offline)
            while (true) { delay(60_000); if (!offline) archive.refresh() }
        }
    }
    val records = remember(state.snapshot, archiveState.records) {
        state.snapshot.regions.values.flatMap { it.requests } + state.snapshot.returnEquipment?.requests.orEmpty() + archiveState.records
    }
    val comments = session?.let { rememberClientPersonalCommentsController(it, offline, records, BuildConfig.API_ORIGIN, json) }
    var terminal by remember { mutableStateOf<String?>(null) }
    val copy: (String) -> Unit = { text ->
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Координация", text))
        if (Build.VERSION.SDK_INT < 33) scope.launch { snackbar.showSnackbar("Скопировано") }
    }
    if (state.selectedRequest == null && state.selectedEngineerId == null && !state.isArchiveOpen) {
        AppHeaderSegments(CoordinationSection.entries.map { it.title }, state.section.ordinal,
            { model.selectSection(CoordinationSection.entries[it]) }, actions = {
                IconButton(onClick = model::openArchive, enabled = state.isAuthorized && archive != null) {
                    Icon(painterResource(R.drawable.ic_nav_assignment), "Закрытые заявки группы")
                }
            })
    }
    BackHandler(state.selectedRequest != null || state.selectedEngineerId != null || state.isArchiveOpen, model::back)
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        val selected = state.selectedRequest
        if (selected != null && session != null && requestsActions != null && comments != null) {
            RequestsDetailContent(selected,
                simpleOne.copy(isOffline = offline, isAuthorized = state.isAuthorized, isDetailLoading = state.isDetailLoading, detailErrorMessage = state.detailErrorMessage),
                requestsActions.copy(onCloseRequest = model::back, onOpenRequest = { model.openRequest(it, force = true) }),
                comments, session, simpleOneRepository, json, onOpenTerminal = { terminal = it })
        } else if (selected != null) {
            SimpleOneRequestDetailContent(selected, width, state.isDetailLoading, state.detailErrorMessage, model::back)
        } else if (state.isArchiveOpen) {
            holder.SaveableStateProvider("closed-group-${state.simpleOneUserId}") {
                CoordinationGroupClosedContent("$owner-${state.simpleOneUserId}", archiveState, state.isAuthorized, offline,
                    onRefresh = { scope.launch { archive?.refresh(forceFull = true) } }, onOpen = { model.openRequest(it) }, copy = copy)
            }
        } else {
            Column(Modifier.widthIn(max = 1100.dp).fillMaxSize()) {
                if (state.selectedEngineerId != null) Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    val engineer = coordinationEngineers(state.currentGroup?.requests.orEmpty()).firstOrNull { it.id == state.selectedEngineerId }
                    Text(engineer?.name ?: "Заявки", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 2)
                    IconButton(onClick = model::refresh, enabled = state.isAuthorized && !offline && !state.isLoading) { Icon(Icons.Default.Refresh, "Обновить заявки инженера") }
                }
                if (simpleOne.isAuthorized && user == null) Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (simpleOne.isResolvingUser || simpleOne.isLoading || simpleOne.isInitializing) { CircularProgressIndicator(); Text("Проверяю профиль SimpleOne…") }
                    else {
                        CoordinationMessage("Профиль SimpleOne недоступен", simpleOne.errorMessage ?: "Для открытия координации требуется подтверждённый профиль SimpleOne.")
                        TextButton(onClick = { requestsActions?.onResolveCurrentUser?.invoke() }, enabled = !offline) { Text("Повторить") }
                    }
                }
                else if (!state.isAuthorized) CoordinationMessage("SimpleOne не подключён", "Войдите в SimpleOne на экране «Заявки», затем вернитесь в координацию.", Modifier.padding(16.dp))
                else AppRefreshContainer(Modifier.weight(1f), state.isLoading, model::refresh, enabled = !offline) {
                    holder.SaveableStateProvider("active-${state.section}-${state.region}-${state.selectedEngineerId}") {
                        CoordinationActiveContent(state, user?.displayName?.ifBlank { user.username } ?: "Текущий пользователь", width,
                            offline, model::selectRegion, model::openEngineer, { model.openRequest(it) }, copy)
                    }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
        if (comments != null) ClientPersonalCommentsHost(comments, Modifier.align(Alignment.BottomCenter))
    }
    terminal?.let { WarehouseRequestsDialog(it, simpleOneRepository, owner, offline) { terminal = null } }
}

@Composable
private fun CoordinationActiveContent(state: CoordinationUiState, userName: String, width: AppWidthClass, offline: Boolean,
    onRegion: (CoordinationRegion) -> Unit, onEngineer: (String) -> Unit, onRequest: (SimpleOneRequest) -> Unit, copy: (String) -> Unit,
) {
    val group = state.currentGroup
    val requests = remember(group?.requests, state.selectedEngineerId) { if (state.selectedEngineerId != null) state.engineerRequests else group?.requests.orEmpty().sortedCoordinationRequests() }
    val engineers = remember(group?.requests) { coordinationEngineers(group?.requests.orEmpty()) }
    val detail = state.selectedEngineerId != null
    val returning = state.section == CoordinationSection.ReturnEquipment
    val header: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CoordinationSummary(state, userName, engineers, detail, onRegion)
            if (offline) Text("Сохранённые данные. Для обновления подключитесь к сети.", style = MaterialTheme.typography.bodySmall)
            listOfNotNull(state.errorMessage, state.statusErrorMessage).distinct().forEach { error ->
                Text(error, color = MaterialTheme.colorScheme.error)
            }
        }
    }
    if (group != null && requests.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            header()
            ActiveRequestsEmptyState(Modifier.weight(1f).fillMaxWidth())
        }
        return
    }
    LazyVerticalGrid(GridCells.Fixed(if (width == AppWidthClass.Compact) 1 else 2), contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize()) {
        item("summary", span = { GridItemSpan(maxLineSpan) }) {
            header()
        }
        when {
            state.isLoading && group == null -> item("loading", span = { GridItemSpan(maxLineSpan) }) { Row(Modifier.padding(20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) { CircularProgressIndicator(Modifier.size(22.dp)); Text("Загружаю активные заявки") } }
            group == null -> item("no-cache", span = { GridItemSpan(maxLineSpan) }) { CoordinationMessage("Нет сохранённых данных", "Подключитесь к интернету и обновите координацию.") }
            !detail && !returning -> {
                item("engineers-title", span = { GridItemSpan(maxLineSpan) }) { Column { Text("Распределение", style = MaterialTheme.typography.titleMedium); Text("По инженерам и без назначения", style = MaterialTheme.typography.bodySmall) } }
                items(engineers, key = { it.id }) { engineer -> AppCard(onClick = { onEngineer(engineer.id) }) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (engineer.isUnassigned) Icon(painterResource(R.drawable.ic_nav_groups), null, tint = MaterialTheme.colorScheme.primary)
                        else Icon(Icons.Default.Person, null, tint = MaterialTheme.colorScheme.primary)
                        Text(engineer.name, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                        Text(engineer.requestCount.toString(), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
                    }
                } }
            }
            else -> {
                if (returning) item("return-title", span = { GridItemSpan(maxLineSpan) }) { Column { Text("Мои возвраты", style = MaterialTheme.typography.titleMedium); Text("Активные заявки текущего пользователя", style = MaterialTheme.typography.bodySmall) } }
                items(requests, key = { it.id }) { CoordinationRequestCard(it, it.id in state.loadingStatusIds, returning, { onRequest(it) }, copy) }
            }
        }
    }
}

@Composable
private fun CoordinationSummary(state: CoordinationUiState, userName: String, engineers: List<CoordinationEngineer>, detail: Boolean, onRegion: (CoordinationRegion) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    AppCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                if (state.section == CoordinationSection.Distribution) {
                    if (!detail) Text("Регион / город", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Box {
                        if (detail) Text(state.region.title, style = MaterialTheme.typography.titleMedium)
                        else TextButton(onClick = { menu = true }) { Text(state.region.title); Icon(Icons.Default.KeyboardArrowDown, null) }
                        DropdownMenu(menu, { menu = false }) { CoordinationRegion.entries.forEach { region -> DropdownMenuItem({ Text(region.title) }, { menu = false; onRegion(region) },
                            trailingIcon = { if (region == state.region) Icon(Icons.Default.Check, null) }) } }
                    }
                } else { Text("Возврат ТО", style = MaterialTheme.typography.titleMedium); Text(userName, style = MaterialTheme.typography.bodyMedium) }
            }
            if (state.isLoading) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            if (state.section == CoordinationSection.Distribution && !detail) Text("${engineers.count { !it.isUnassigned }} инженеров", style = MaterialTheme.typography.titleMedium)
            Text("${if (detail) state.engineerRequests.size else state.currentGroup?.requests?.size ?: 0} заявок", style = MaterialTheme.typography.titleMedium)
        }
        state.currentGroup?.updatedAtEpochMillis?.let { Text("Обновлено ${COORDINATION_UPDATED.format(Instant.ofEpochMilli(it))}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    } }
}

@Composable
private fun CoordinationRequestCard(request: SimpleOneRequest, loading: Boolean, returning: Boolean, open: () -> Unit, copy: (String) -> Unit) {
    val data = remember(request, loading, returning) { CoordinationRequestPresentation(request, loading, usesMulticardStatus = !returning) }
    AppCard(onClick = open, modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SelectionContainer(Modifier.weight(1f)) { Text(data.primaryNumber, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium) }
            data.secondaryNumber?.let { Text("| $it", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!returning && data.typeTitle.isNotBlank()) Text(data.typeTitle, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            data.status?.let { status -> Text(status, color = coordinationStatusColor(status), style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f)) }
            if (loading) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
        }
        HorizontalDivider()
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) { CoordinationCopyField("ID терминала", data.terminalText, data.terminal, copy) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                CoordinationCopyField("S/N установки", data.installedSerialText, data.installedSerial, copy)
                CoordinationCopyField("S/N демонтажа", data.dismantledSerialText, data.dismantledSerial, copy)
            }
        }
        if (data.registeredAt != null || data.deadline != null) { HorizontalDivider(); Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            data.registeredAt?.let { Text("Регистрация: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            data.deadline?.let { Text("Срок СУТС: $it", style = MaterialTheme.typography.bodySmall, color = if (request.isOverdue()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
        } }
        if (data.address.isNotBlank()) { HorizontalDivider(); Column { Text("Адрес", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(data.address, maxLines = 2, overflow = TextOverflow.Ellipsis) } }
    } }
}

@Composable
private fun CoordinationCopyField(title: String, display: String, value: String, copy: (String) -> Unit) {
    Column { Text(title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (value.isBlank()) Text(display, style = MaterialTheme.typography.bodyMedium)
        else TextButton(onClick = { copy(value) }, contentPadding = PaddingValues(0.dp)) { Text(display) }
    }
}
@Composable
internal fun CoordinationMessage(title: String, message: String, modifier: Modifier = Modifier) {
    AppCard(modifier.fillMaxWidth()) { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium); Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } }
}
private val COORDINATION_UPDATED = DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.forLanguageTag("ru")).withZone(ZoneId.systemDefault())

@Composable
private fun coordinationStatusColor(status: String): androidx.compose.ui.graphics.Color {
    val value = status.lowercase()
    return when {
        listOf("ожидан", "waiting", "pending").any(value::contains) -> androidx.compose.ui.graphics.Color(0xFFB26A00)
        listOf("отказ", "отмен", "cancel").any(value::contains) -> MaterialTheme.colorScheme.error
        listOf("решен", "выполн", "закрыт", "completed").any(value::contains) -> androidx.compose.ui.graphics.Color(0xFF25824F)
        value.contains("недоступ") -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.primary
    }
}
