package ru.lumastack.lumawork.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.delay
import ru.lumastack.lumawork.data.fuel.FuelRepository
import ru.lumastack.lumawork.data.route.RouteRepository
import ru.lumastack.lumawork.data.simpleone.SimpleOneRepository
import ru.lumastack.lumawork.domain.model.AppSession
import ru.lumastack.lumawork.domain.route.RouteWorkType
import ru.lumastack.lumawork.domain.route.makeYandexRouteUrl
import ru.lumastack.lumawork.domain.simpleone.RequestsMode
import ru.lumastack.lumawork.domain.simpleone.SimpleOneRequest
import ru.lumastack.lumawork.ui.adaptive.AppWidthClass
import ru.lumastack.lumawork.ui.requests.RequestsActions
import ru.lumastack.lumawork.ui.requests.SimpleOneRequestDetailContent
import ru.lumastack.lumawork.ui.shell.AppDestination
import ru.lumastack.lumawork.ui.simpleone.SimpleOneUiState
import ru.lumastack.lumawork.ui.theme.AppButton
import ru.lumastack.lumawork.ui.theme.AppRefreshContainer
import ru.lumastack.lumawork.ui.theme.AppTextField
import ru.lumastack.lumawork.ui.theme.LumaBackgroundBottomDark
import ru.lumastack.lumawork.ui.theme.LumaBackgroundBottomLight
import ru.lumastack.lumawork.ui.theme.LumaBackgroundTopDark
import ru.lumastack.lumawork.ui.theme.LumaBackgroundTopLight

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    session: AppSession,
    isOffline: Boolean,
    routeRepository: RouteRepository,
    simpleOneState: SimpleOneUiState,
    widthClass: AppWidthClass,
    onOpenDestination: (AppDestination) -> Unit,
    onRefreshRequests: () -> Unit,
    fuelRepository: FuelRepository,
    simpleOneRepository: SimpleOneRepository,
    requestsActions: RequestsActions,
    modifier: Modifier = Modifier,
) {
    var selectedDate by rememberSaveable(session.user.id) { mutableStateOf(LocalDate.now().toString()) }
    val model: RouteViewModel = viewModel(
        key = "route-${session.user.id}-${session.token.hashCode()}-$isOffline",
        factory = RouteViewModel.factory(routeRepository, session, isOffline, LocalDate.parse(selectedDate)),
    )
    val state by model.state.collectAsStateWithLifecycle()
    LaunchedEffect(model) { model.selectDate(LocalDate.parse(selectedDate)) }
    LaunchedEffect(model, session.user.profile) { model.updateSettings(ru.lumastack.lumawork.domain.route.RouteSettings.from(session.user.profile)) }
    val isDark = MaterialTheme.colorScheme.background == LumaBackgroundTopDark
    val background = remember(isDark) {
        Brush.verticalGradient(if (isDark) listOf(LumaBackgroundTopDark, LumaBackgroundBottomDark)
            else listOf(LumaBackgroundTopLight, LumaBackgroundBottomLight))
    }
    val maxContentWidth = when (widthClass) {
        AppWidthClass.Compact -> 560.dp
        AppWidthClass.Medium -> 920.dp
        AppWidthClass.Expanded -> 1120.dp
    }
    val keyboard = LocalSoftwareKeyboardController.current
    var archiveOpen by rememberSaveable { mutableStateOf(false) }
    var fuelOpen by rememberSaveable { mutableStateOf(false) }
    var detailOpen by rememberSaveable { mutableStateOf(false) }
    var routeUrl by rememberSaveable { mutableStateOf<String?>(null) }
    var mapError by remember { mutableStateOf<String?>(null) }
    var cachedClosed by remember(session.user.id) { mutableStateOf(emptyList<SimpleOneRequest>()) }
    var savedAddresses by remember(session.user.id) { mutableStateOf(emptyList<String>()) }
    LaunchedEffect(session.user.id) {
        cachedClosed = simpleOneRepository.readClosedArchive(session.user.id)?.requests.orEmpty()
    }
    LaunchedEffect(state.record.date.take(7)) {
        savedAddresses = routeRepository.readMonth(session, state.record.date.take(7)).flatMap { it.stops }.map { it.address }.filter(String::isNotBlank).distinct()
    }
    LaunchedEffect(state.record.date, state.record.workType, state.isRefreshing, cachedClosed, simpleOneState.closedArchive) {
        model.autoImportClosedRequests(simpleOneState.closedArchive.requests.ifEmpty { cachedClosed })
    }
    LaunchedEffect(state.notice, state.errorMessage) {
        if (state.notice != null || state.errorMessage != null) { delay(5000); model.dismissMessage() }
    }
    fun openMap() {
        val url = makeYandexRouteUrl(state.record.stops.map { it.address })
        if (url == null) { mapError = "Добавьте минимум два адреса в маршрут."; return }
        routeUrl = url
    }
    var calendarOpen by rememberSaveable { mutableStateOf(false) }
    var editorId by rememberSaveable(state.record.date, state.record.workType) { mutableStateOf<String?>(null) }
    var sendConfirmation by rememberSaveable(state.record.date, state.record.workType) { mutableStateOf(false) }

    Column(modifier.fillMaxSize().background(background), horizontalAlignment = Alignment.CenterHorizontally) {
        AppRefreshContainer(Modifier.weight(1f).widthIn(max = maxContentWidth).fillMaxWidth(),
            isRefreshing = state.isRefreshing || simpleOneState.isLoading,
            enabled = !state.isSending,
            onRefresh = { model.refresh(); onRefreshRequests() }) {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                state.notice?.let { item { Snackbar { Text(it) } } }
                state.errorMessage?.let { item { Snackbar { Text(it) } } }
                if (state.record.workType == RouteWorkType.Pos) item {
                    ActiveRequestsCard(simpleOneState, onOpenRequests = {
                        requestsActions.onCloseRequest(); requestsActions.onModeChange(RequestsMode.Active)
                        onOpenDestination(AppDestination.Requests)
                    }, onOpenRequest = { requestsActions.onOpenRequest(it); detailOpen = true })
                }
                item {
                    WorkDayCard(state, onOpenCalendar = { calendarOpen = true },
                        onDistanceChange = model::updateDistance, onAddFuel = { fuelOpen = true },
                        onOpenArchive = { archiveOpen = true }, onOpenMap = ::openMap)
                }
                item {
                    RouteTimelineCard(state, onEditStop = { editorId = it }, onAddStop = model::addStop,
                        onRemoveStop = model::removeStop, onReorderStop = model::reorderStop, onEndpointSelect = model::updateEndpoint)
                }
            }
        }
        Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = .96f)) {
            Box(Modifier.widthIn(max = maxContentWidth).fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
                AppButton(onClick = { keyboard?.hide(); if (state.canSend) sendConfirmation = true else model.send() },
                    enabled = !state.isSending, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(50)) {
                    if (state.isSending) CircularProgressIndicator(Modifier.size(18.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                    else Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(if (state.isSending) "Отправляю…" else "Отправить", Modifier.padding(start = 10.dp))
                }
            }
        }
    }
    if (calendarOpen) RouteCalendar(state.record.date, onDismiss = { calendarOpen = false }) {
        selectedDate = it.toString()
        model.selectDate(it)
        calendarOpen = false
    }
    state.record.stops.firstOrNull { it.id == editorId }?.let { stop ->
        val index = state.record.stops.indexOf(stop)
        RouteStopEditor(stop, index, state.record.date, onDismiss = { editorId = null },
            onSave = { address, number -> model.updateStop(stop.id, address, number); editorId = null },
            workType = state.record.workType,
            suggestions = (savedAddresses + state.record.stops.map { it.address } + listOf(state.settings.startAddress, state.settings.homeAddress)).filter(String::isNotBlank).distinct(),
            offices = state.officeAddresses, isLoadingOffices = state.isLoadingOffices, onRefreshOffices = model::refreshOfficeAddresses)
    }
    if (sendConfirmation) AlertDialog(onDismissRequest = { sendConfirmation = false },
        title = { Text("Отправить отчёт?") }, text = { Text("Будет отправлен отчёт ${state.record.workType.title} за ${state.dateTitle.lowercase()}.") },
        confirmButton = { TextButton(onClick = { sendConfirmation = false; model.send() }) { Text("Отправить") } },
        dismissButton = { TextButton(onClick = { sendConfirmation = false }) { Text("Отмена") } })
    routeUrl?.let { url -> HomeRouteBrowser(url, onDismiss = { routeUrl = null }) }
    state.sentReport?.let { sent ->
        LaunchedEffect(sent) { delay(2400); model.dismissSentReport() }
        androidx.compose.ui.window.Dialog(onDismissRequest = model::dismissSentReport) {
            Card(shape = RoundedCornerShape(28.dp)) {
                Column(Modifier.padding(28.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("✓", style = MaterialTheme.typography.displayMedium, color = MaterialTheme.colorScheme.primary)
                    Text("Отчёт ${sent.workType.title} отправлен", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                    Text(LocalDate.parse(sent.date).format(java.time.format.DateTimeFormatter.ofPattern("d MMMM", java.util.Locale.forLanguageTag("ru"))),
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    if (archiveOpen) RouteArchiveSheet(state, session, isOffline, routeRepository,
        onDismiss = { archiveOpen = false }, onOdometerChange = model::updateOdometer)
    if (fuelOpen) HomeFuelEntry(session, isOffline, fuelRepository, onDismiss = { fuelOpen = false })
    if (detailOpen) ModalBottomSheet(onDismissRequest = { detailOpen = false; requestsActions.onCloseRequest() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), sheetMaxWidth = 980.dp) {
        simpleOneState.selectedRequest?.let { request ->
            SimpleOneRequestDetailContent(request, widthClass, simpleOneState.isDetailLoading, simpleOneState.detailErrorMessage,
                onBack = { detailOpen = false; requestsActions.onCloseRequest() })
        }
    }
    mapError?.let { message -> AlertDialog(onDismissRequest = { mapError = null }, title = { Text("Маршрут") },
        text = { Text(message) }, confirmButton = { TextButton(onClick = { mapError = null }) { Text("Готово") } }) }

}

@Composable
private fun WorkDayCard(state: RouteUiState, onOpenCalendar: () -> Unit, onDistanceChange: (String) -> Unit, onAddFuel: () -> Unit, onOpenArchive: () -> Unit, onOpenMap: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = .96f)),
        shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).clickable(onClick = onOpenCalendar).semantics { contentDescription = "Дата маршрута" }) {
                    Text("Рабочий день", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(state.dateTitle, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    }
                }
                TextButton(onClick = onAddFuel) { Text("Добавить заправку") }
            }
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { RouteNumberField(state.distanceText, "Пробег дня", onDistanceChange) }
                IconButton(onClick = onOpenMap) { androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(ru.lumastack.lumawork.R.drawable.ic_yandex_maps), "Составить маршрут в Яндекс.Картах", Modifier.size(40.dp).clip(RoundedCornerShape(10.dp))) }
            }
            HorizontalDivider()
            TextButton(onClick = onOpenArchive, modifier = Modifier.fillMaxWidth()) {
                Text("Архив маршрутов", modifier = Modifier.weight(1f), textAlign = TextAlign.Start)
                Text("›", style = MaterialTheme.typography.titleLarge)
            }
        }
    }
}

@Composable
private fun RouteNumberField(value: String, label: String, onValueChange: (String) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        AppTextField(value, onValueChange, label, showLabel = false, placeholder = "0", singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            textStyle = MaterialTheme.typography.bodyLarge.copy(textAlign = TextAlign.End, fontWeight = FontWeight.SemiBold),
            modifier = Modifier.width(82.dp))
        Text("км", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RouteCalendar(date: String, onDismiss: () -> Unit, onSelect: (LocalDate) -> Unit) {
    val picker = rememberDatePickerState(initialSelectedDateMillis = LocalDate.parse(date).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
    DatePickerDialog(onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { picker.selectedDateMillis?.let { onSelect(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) } }) { Text("Готово") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }) {
        DatePicker(picker, title = { Text("Календарь", Modifier.padding(start = 24.dp, top = 16.dp)) })
    }
}
