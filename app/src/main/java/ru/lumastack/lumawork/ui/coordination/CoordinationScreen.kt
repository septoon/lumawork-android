package ru.lumastack.lumawork.ui.coordination

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import ru.lumastack.lumawork.data.coordination.CoordinationRepository
import ru.lumastack.lumawork.data.simpleone.SimpleOneRepository
import ru.lumastack.lumawork.domain.coordination.CoordinationEngineer
import ru.lumastack.lumawork.domain.coordination.CoordinationRegion
import ru.lumastack.lumawork.domain.coordination.CoordinationRequestGroup
import ru.lumastack.lumawork.domain.coordination.CoordinationSection
import ru.lumastack.lumawork.domain.coordination.coordinationEngineerKey
import ru.lumastack.lumawork.domain.coordination.coordinationEngineers
import ru.lumastack.lumawork.domain.simpleone.SimpleOneRequest
import ru.lumastack.lumawork.ui.adaptive.AppWidthClass
import ru.lumastack.lumawork.ui.requests.SimpleOneRequestDetailContent
import ru.lumastack.lumawork.ui.simpleone.SimpleOneUiState
import ru.lumastack.lumawork.ui.theme.LumaBackgroundBottomDark
import ru.lumastack.lumawork.ui.theme.LumaBackgroundBottomLight
import ru.lumastack.lumawork.ui.theme.LumaBackgroundTopDark
import ru.lumastack.lumawork.ui.theme.LumaBackgroundTopLight

@Composable
fun CoordinationRoute(
    ownerUserId: String,
    startOffline: Boolean,
    simpleOneState: SimpleOneUiState,
    coordinationRepository: CoordinationRepository,
    simpleOneRepository: SimpleOneRepository,
    widthClass: AppWidthClass,
    modifier: Modifier = Modifier,
) {
    val coordinationViewModel: CoordinationViewModel = viewModel(
        key = "coordination-$ownerUserId-$startOffline",
        factory = CoordinationViewModel.factory(
            coordinationRepository,
            simpleOneRepository,
            ownerUserId,
            startOffline,
        ),
    )
    val state by coordinationViewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(simpleOneState.snapshot.currentUser?.sysId, simpleOneState.isAuthorized) {
        coordinationViewModel.activate(
            simpleOneUserId = simpleOneState.snapshot.currentUser?.sysId.orEmpty(),
            isAuthorized = simpleOneState.isAuthorized,
        )
    }
    CoordinationScreen(
        state = state,
        widthClass = widthClass,
        onSectionChange = coordinationViewModel::selectSection,
        onRegionChange = coordinationViewModel::selectRegion,
        onRefresh = { coordinationViewModel.refresh() },
        onOpenEngineer = coordinationViewModel::openEngineer,
        onOpenRequest = coordinationViewModel::openRequest,
        onBack = coordinationViewModel::back,
        modifier = modifier,
    )
}

@Composable
private fun CoordinationScreen(
    state: CoordinationUiState,
    widthClass: AppWidthClass,
    onSectionChange: (CoordinationSection) -> Unit,
    onRegionChange: (CoordinationRegion) -> Unit,
    onRefresh: () -> Unit,
    onOpenEngineer: (String) -> Unit,
    onOpenRequest: (SimpleOneRequest) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isDark = isSystemInDarkTheme()
    val background = remember(isDark) {
        Brush.verticalGradient(
            if (isDark) listOf(LumaBackgroundTopDark, LumaBackgroundBottomDark)
            else listOf(LumaBackgroundTopLight, LumaBackgroundBottomLight),
        )
    }
    Box(modifier.fillMaxSize().background(background), contentAlignment = Alignment.TopCenter) {
        when {
            state.selectedRequest != null -> SimpleOneRequestDetailContent(
                request = state.selectedRequest,
                widthClass = widthClass,
                isLoading = state.isDetailLoading,
                errorMessage = state.detailErrorMessage,
                onBack = onBack,
            )
            state.selectedEngineerId != null -> EngineerRequestsContent(
                state = state,
                widthClass = widthClass,
                onBack = onBack,
                onRefresh = onRefresh,
                onOpenRequest = onOpenRequest,
            )
            else -> CoordinationOverview(
                state = state,
                widthClass = widthClass,
                onSectionChange = onSectionChange,
                onRegionChange = onRegionChange,
                onRefresh = onRefresh,
                onOpenEngineer = onOpenEngineer,
                onOpenRequest = onOpenRequest,
            )
        }
    }
}

@Composable
private fun CoordinationOverview(
    state: CoordinationUiState,
    widthClass: AppWidthClass,
    onSectionChange: (CoordinationSection) -> Unit,
    onRegionChange: (CoordinationRegion) -> Unit,
    onRefresh: () -> Unit,
    onOpenEngineer: (String) -> Unit,
    onOpenRequest: (SimpleOneRequest) -> Unit,
) {
    val columns = if (widthClass == AppWidthClass.Compact) 1 else 2
    val group = state.currentGroup()
    val engineers = remember(group.requests) { coordinationEngineers(group.requests) }
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        contentPadding = PaddingValues(20.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxSize().widthIn(max = 1000.dp),
    ) {
        item(key = "sections", span = { GridItemSpan(maxLineSpan) }) {
            SectionSelector(state.section, onSectionChange)
        }
        item(key = "summary", span = { GridItemSpan(maxLineSpan) }) {
            CoordinationSummary(
                state = state,
                group = group,
                engineers = engineers,
                onRegionChange = onRegionChange,
                onRefresh = onRefresh,
            )
        }
        if (!state.isAuthorized && state.simpleOneUserId.isBlank()) {
            item(key = "not-authorized", span = { GridItemSpan(maxLineSpan) }) {
                MessageCard("SimpleOne не подключён", "Войдите в SimpleOne на экране «Заявки».")
            }
        } else if (group.requests.isEmpty()) {
            item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                MessageCard(
                    if (state.isLoading) "Загружаю активные заявки…" else "Активных заявок нет",
                    state.errorMessage ?: if (state.isAuthorized) {
                        "В текущем разделе ничего не найдено."
                    } else {
                        "Нет локальной копии. Подключите SimpleOne и обновите раздел."
                    },
                )
            }
        } else if (state.section == CoordinationSection.Distribution) {
            items(engineers, key = CoordinationEngineer::id) { engineer ->
                EngineerCard(engineer, onClick = { onOpenEngineer(engineer.id) })
            }
        } else {
            items(group.requests, key = SimpleOneRequest::id) { request ->
                CoordinationRequestCard(request, onClick = { onOpenRequest(request) })
            }
        }
    }
}

@Composable
private fun SectionSelector(
    selected: CoordinationSection,
    onSelect: (CoordinationSection) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        CoordinationSection.entries.forEach { section ->
            if (selected == section) {
                FilledTonalButton(onClick = { onSelect(section) }, modifier = Modifier.weight(1f)) {
                    Text(section.title)
                }
            } else {
                OutlinedButton(onClick = { onSelect(section) }, modifier = Modifier.weight(1f)) {
                    Text(section.title)
                }
            }
        }
    }
}

@Composable
private fun CoordinationSummary(
    state: CoordinationUiState,
    group: CoordinationRequestGroup,
    engineers: List<CoordinationEngineer>,
    onRegionChange: (CoordinationRegion) -> Unit,
    onRefresh: () -> Unit,
) {
    var regionMenuExpanded by remember { mutableStateOf(false) }
    Card(
        colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)),
        shape = RoundedCornerShape(22.dp),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        if (state.section == CoordinationSection.Distribution) "Регион / город" else "Возврат оборудования",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelMedium,
                    )
                    if (state.section == CoordinationSection.Distribution) {
                        Box {
                            Button(onClick = { regionMenuExpanded = true }) { Text(state.region.title) }
                            DropdownMenu(
                                expanded = regionMenuExpanded,
                                onDismissRequest = { regionMenuExpanded = false },
                            ) {
                                CoordinationRegion.entries.forEach { region ->
                                    DropdownMenuItem(
                                        text = { Text(region.title) },
                                        onClick = {
                                            regionMenuExpanded = false
                                            onRegionChange(region)
                                        },
                                    )
                                }
                            }
                        }
                    } else {
                        Text("Текущий пользователь SimpleOne", style = MaterialTheme.typography.titleMedium)
                    }
                }
                if (state.isLoading) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                } else {
                    OutlinedButton(onClick = onRefresh, enabled = state.isAuthorized) { Text("Обновить") }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricCard(
                    value = if (state.section == CoordinationSection.Distribution) {
                        engineers.count { !it.isUnassigned }.toString()
                    } else {
                        group.requests.size.toString()
                    },
                    title = if (state.section == CoordinationSection.Distribution) "инженеров" else "возвратов",
                    modifier = Modifier.weight(1f),
                )
                MetricCard(
                    value = group.requests.size.toString(),
                    title = "активных заявок",
                    modifier = Modifier.weight(1f),
                )
            }
            group.updatedAtEpochMillis?.let {
                Text(
                    "Обновлено ${UPDATED_FORMATTER.format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()))}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            state.errorMessage?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun MetricCard(value: String, title: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.background(
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
            RoundedCornerShape(14.dp),
        ).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(value, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
        Text(title, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun EngineerCard(engineer: CoordinationEngineer, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)),
        shape = RoundedCornerShape(20.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(40.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (engineer.isUnassigned) "?" else engineer.name.initials(),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontWeight = FontWeight.Bold,
                )
            }
            Text(
                engineer.name,
                modifier = Modifier.weight(1f),
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(engineer.requestCount.toString(), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun EngineerRequestsContent(
    state: CoordinationUiState,
    widthClass: AppWidthClass,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onOpenRequest: (SimpleOneRequest) -> Unit,
) {
    val columns = if (widthClass == AppWidthClass.Compact) 1 else 2
    val allRequests = state.snapshot.regions[state.region.name]?.requests.orEmpty()
    val requests = remember(allRequests, state.selectedEngineerId) {
        allRequests.filter { it.coordinationEngineerKey() == state.selectedEngineerId }
            .sortedByDescending { it.registeredDateTime() }
    }
    val engineer = coordinationEngineers(allRequests).firstOrNull { it.id == state.selectedEngineerId }
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        contentPadding = PaddingValues(20.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxSize().widthIn(max = 1000.dp),
    ) {
        item(key = "engineer-header", span = { GridItemSpan(maxLineSpan) }) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedButton(onClick = onBack) { Text("Назад") }
                    Text(engineer?.name ?: "Заявки", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "${state.region.title} · ${requests.size} заявок",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (state.isLoading) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                else OutlinedButton(onClick = onRefresh, enabled = state.isAuthorized) { Text("Обновить") }
            }
        }
        items(requests, key = SimpleOneRequest::id) { request ->
            CoordinationRequestCard(request, onClick = { onOpenRequest(request) })
        }
    }
}

@Composable
private fun CoordinationRequestCard(request: SimpleOneRequest, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(
            if (request.isOverdue()) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.72f)
            else MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        ),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(request.displayNumber, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            request.state.takeIf(String::isNotBlank)?.let {
                Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
            }
            request.shortDescription.takeIf(String::isNotBlank)?.let {
                Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            request.address.takeIf(String::isNotBlank)?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun MessageCard(title: String, message: String) {
    Card(
        colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
            Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun CoordinationUiState.currentGroup(): CoordinationRequestGroup = when (section) {
    CoordinationSection.Distribution -> snapshot.regions[region.name] ?: CoordinationRequestGroup()
    CoordinationSection.ReturnEquipment -> snapshot.returnEquipment ?: CoordinationRequestGroup()
}

private fun String.initials(): String = split(Regex("\\s+"))
    .filter(String::isNotBlank)
    .take(2)
    .mapNotNull(String::firstOrNull)
    .joinToString("")
    .uppercase(Locale.forLanguageTag("ru"))
    .ifBlank { "?" }

private val UPDATED_FORMATTER = DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.forLanguageTag("ru"))
