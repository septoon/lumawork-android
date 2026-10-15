package ru.lumastack.lumawork.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ru.lumastack.lumawork.data.route.RouteRepository
import ru.lumastack.lumawork.domain.model.AppSession
import ru.lumastack.lumawork.domain.route.RouteStop
import ru.lumastack.lumawork.ui.adaptive.AppWidthClass
import ru.lumastack.lumawork.ui.shell.AppDestination
import ru.lumastack.lumawork.ui.simpleone.SimpleOneUiState
import ru.lumastack.lumawork.ui.theme.LumaBackgroundBottomDark
import ru.lumastack.lumawork.ui.theme.LumaBackgroundBottomLight
import ru.lumastack.lumawork.ui.theme.LumaBackgroundTopDark
import ru.lumastack.lumawork.ui.theme.LumaBackgroundTopLight

@Composable
fun HomeScreen(
    session: AppSession,
    isOffline: Boolean,
    routeRepository: RouteRepository,
    simpleOneState: SimpleOneUiState,
    widthClass: AppWidthClass,
    onOpenDestination: (AppDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    val routeViewModel: RouteViewModel = viewModel(
        key = "route-${session.user.id}-${session.token.hashCode()}-$isOffline",
        factory = RouteViewModel.factory(routeRepository, session, isOffline),
    )
    val routeState by routeViewModel.state.collectAsStateWithLifecycle()
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
    val maxContentWidth = when (widthClass) {
        AppWidthClass.Compact -> 560.dp
        AppWidthClass.Medium -> 920.dp
        AppWidthClass.Expanded -> 1120.dp
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(background)
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = maxContentWidth)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            if (widthClass == AppWidthClass.Compact) {
                ActiveRequestsCard(
                    state = simpleOneState,
                    onOpenRequests = { onOpenDestination(AppDestination.Requests) },
                )
                WorkDayCard(
                    state = routeState,
                    compact = true,
                    onDistanceChange = routeViewModel::updateDistance,
                    onOdometerChange = routeViewModel::updateOdometer,
                    onAddressChange = routeViewModel::updatePrimaryStopAddress,
                    onRequestNumberChange = routeViewModel::updatePrimaryStopRequestNumber,
                    activeRequestsCount = simpleOneState.activeRequests.size,
                    onImportActiveRequests = {
                        routeViewModel.importActiveRequests(simpleOneState.activeRequests)
                    },
                    onSend = routeViewModel::send,
                )
                ConnectionCard(isOffline)
                AccountCard(session)
                QuickSectionsCard(
                    widthClass = widthClass,
                    onOpenDestination = onOpenDestination,
                )
            } else {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Column(
                        modifier = Modifier.weight(0.9f),
                        verticalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        ActiveRequestsCard(
                            state = simpleOneState,
                            onOpenRequests = { onOpenDestination(AppDestination.Requests) },
                        )
                        WorkDayCard(
                            state = routeState,
                            compact = widthClass != AppWidthClass.Expanded,
                            onDistanceChange = routeViewModel::updateDistance,
                            onOdometerChange = routeViewModel::updateOdometer,
                            onAddressChange = routeViewModel::updatePrimaryStopAddress,
                            onRequestNumberChange = routeViewModel::updatePrimaryStopRequestNumber,
                            activeRequestsCount = simpleOneState.activeRequests.size,
                            onImportActiveRequests = {
                                routeViewModel.importActiveRequests(simpleOneState.activeRequests)
                            },
                            onSend = routeViewModel::send,
                        )
                    }
                    Column(
                        modifier = Modifier.weight(0.9f),
                        verticalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        AccountCard(session)
                        ConnectionCard(isOffline)
                        QuickSectionsCard(
                            widthClass = if (widthClass == AppWidthClass.Expanded) {
                                AppWidthClass.Expanded
                            } else {
                                AppWidthClass.Compact
                            },
                            onOpenDestination = onOpenDestination,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkDayCard(
    state: RouteUiState,
    compact: Boolean,
    onDistanceChange: (String) -> Unit,
    onOdometerChange: (String) -> Unit,
    onAddressChange: (String) -> Unit,
    onRequestNumberChange: (String) -> Unit,
    activeRequestsCount: Int,
    onImportActiveRequests: () -> Unit,
    onSend: () -> Unit,
) {
    val middleStops = state.record.stops.drop(1).dropLast(1)
    val primaryStop = middleStops.firstOrNull()
    val visiblePoints = state.record.stops.filter { it.address.isNotBlank() }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        ),
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("Рабочий день", style = MaterialTheme.typography.titleLarge)
                    Text(
                        state.dateTitle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (state.isRefreshing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text(
                        text = if (state.record.sent) "Отправлен" else "Черновик",
                        color = if (state.record.sent) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }

            if (compact) {
                RouteNumberField(
                    value = state.distanceText,
                    label = "Пробег за день, км",
                    onValueChange = onDistanceChange,
                )
                RouteNumberField(
                    value = state.odometerText,
                    label = "Одометр на начало месяца",
                    onValueChange = onOdometerChange,
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(Modifier.weight(1f)) {
                        RouteNumberField(
                            value = state.distanceText,
                            label = "Пробег за день, км",
                            onValueChange = onDistanceChange,
                        )
                    }
                    Box(Modifier.weight(1f)) {
                        RouteNumberField(
                            value = state.odometerText,
                            label = "Одометр на начало месяца",
                            onValueChange = onOdometerChange,
                        )
                    }
                }
            }

            HorizontalDivider()
            if (activeRequestsCount > 0) {
                OutlinedButton(
                    onClick = onImportActiveRequests,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Заполнить из заявок ($activeRequestsCount)")
                }
            }
            Text("Основная точка", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                value = primaryStop?.address.orEmpty(),
                onValueChange = onAddressChange,
                label = { Text("Адрес") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = primaryStop?.requestNumber.orEmpty(),
                onValueChange = onRequestNumberChange,
                label = { Text("Номер заявки") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            if (visiblePoints.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    visiblePoints.take(4).forEachIndexed { index, stop ->
                        RoutePointRow(index = index + 1, stop = stop)
                    }
                    if (visiblePoints.size > 4) {
                        Text(
                            text = "Ещё ${visiblePoints.size - 4} точек",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            state.notice?.let {
                Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
            }
            state.errorMessage?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }

            Button(
                onClick = onSend,
                enabled = state.canSend,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.isSending) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text("Отправить рабочий день")
                }
            }
        }
    }
}

@Composable
private fun RouteNumberField(
    value: String,
    label: String,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun RoutePointRow(index: Int, stop: RouteStop) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = RoundedCornerShape(10.dp),
        ) {
            Text(
                text = index.toString(),
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelMedium,
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = stop.address,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
            )
            val detail = stop.requestNumber.ifBlank { stop.reason }
            if (detail.isNotBlank()) {
                Text(
                    text = detail,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun AccountCard(session: AppSession) {
    val profile = session.user.profile
    val displayName = profile?.shortDisplayName ?: "Профиль сотрудника"
    val details = listOfNotNull(
        profile?.jobTitle.clean(),
        profile?.departmentTitle.clean(),
    )

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        ),
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Профиль", style = MaterialTheme.typography.labelLarge)
            Text(displayName, style = MaterialTheme.typography.headlineSmall)
            details.forEach { detail ->
                Text(
                    text = detail,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Text(
                text = session.user.email,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun ConnectionCard(isOffline: Boolean) {
    val containerColor = if (isOffline) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.primaryContainer
    }
    val contentColor = if (isOffline) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onPrimaryContainer
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = containerColor),
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = if (isOffline) "Локальный режим" else "Данные синхронизированы",
                color = contentColor,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = if (isOffline) {
                    "Сохранённая сессия доступна. Обновление продолжится после восстановления связи."
                } else {
                    "Учётная запись подтверждена общим сервером LumaWork."
                },
                color = contentColor,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun QuickSectionsCard(
    widthClass: AppWidthClass,
    onOpenDestination: (AppDestination) -> Unit,
) {
    val destinations = remember {
        listOf(
            AppDestination.Requests,
            AppDestination.Coordination,
            AppDestination.TimeReport,
            AppDestination.Fuel,
        )
    }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        ),
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Рабочие разделы", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Быстрый переход к основным сценариям",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            if (widthClass == AppWidthClass.Compact) {
                destinations.forEach { destination ->
                    QuickDestination(destination) { onOpenDestination(destination) }
                }
            } else {
                destinations.chunked(2).forEach { rowDestinations ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        rowDestinations.forEach { destination ->
                            Box(Modifier.weight(1f)) {
                                QuickDestination(destination) { onOpenDestination(destination) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickDestination(
    destination: AppDestination,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.62f),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                destination.title,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                destination.subtitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private fun String?.clean(): String? = this?.trim()?.ifBlank { null }
