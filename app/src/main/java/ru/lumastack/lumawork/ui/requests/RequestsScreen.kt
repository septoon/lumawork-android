package ru.lumastack.lumawork.ui.requests

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items as lazyRowItems
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.format.DateTimeFormatter
import java.util.Locale
import ru.lumastack.lumawork.domain.simpleone.ActiveRequestsSortOrder
import ru.lumastack.lumawork.domain.simpleone.ActiveRequestsStatusFilter
import ru.lumastack.lumawork.domain.simpleone.ClosedArchiveSource
import ru.lumastack.lumawork.domain.simpleone.RequestsMode
import ru.lumastack.lumawork.domain.simpleone.SimpleOneRequest
import ru.lumastack.lumawork.domain.simpleone.SimpleOneRequestSource
import ru.lumastack.lumawork.domain.simpleone.filterActiveRequests
import ru.lumastack.lumawork.domain.simpleone.filterClosedRequests
import ru.lumastack.lumawork.domain.simpleone.updatedAtDateTime
import ru.lumastack.lumawork.ui.adaptive.AppWidthClass
import ru.lumastack.lumawork.ui.simpleone.SimpleOneUiState
import ru.lumastack.lumawork.ui.theme.LumaBackgroundBottomDark
import ru.lumastack.lumawork.ui.theme.LumaBackgroundBottomLight
import ru.lumastack.lumawork.ui.theme.LumaBackgroundTopDark
import ru.lumastack.lumawork.ui.theme.LumaBackgroundTopLight

data class RequestsActions(
    val onUsernameChange: (String) -> Unit,
    val onPasswordChange: (String) -> Unit,
    val onSignIn: () -> Unit,
    val onRefresh: () -> Unit,
    val onSignOut: () -> Unit,
    val onSearchQueryChange: (String) -> Unit,
    val onStatusFilterChange: (ActiveRequestsStatusFilter) -> Unit,
    val onSortOrderChange: (ActiveRequestsSortOrder) -> Unit,
    val onModeChange: (RequestsMode) -> Unit,
    val onOpenRequest: (SimpleOneRequest) -> Unit,
    val onCloseRequest: () -> Unit,
)

@Composable
fun RequestsScreen(
    state: SimpleOneUiState,
    widthClass: AppWidthClass,
    actions: RequestsActions,
    modifier: Modifier = Modifier,
) {
    val isDark = isSystemInDarkTheme()
    val background = remember(isDark) {
        Brush.verticalGradient(
            if (isDark) {
                listOf(LumaBackgroundTopDark, LumaBackgroundBottomDark)
            } else {
                listOf(LumaBackgroundTopLight, LumaBackgroundBottomLight)
            },
        )
    }

    Box(
        modifier = modifier.fillMaxSize().background(background),
        contentAlignment = Alignment.TopCenter,
    ) {
        val selected = state.selectedRequest
        if (selected != null) {
            SimpleOneRequestDetailContent(
                request = selected,
                widthClass = widthClass,
                isLoading = state.isDetailLoading,
                errorMessage = state.detailErrorMessage,
                onBack = actions.onCloseRequest,
            )
        } else {
            RequestsListContent(state = state, widthClass = widthClass, actions = actions)
        }
    }
}

@Composable
private fun RequestsListContent(
    state: SimpleOneUiState,
    widthClass: AppWidthClass,
    actions: RequestsActions,
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

    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        contentPadding = PaddingValues(20.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxSize().widthIn(max = 1120.dp),
    ) {
        item(key = "connection", span = { GridItemSpan(maxLineSpan) }) {
            SimpleOneConnectionCard(state = state, actions = actions)
        }

        item(key = "modes", span = { GridItemSpan(maxLineSpan) }) {
            RequestsModeSelector(state.mode, actions.onModeChange)
        }

        item(key = "search-and-filters", span = { GridItemSpan(maxLineSpan) }) {
            RequestsSearchAndFilters(state = state, actions = actions)
        }

        item(key = "heading", span = { GridItemSpan(maxLineSpan) }) {
            RequestsHeading(state = state, count = visibleRequests.size)
        }

        if (visibleRequests.isEmpty()) {
            item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                EmptyRequestsCard(state)
            }
        } else {
            items(visibleRequests, key = SimpleOneRequest::id) { request ->
                RequestCard(request = request, onClick = { actions.onOpenRequest(request) })
            }
        }
    }
}

@Composable
private fun RequestsModeSelector(
    selected: RequestsMode,
    onSelect: (RequestsMode) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        RequestsMode.entries.forEach { mode ->
            if (mode == selected) {
                FilledTonalButton(onClick = { onSelect(mode) }, modifier = Modifier.weight(1f)) {
                    Text(mode.title)
                }
            } else {
                OutlinedButton(onClick = { onSelect(mode) }, modifier = Modifier.weight(1f)) {
                    Text(mode.title)
                }
            }
        }
    }
}

@Composable
private fun RequestsSearchAndFilters(
    state: SimpleOneUiState,
    actions: RequestsActions,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            value = state.searchQuery,
            onValueChange = actions.onSearchQueryChange,
            label = { Text("Поиск по заявкам") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            modifier = Modifier.fillMaxWidth(),
        )
        if (state.mode == RequestsMode.Active) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                lazyRowItems(ActiveRequestsStatusFilter.entries, key = { it.name }) { filter ->
                    FilterChip(
                        selected = state.statusFilter == filter,
                        onClick = { actions.onStatusFilterChange(filter) },
                        label = { Text(filter.title) },
                    )
                }
                item(key = "sort") {
                    OutlinedButton(
                        onClick = {
                            actions.onSortOrderChange(
                                if (state.sortOrder == ActiveRequestsSortOrder.RegisteredAt) {
                                    ActiveRequestsSortOrder.Deadline
                                } else {
                                    ActiveRequestsSortOrder.RegisteredAt
                                },
                            )
                        },
                    ) {
                        Text(state.sortOrder.title)
                    }
                }
            }
        }
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
                    state.mode == RequestsMode.Closed && !state.hasLoadedClosedArchive -> "Архив загружается только при открытии"
                    state.mode == RequestsMode.Closed && state.closedArchive.source == ClosedArchiveSource.Xlsx ->
                        "Полная XLSX-копия · Найдено: $count"
                    state.mode == RequestsMode.Closed && state.closedArchive.isComplete ->
                        "Резервная API-копия · ограниченные поля · Найдено: $count"
                    state.mode == RequestsMode.Closed && !state.isAuthorized -> "Локальная копия доступна без сети"
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
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        ),
        shape = RoundedCornerShape(22.dp),
    ) {
        Text(
            text = when {
                state.mode == RequestsMode.Closed && state.isClosedLoading -> "Синхронизирую закрытый архив…"
                state.mode == RequestsMode.Closed && !state.isAuthorized -> "Локальный архив пуст. Войдите для первой синхронизации."
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
private fun SimpleOneConnectionCard(
    state: SimpleOneUiState,
    actions: RequestsActions,
) {
    val isBusy = state.isLoading || state.isClosedLoading
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        ),
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.isAuthorized) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = state.snapshot.currentUser?.displayName?.ifBlank { "SimpleOne" } ?: "SimpleOne",
                            style = MaterialTheme.typography.titleLarge,
                        )
                        val updatedAt = if (state.mode == RequestsMode.Closed) {
                            state.closedArchive.updatedAtDateTime()
                        } else {
                            state.snapshot.updatedAtDateTime()
                        }
                        Text(
                            text = updatedAt?.format(UPDATED_FORMATTER)?.let { "Обновлено $it" }
                                ?: "Данные ещё не синхронизировались",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    if (isBusy) CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = actions.onRefresh,
                        enabled = !isBusy,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Обновить")
                    }
                    OutlinedButton(
                        onClick = actions.onSignOut,
                        enabled = !isBusy,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Выйти")
                    }
                }
            } else {
                Text("Вход в SimpleOne", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Пароль используется только для входа и не сохраняется.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = state.username,
                    onValueChange = actions.onUsernameChange,
                    label = { Text("Логин") },
                    singleLine = true,
                    enabled = !state.isLoading,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Next,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = state.password,
                    onValueChange = actions.onPasswordChange,
                    label = { Text("Пароль") },
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
                Button(
                    onClick = actions.onSignIn,
                    enabled = !state.isLoading,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (state.isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Text("Войти и загрузить")
                    }
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
private fun RequestCard(request: SimpleOneRequest, onClick: () -> Unit) {
    val overdue = request.isOverdue()
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = if (overdue) {
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.74f)
            } else {
                MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)
            },
        ),
        shape = RoundedCornerShape(22.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    request.displayNumber,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (overdue) {
                    Text(
                        "Просрочено",
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
            request.displayState.takeIf(String::isNotBlank)?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            if (request.shortDescription.isNotBlank()) {
                Text(
                    request.shortDescription,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            val date = if (request.source == SimpleOneRequestSource.Active) {
                request.deadlineDateTime()?.format(DEADLINE_FORMATTER)?.let { "Срок: $it" }
            } else {
                request.resolvedDateTime()?.format(DEADLINE_FORMATTER)?.let { "Закрыта: $it" }
            }
            date?.let {
                Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
            if (request.address.isNotBlank()) {
                Text(
                    request.address,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (request.customer.isNotBlank()) {
                Text(
                    request.customer,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
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
) {
    val columns = if (widthClass == AppWidthClass.Compact) 1 else 2
    val fields = remember(request) { request.detailFields() }
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        contentPadding = PaddingValues(20.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxSize().widthIn(max = 980.dp),
    ) {
        item(key = "detail-header", span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(onClick = onBack) { Text("Назад") }
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
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        ),
        shape = RoundedCornerShape(18.dp),
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
        DetailField("Регистрация", registeredAt),
        DetailField("Предельный срок", deadline),
        DetailField("Выполнена", completedAt.ifBlank { resolvedAt }),
        DetailField("Закрыта", closedAt),
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

private val RUSSIAN_LOCALE = Locale.forLanguageTag("ru")
private val UPDATED_FORMATTER = DateTimeFormatter.ofPattern("d MMM, HH:mm", RUSSIAN_LOCALE)
private val DEADLINE_FORMATTER = DateTimeFormatter.ofPattern("d MMM, HH:mm", RUSSIAN_LOCALE)
