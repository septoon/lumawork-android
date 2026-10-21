package ru.lumastack.lumawork.ui.analytics

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import ru.lumastack.lumawork.domain.requests.*
import ru.lumastack.lumawork.ui.adaptive.AppWidthClass
import ru.lumastack.lumawork.ui.simpleone.SimpleOneUiState
import ru.lumastack.lumawork.ui.theme.*
import ru.lumastack.lumawork.ui.theme.AppSurfaces
import ru.lumastack.lumawork.ui.theme.appScreenBackground
import ru.lumastack.lumawork.ui.theme.AppPanel

@Composable
fun AnalyticsScreen(state: SimpleOneUiState, widthClass: AppWidthClass, onRefresh: () -> Unit, modifier: Modifier = Modifier) {
    val analytics = state.closedAnalytics
    var selectedMonth by rememberSaveable { mutableStateOf<String?>(null) }
    var requestedRefresh by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(analytics) {
        if (analytics.months.none { it.id == selectedMonth }) selectedMonth = null
    }
    LaunchedEffect(state.isClosedLoading, state.errorMessage) {
        if (requestedRefresh && !state.isClosedLoading) {
            requestedRefresh = false
            state.errorMessage?.let { snackbar.showSnackbar(it) }
        }
    }
    val background = appScreenBackground()
    AppRefreshContainer(modifier.fillMaxSize().background(background), isRefreshing = state.isClosedLoading,
        enabled = state.isAuthorized && !state.isOffline && state.initialArchiveReady && !state.isInitializing,
        onRefresh = { requestedRefresh = true; onRefresh() }) {
        if (state.isInitializing && !state.hasLoadedClosedArchive) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator()
                Text("Читаем сохранённый архив…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(Modifier.widthIn(max = 1120.dp).fillMaxSize(),
                contentPadding = PaddingValues(if (widthClass == AppWidthClass.Compact) 16.dp else 20.dp)) {
                item(key = "summary") { AnalyticsSummary(analytics, Modifier.padding(bottom = 14.dp)) }
                if (state.hasLoadedClosedArchive || state.closedArchive.requests.isNotEmpty()) {
                    item(key = "source") {
                        AnalyticsCard(Modifier.padding(bottom = 14.dp)) {
                            SectionTitle("Сводка")
                            StatRow("Последнее обновление", archiveUpdatedAt(state.closedArchive.updatedAtEpochMillis))
                            StatRow("Возврат ТО", analytics.returnEquipCount.toString(), MaterialTheme.colorScheme.secondary)
                            StatRow("Топ месяц", analytics.topMonth?.let { "${it.title} • ${it.count}" } ?: "Нет данных")
                            StatRow("Топ день", analytics.topDay?.let { "${it.title} • ${it.count}" } ?: "Нет данных")
                        }
                    }
                }
                item(key = "chart") {
                    AnalyticsCard(Modifier.padding(bottom = 14.dp)) {
                        SectionTitle("График по месяцам", "Динамика выполненных заявок по месяцам")
                        if (analytics.months.isEmpty()) EmptyText("Нет данных для построения графика по месяцам.")
                        else MonthlyChart(analytics)
                    }
                }
                item(key = "months-header") {
                    Surface(shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp), color = AppSurfaces.card) {
                        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            SectionTitle("Распределение по месяцам", "Сколько выполненных заявок закрыто в каждом месяце")
                            if (analytics.months.isNotEmpty()) Text("Нажмите на месяц, чтобы открыть календарь дней.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (analytics.months.isEmpty()) item(key = "months-empty") {
                    Surface(Modifier.padding(bottom = 14.dp), shape = RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp),
                        color = AppSurfaces.card) {
                        Box(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, bottom = 18.dp)) {
                            EmptyText("Нет данных для построения распределения по месяцам.")
                        }
                    }
                }
                items(analytics.months, key = { "month:${it.id}" }) { month ->
                    val last = month.id == analytics.months.last().id
                    Surface(Modifier.padding(bottom = if (last) 14.dp else 0.dp), color = AppSurfaces.card,
                        shape = RoundedCornerShape(bottomStart = if (last) 28.dp else 0.dp, bottomEnd = if (last) 28.dp else 0.dp)) {
                        MonthAccordion(month, analytics.maxMonthCount, month.id == analytics.latestMonth?.id, month.id == selectedMonth,
                            onToggle = { selectedMonth = if (selectedMonth == month.id) null else month.id })
                    }
                }
                item(key = "types") {
                    AnalyticsCard {
                        SectionTitle("Распределение по типам заявок", "Только выполненные заявки без возврата ТО")
                        if (analytics.requestTypes.isEmpty()) EmptyText("Нет данных для распределения по типам заявок.")
                        analytics.requestTypes.forEach { type ->
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                StatRow(type.title, type.count.toString())
                                DistributionBar(type.count, analytics.topRequestType?.count ?: 0, MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun AnalyticsSummary(analytics: ClosedRequestsAnalytics, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SummaryCard("Выполнено", analytics.completedCount.toString(), "без возврата ТО", Modifier.weight(1f))
            SummaryCard("Месяцев", analytics.months.size.toString(), "в истории", Modifier.weight(1f), MaterialTheme.colorScheme.secondary)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SummaryCard("Последний месяц", analytics.latestMonth?.count?.toString() ?: "—", analytics.latestMonth?.title ?: "Нет данных", Modifier.weight(1f))
            SummaryCard("Топ тип", analytics.topRequestType?.count?.toString() ?: "—", analytics.topRequestType?.title ?: "Нет данных",
                Modifier.weight(1f), MaterialTheme.colorScheme.secondary)
        }
    }
}

@Composable
private fun SummaryCard(title: String, value: String, subtitle: String, modifier: Modifier, tint: Color = MaterialTheme.colorScheme.primary) {
    AnalyticsCard(modifier) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = tint)
    }
}

@Composable
private fun AnalyticsCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    AppPanel(modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
private fun SectionTitle(title: String, caption: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        if (caption != null) Text(caption, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun StatRow(title: String, value: String, tint: Color = MaterialTheme.colorScheme.primary) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
            color = tint, textAlign = androidx.compose.ui.text.style.TextAlign.End)
    }
}

@Composable
private fun EmptyText(text: String) { Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }

@Composable
private fun DistributionBar(count: Int, max: Int, tint: Color) {
    Box(Modifier.fillMaxWidth().height(10.dp).background(MaterialTheme.colorScheme.secondary.copy(alpha = .12f), RoundedCornerShape(7.dp))) {
        val ratio = if (max <= 0) 0f else (count.toFloat() / max).coerceIn(0f, 1f)
        if (ratio > 0f) Box(Modifier.fillMaxWidth(ratio).height(10.dp).background(tint, RoundedCornerShape(7.dp)))
    }
}

@Composable
private fun MonthlyChart(analytics: ClosedRequestsAnalytics) {
    val months = remember(analytics) { analytics.months.asReversed() }
    val list = rememberLazyListState()
    LaunchedEffect(analytics.latestMonth?.id) { if (months.isNotEmpty()) list.scrollToItem(months.lastIndex) }
    LazyRow(Modifier.fillMaxWidth(), state = list, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(months, key = { it.id }) { month ->
            val highlighted = month.id == analytics.latestMonth?.id
            val tint = if (highlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary.copy(alpha = .7f)
            Column(Modifier.width(58.dp).clearAndSetSemantics { contentDescription = "${month.title}: ${month.count}" },
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(month.count.toString(), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                Box(Modifier.fillMaxWidth().height(88.dp).background(MaterialTheme.colorScheme.secondary.copy(alpha = .12f), RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.BottomCenter) {
                    val height = (88f * month.count / analytics.maxMonthCount.coerceAtLeast(1)).coerceIn(16f, 88f)
                    Box(Modifier.fillMaxWidth().height(height.dp).background(tint, RoundedCornerShape(10.dp)))
                }
                Text(month.shortTitle, style = MaterialTheme.typography.labelSmall, color = tint)
            }
        }
    }
}

@Composable
private fun MonthAccordion(month: AnalyticsMonth, max: Int, highlighted: Boolean, expanded: Boolean, onToggle: () -> Unit) {
    val tint = MaterialTheme.colorScheme.primary.copy(alpha = if (highlighted) 1f else .8f)
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(bottom = 14.dp)) {
        Column(Modifier.fillMaxWidth().semantics { stateDescription = if (expanded) "Развёрнуто" else "Свёрнуто" }
            .clickable(role = Role.Button, onClickLabel = "Показать календарь дней", onClick = onToggle).padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(month.title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text(month.count.toString(), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = tint)
                Icon(if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, contentDescription = null, tint = tint)
            }
            DistributionBar(month.count, max, tint)
        }
        AnimatedVisibility(expanded) {
            Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                StatRow("Календарь по дням", "За месяц: ${month.count}")
                CalendarMonth(month)
            }
        }
    }
}

@Composable
private fun CalendarMonth(month: AnalyticsMonth) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс").forEach { weekday ->
                Text(weekday, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        month.calendar.chunked(7).forEach { week ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                week.forEach { day ->
                    if (day.date == null) Spacer(Modifier.weight(1f).height(56.dp))
                    else Surface(Modifier.weight(1f).heightIn(min = 56.dp).clearAndSetSemantics {
                        contentDescription = "${day.date.format(ANALYTICS_DAY)}: ${day.count} заявок"
                    }, shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.secondary.copy(alpha = if (day.count > 0) .16f else .07f)) {
                        Column(Modifier.padding(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(day.dayNumber.toString(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (day.count > 0) Text(day.count.toString(), style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    }
}

private fun archiveUpdatedAt(millis: Long?): String = millis?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(ANALYTICS_UPDATED) } ?: "—"
private val ANALYTICS_UPDATED = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm", Locale.forLanguageTag("ru-RU"))
private val ANALYTICS_DAY = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.forLanguageTag("ru-RU"))
