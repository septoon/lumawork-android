package ru.lumastack.lumawork.ui.requests

import ru.lumastack.lumawork.ui.shell.AppHeaderBackHandler as BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Icon
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.MaterialTheme
import ru.lumastack.lumawork.ui.theme.AppTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import java.time.format.DateTimeFormatter
import java.util.Locale
import ru.lumastack.lumawork.domain.simpleone.ActiveRequestsSortOrder
import ru.lumastack.lumawork.domain.simpleone.ActiveRequestsStatusFilter
import ru.lumastack.lumawork.domain.simpleone.ClosedArchiveSource
import ru.lumastack.lumawork.domain.simpleone.RequestsMode
import ru.lumastack.lumawork.domain.simpleone.SimpleOneRequest
import ru.lumastack.lumawork.domain.simpleone.filterActiveRequests
import ru.lumastack.lumawork.domain.simpleone.filterClosedRequests
import ru.lumastack.lumawork.domain.simpleone.parseSimpleOneDate
import ru.lumastack.lumawork.ui.adaptive.AppWidthClass
import ru.lumastack.lumawork.ui.simpleone.SimpleOneUiState
import ru.lumastack.lumawork.ui.theme.AppButton
import ru.lumastack.lumawork.ui.theme.AppCard
import ru.lumastack.lumawork.ui.theme.AppPanel

data class RequestsActions(
    val onUsernameChange: (String) -> Unit,
    val onPasswordChange: (String) -> Unit,
    val onSignIn: () -> Unit,
    val onRefresh: () -> Unit,
    val onRefreshActive: () -> Unit,
    val onRefreshAnalytics: () -> Unit,
    val onSignOut: () -> Unit,
    val onSearchQueryChange: (String) -> Unit,
    val onStatusFilterChange: (ActiveRequestsStatusFilter) -> Unit,
    val onSortOrderChange: (ActiveRequestsSortOrder) -> Unit,
    val onModeChange: (RequestsMode) -> Unit,
    val onOpenRequest: (SimpleOneRequest) -> Unit,
    val onCloseRequest: () -> Unit,
    val onInitialLoad: () -> Unit = {},
    val onDismissConnection: () -> Unit = {},
    val onRefreshClosed: () -> Unit = {},
    val onImportArchive: (ByteArray) -> Unit = {},
    val onExportArchive: suspend () -> ByteArray = { byteArrayOf() },
    val onCleanupArchive: (ru.lumastack.lumawork.domain.requests.ClosedRequestsPeriod?) -> Unit = {},
    val browserAuthKey: suspend () -> String? = { null },
    val onResolveCurrentUser: () -> Unit = {},
    val onSignInIdentityOnly: () -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RequestsScreen(
    state: SimpleOneUiState,
    widthClass: AppWidthClass,
    actions: RequestsActions,
    modifier: Modifier = Modifier,
    toolbar: @Composable () -> Unit = {},
    personalComment: @Composable (SimpleOneRequest) -> Unit = {},
    onOpenTerminal: (String) -> Unit = {},
    detail: @Composable (SimpleOneRequest) -> Unit = { request ->
        SimpleOneRequestDetailContent(request, widthClass, state.isDetailLoading, state.detailErrorMessage, actions.onCloseRequest)
    },
) {
    val selected = state.selectedRequest
    val listState = rememberLazyGridState()
    val savedLists = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    BackHandler(enabled = selected != null, onBack = actions.onCloseRequest)
    val list: @Composable () -> Unit = {
        if (state.mode == RequestsMode.Closed) {
            savedLists.SaveableStateProvider("closed") {
            PullToRefreshBox(isRefreshing = state.isClosedLoading, onRefresh = actions.onRefresh) {
                ClosedRequestsContent(state.closedArchiveItems, state.searchQuery, actions.onSearchQueryChange,
                    state.isClosedLoading, state.hasLoadedClosedArchive, state.isOffline || !state.isAuthorized,
                    actions.onOpenRequest, onOpenTerminal, personalComment = personalComment)
            }
            }
        } else RequestsListContent(state, if (widthClass == AppWidthClass.Expanded) AppWidthClass.Compact else widthClass, actions, listState, personalComment, onOpenTerminal)
    }
    Column(modifier.fillMaxSize()) {
        if (selected == null || widthClass == AppWidthClass.Expanded) toolbar()
        if (widthClass == AppWidthClass.Expanded) {
            Row(Modifier.fillMaxSize().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                AppPanel(Modifier.weight(.43f).fillMaxHeight()) { list() }
                AppPanel(Modifier.weight(.57f).fillMaxHeight()) {
                    if (selected != null) detail(selected)
                    else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Выберите заявку") }
                }
            }
        } else if (selected != null) detail(selected) else list()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RequestsListContent(
    state: SimpleOneUiState,
    widthClass: AppWidthClass,
    actions: RequestsActions,
    gridState: LazyGridState,
    personalComment: @Composable (SimpleOneRequest) -> Unit = {},
    onOpenTerminal: (String) -> Unit = {},
) {
    val columns = when (widthClass) {
        AppWidthClass.Compact -> 1
        AppWidthClass.Medium -> 2
        AppWidthClass.Expanded -> 3
    }
    val activeRequests = remember(
        state.activeRequests,
        state.searchQuery,
        state.statusFilter,
        state.sortOrder,
    ) {
        filterActiveRequests(
            requests = state.activeRequests,
            query = state.searchQuery,
            filter = state.statusFilter,
            sortOrder = state.sortOrder,
        )
    }
    val closedRequests = remember(state.closedArchive.requests, state.searchQuery) {
        filterClosedRequests(state.closedArchive.requests, state.searchQuery)
    }
    val visibleRequests = if (state.mode == RequestsMode.Active) activeRequests else closedRequests

    PullToRefreshBox(
        isRefreshing = state.isAuthorized &&
            (if (state.mode == RequestsMode.Active) state.isLoading else state.isClosedLoading),
        onRefresh = {
            if (state.isAuthorized && !state.isLoading && !state.isClosedLoading) actions.onRefresh()
        },
        modifier = Modifier.widthIn(max = 1120.dp).fillMaxSize(),
    ) {
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Fixed(columns),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (!state.isAuthorized) {
                item(key = "connection", span = { GridItemSpan(maxLineSpan) }) {
                    SimpleOneConnectionCard(state = state, actions = actions)
                }
            }

            item(key = "search-and-filters", span = { GridItemSpan(maxLineSpan) }) {
                RequestsSearchAndFilters(state = state, count = visibleRequests.size, actions = actions)
            }

            if (state.mode == RequestsMode.Closed) {
                item(key = "heading", span = { GridItemSpan(maxLineSpan) }) {
                    RequestsHeading(state = state, count = visibleRequests.size)
                }
            }

            if (visibleRequests.isEmpty()) {
                item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                    EmptyRequestsCard(state)
                }
            } else {
                items(visibleRequests, key = SimpleOneRequest::id) { request ->
                    RequestCard(request, request.id == state.selectedRequest?.id, { actions.onOpenRequest(request) }, onOpenTerminal, personalComment)
                }
            }
        }
    }
}

@Composable
private fun RequestsSearchAndFilters(
    state: SimpleOneUiState,
    count: Int,
    actions: RequestsActions,
) {
    var statusMenuExpanded by remember { mutableStateOf(false) }
    val counts = remember(state.activeRequests, state.searchQuery) {
        ActiveRequestsStatusFilter.entries.associateWith { filter ->
            filterActiveRequests(state.activeRequests, state.searchQuery, filter, state.sortOrder).size
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (state.mode == RequestsMode.Active) {
            Box {
                TextButton(onClick = { statusMenuExpanded = true },
                    contentPadding = PaddingValues(vertical = 4.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(state.statusFilter.title, style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                        Surface(shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.primary.copy(alpha = .12f)) {
                            Text(count.toString(), Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary)
                        }
                        Icon(Icons.Default.ArrowDropDown, contentDescription = "Выбрать статус",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                DropdownMenu(expanded = statusMenuExpanded,
                    onDismissRequest = { statusMenuExpanded = false }) {
                    ActiveRequestsStatusFilter.entries.forEach { filter ->
                        DropdownMenuItem(
                            text = { Text("${filter.title} · ${counts[filter] ?: 0}") },
                            trailingIcon = {
                                if (state.statusFilter == filter) {
                                    Icon(Icons.Default.Check, contentDescription = "Выбрано")
                                }
                            },
                            onClick = {
                                actions.onStatusFilterChange(filter)
                                statusMenuExpanded = false
                            },
                        )
                    }
                }
            }
        }
        AppTextField(
            value = state.searchQuery,
            onValueChange = actions.onSearchQueryChange,
            label = "Поиск по заявкам", search = true,
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun RequestsHeading(state: SimpleOneUiState, count: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.weight(1f)) {
            Text(
                text = if (state.mode == RequestsMode.Active) "Активные заявки" else "Закрытый архив SimpleOne",
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                text = when {
                    state.mode == RequestsMode.Closed && state.isClosedLoading && !state.hasLoadedClosedArchive -> "Загружаем архив…"
                    state.mode == RequestsMode.Closed && state.closedArchive.source != ClosedArchiveSource.Xlsx && state.closedArchive.isComplete ->
                        "Найдено: $count · Некоторые сведения недоступны"
                    state.mode == RequestsMode.Closed && !state.isAuthorized && state.closedArchive.requests.isNotEmpty() -> "Войдите, чтобы обновить архив"
                    !state.isAuthorized && state.activeRequests.isNotEmpty() -> "Сохранённые данные — войдите для обновления"
                    else -> "Найдено: $count"
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (state.isClosedLoading) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
        }
    }
}

@Composable
private fun EmptyRequestsCard(state: SimpleOneUiState) {
    AppCard(
    ) {
        Text(
            text = when {
                state.mode == RequestsMode.Closed && state.isClosedLoading -> "Загружаем архив…"
                state.mode == RequestsMode.Closed && !state.isAuthorized -> "Войдите в SimpleOne, чтобы загрузить архив."
                state.mode == RequestsMode.Closed -> "Закрытых заявок по поиску нет."
                state.isAuthorized && !state.isLoading -> "Активных заявок по текущему фильтру нет."
                !state.isAuthorized -> "После входа здесь появятся активные заявки."
                else -> "Загружаю заявки…"
            },
            modifier = Modifier.padding(20.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun SimpleOneConnectionCard(
    state: SimpleOneUiState,
    actions: RequestsActions,
) {
    AppCard(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Вход в SimpleOne", style = MaterialTheme.typography.titleLarge)
            AppTextField(
                value = state.username,
                onValueChange = actions.onUsernameChange,
                label = "Логин",
                singleLine = true,
                enabled = !state.isLoading,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Next,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            AppTextField(
                value = state.password,
                onValueChange = actions.onPasswordChange,
                label = "Пароль",
                singleLine = true,
                enabled = !state.isLoading,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { actions.onSignIn() }),
                modifier = Modifier.fillMaxWidth(),
            )
            AppButton(
                onClick = actions.onSignIn,
                enabled = !state.isLoading,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = androidx.compose.material3.LocalContentColor.current,
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text("Войти и загрузить")
                }
            }

            state.notice?.let {
                Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
            }
            state.errorMessage?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun RequestCard(request: SimpleOneRequest, selected: Boolean, onClick: () -> Unit,
    onOpenTerminal: (String) -> Unit, personalComment: @Composable (SimpleOneRequest) -> Unit) {
    val data = remember(request) { ru.lumastack.lumawork.domain.requests.RequestDetailsData(request) }
    val type = ru.lumastack.lumawork.domain.requests.closedRequestTypeTitle(data.type)
    AppCard(onClick = onClick, border = if (selected) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(data.number, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    if (data.number != request.number) Text(request.number, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Surface(shape = RoundedCornerShape(12.dp), color = closedTypeColor(type)) {
                    Text(type.ifBlank { "Тип не указан" }, Modifier.widthIn(max = 150.dp).padding(horizontal = 10.dp, vertical = 6.dp),
                        color = androidx.compose.ui.graphics.Color.White, style = MaterialTheme.typography.labelMedium)
                }
            }
            request.registeredDateTime()?.let { RequestCardInfo("Зарегистрирована", it.format(DEADLINE_FORMATTER)) }
            request.deadlineDateTime()?.let { RequestCardInfo("Предельный срок", it.format(DEADLINE_FORMATTER)) }
            request.slaStatusText()?.let { Text(it, color = if (request.isOverdue()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold) }
            if (request.contactPhone.isNotBlank()) RequestCardInfo("Телефон", request.contactPhone)
            RequestCardInfo("Заказчик", data.legalName.ifBlank { "Информация отсутствует" })
            Column {
                Text("Адрес", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(data.address.ifBlank { "Информация отсутствует" }, style = MaterialTheme.typography.bodyMedium)
            }
            if (data.terminal.isNotBlank()) RequestTerminalRow(data.terminal, onOpenTerminal)
            personalComment(request)
        }
        Surface(color = requestStatusColor(request.displayState, closed = false)) {
            Text(request.displayState, Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp),
                color = requestStatusTextColor(request.displayState), style = MaterialTheme.typography.labelLarge)
        }
    }
}
@Composable
private fun RequestCardInfo(title: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, Modifier.weight(1.3f), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

private data class DetailField(val title: String, val value: String, val isLong: Boolean = false)

@Composable
fun SimpleOneRequestDetailContent(
    request: SimpleOneRequest,
    widthClass: AppWidthClass,
    isLoading: Boolean,
    errorMessage: String?,
    onBack: () -> Unit,
    gridState: LazyGridState = rememberLazyGridState(),
    showBackButton: Boolean = false,
) {
    val columns = if (widthClass == AppWidthClass.Compact) 1 else 2
    val fields = remember(request) { request.detailFields() }
    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Fixed(columns),
        contentPadding = PaddingValues(20.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.widthIn(max = 980.dp).fillMaxSize(),
    ) {
        item(key = "detail-header", span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (showBackButton || isLoading) Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (showBackButton) ru.lumastack.lumawork.ui.shell.AppBackButton(onBack)
                    if (isLoading) CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                }
                Text(request.displayNumber, style = MaterialTheme.typography.headlineMedium)
                request.displayState.takeIf(String::isNotBlank)?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                errorMessage?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        items(
            items = fields,
            key = { it.title },
            span = { field ->
                if (field.isLong) GridItemSpan(maxLineSpan) else GridItemSpan(1)
            },
        ) { field ->
            DetailFieldCard(field)
        }
        item(key = "detail-bottom", span = { GridItemSpan(maxLineSpan) }) {
            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
private fun DetailFieldCard(field: DetailField) {
    AppCard(
        modifier = Modifier.fillMaxWidth(),
    ) {
        SelectionContainer {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text(
                    field.title,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                )
                Text(field.value, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

private fun SimpleOneRequest.detailFields(): List<DetailField> {
    val primary = listOf(
        DetailField("Номер SimpleOne", number),
        DetailField("Входящий номер", incomingNumber),
        DetailField("Регистрация", registeredAt.displaySimpleOneDate()),
        DetailField("Предельный срок", deadline.displaySimpleOneDate()),
        DetailField("Выполнена", completedAt.ifBlank { resolvedAt }.displaySimpleOneDate()),
        DetailField("Закрыта", closedAt.displaySimpleOneDate()),
        DetailField("Причина ожидания", waitingReason),
        DetailField("Тип заявки", requestType),
        DetailField("Группа назначения", assignmentGroup),
        DetailField("Инициатор", initiator),
        DetailField("Приоритет", priority),
        DetailField("Клиентский сервис", clientService),
        DetailField("Родительский сервис", clientServiceParent),
        DetailField("Исполнитель", assignedUser),
        DetailField("Заказчик", customer),
        DetailField("Адрес", address),
        DetailField("Модель POS-терминала", terminalModel),
        DetailField("ID терминала", terminalId),
        DetailField("Контактное лицо", contactPerson),
        DetailField("Телефон ТСП", contactPhone),
        DetailField("Код закрытия", closureCode),
        DetailField("Серийный номер установленного ФН", installedFiscalStorageSerialNumber),
        DetailField("Код активации тарифа ОФД", ofdTariffActivationCode),
        DetailField("Использованная SIM-карта", usedSimCard),
        DetailField("Краткое описание", shortDescription, isLong = true),
        DetailField("Описание", description, isLong = true),
        DetailField("Информация", additionalInformation, isLong = true),
        DetailField("Комментарий инженера", engineerComment, isLong = true),
        DetailField("Решение", resolution, isLong = true),
    ).filter { it.value.isNotBlank() }
    val primaryTitles = primary.map { it.title.normalizedDetailTitle() }.toSet()
    val exported = tableFields.entries
        .filter { it.value.isNotBlank() && it.key.normalizedDetailTitle() !in primaryTitles }
        .map { (title, value) -> DetailField(title, value, isLong = value.length > 100 || value.contains('\n')) }
    return primary + exported
}

private fun String.normalizedDetailTitle(): String = trim()
    .replace('ё', 'е')
    .replace('Ё', 'Е')
    .lowercase(Locale.ROOT)
    .replace(Regex("\\s+"), " ")

private fun String.displaySimpleOneDate(): String = parseSimpleOneDate(this)?.format(DEADLINE_FORMATTER)
    ?: if (startsWith("1970-")) "" else this

private val RUSSIAN_LOCALE = Locale.forLanguageTag("ru")
private val DEADLINE_FORMATTER = DateTimeFormatter.ofPattern("d MMM, HH:mm", RUSSIAN_LOCALE)
