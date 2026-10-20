package ru.lumastack.lumawork.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import ru.lumastack.lumawork.ui.theme.AppButton as Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import ru.lumastack.lumawork.domain.simpleone.SimpleOneRequest
import ru.lumastack.lumawork.domain.simpleone.updatedAtDateTime
import ru.lumastack.lumawork.ui.simpleone.SimpleOneUiState

@Composable
fun ActiveRequestsCard(
    state: SimpleOneUiState,
    onOpenRequests: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenRequest: (SimpleOneRequest) -> Unit = { onOpenRequests() },
) {
    val requests = state.activeRequests
    val now = remember(requests) { LocalDateTime.now() }
    val today = remember(requests) { LocalDate.now() }
    val overdue = remember(requests, now) { requests.count { it.isOverdue(now) } }
    val dueToday = remember(requests, today) { requests.count { it.isDueToday(today) } }
    val nearest = remember(requests, now) {
        requests.mapNotNull { request -> request.deadlineDateTime()?.let { request to it } }
            .minByOrNull { (_, deadline) -> deadline }
            ?.first
    }
    val updateCaption = state.snapshot.updatedAtDateTime()?.format(TIME_FORMATTER)?.let {
        "Обновлено $it"
    }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        ),
        shape = RoundedCornerShape(24.dp),
        modifier = modifier.fillMaxWidth(),
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
                    Text("Активные заявки", style = MaterialTheme.typography.titleLarge)
                    Text(
                        text = updateCaption ?: when {
                            state.isInitializing -> "Загружаем сохранённые данные…"
                            state.isAuthorized -> "Данные ещё не обновлялись"
                            else -> "Требуется вход в SimpleOne"
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (state.isInitializing || state.isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            }

            if (requests.isNotEmpty() || state.isAuthorized) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    RequestMetric("Всего", requests.size, Modifier.weight(1f).clickable(onClick = onOpenRequests))
                    RequestMetric("Просрочено", overdue, Modifier.weight(1f), isAlert = overdue > 0)
                    RequestMetric("Сегодня", dueToday, Modifier.weight(1f))
                }

                if (nearest != null) NearestRequestSummary(nearest, onClick = { onOpenRequest(nearest) })
                else Text(
                    text = "Заявок со сроком SLA нет",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            if (!state.isInitializing && !state.isAuthorized && requests.isNotEmpty()) {
                Text(
                    text = "Показаны сохранённые данные. Войдите, чтобы обновить список.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            if (!state.isInitializing && !state.isAuthorized && requests.isEmpty()) {
                Text(
                    text = "Подключите рабочую учётную запись SimpleOne в разделе заявок.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            if (!state.isInitializing) {
                Button(onClick = onOpenRequests, modifier = Modifier.fillMaxWidth()) {
                    Text(if (state.isAuthorized) "Открыть заявки" else "Подключить SimpleOne")
                }
            }
        }
    }
}

@Composable
private fun RequestMetric(
    title: String,
    value: Int,
    modifier: Modifier,
    isAlert: Boolean = false,
) {
    val containerColor = if (isAlert) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
    }
    val contentColor = if (isAlert) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    Surface(color = containerColor, shape = RoundedCornerShape(16.dp), modifier = modifier) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = title,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelSmall,
            )
            Text(
                text = value.toString(),
                color = contentColor,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleLarge,
            )
        }
    }
}

@Composable
private fun NearestRequestSummary(request: SimpleOneRequest, onClick: () -> Unit) {
    val overdue = request.isOverdue()
    Surface(
        color = if (overdue) {
            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.72f)
        } else {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f)
        },
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = request.displayNumber,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = if (overdue) "Просрочено" else "В срок",
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            if (request.shortDescription.isNotBlank()) {
                Text(
                    request.shortDescription,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            request.deadlineDateTime()?.let {
                Text(
                    text = "Срок: ${it.format(DEADLINE_FORMATTER)}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (request.assignedUser.isNotBlank()) Text(request.assignedUser, style = MaterialTheme.typography.bodySmall)
            request.slaStatusText()?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (request.address.isNotBlank()) {
                Text(
                    request.address,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

private val RUSSIAN_LOCALE = Locale.forLanguageTag("ru")
private val TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm", RUSSIAN_LOCALE)
private val DEADLINE_FORMATTER = DateTimeFormatter.ofPattern("d MMM, HH:mm", RUSSIAN_LOCALE)
