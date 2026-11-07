package ru.lumastack.lumawork.ui.analytics

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import ru.lumastack.lumawork.data.analytics.*
import ru.lumastack.lumawork.domain.analytics.*
import ru.lumastack.lumawork.ui.adaptive.AppWidthClass
import ru.lumastack.lumawork.ui.requests.RequestLoadingTitle
import ru.lumastack.lumawork.ui.shell.AppHeaderSegments
import ru.lumastack.lumawork.ui.simpleone.SimpleOneUiState
import ru.lumastack.lumawork.ui.theme.*
import java.time.Instant
import java.time.format.DateTimeFormatter

internal fun leaderboardUsesSidePanel(widthClass: AppWidthClass, widthDp: Float, fontScale: Float): Boolean =
    widthClass == AppWidthClass.Expanded && widthDp / fontScale.coerceAtLeast(1f) >= 900f

@Composable
fun AnalyticsRoute(repository: LeaderboardRepository, publication: WorkStatisticsSyncCoordinator?, state: SimpleOneUiState,
    widthClass: AppWidthClass, onRefresh: () -> Unit) {
    val access by repository.access.state.collectAsStateWithLifecycle()
    val ranking by repository.state.collectAsStateWithLifecycle()
    var selectedRanking by remember { mutableStateOf(false) }
    var cityMenu by remember { mutableStateOf(false) }
    val enabled = access.owner?.let(repository.access::allows) == true
    val listState = rememberLazyListState()
    val savedState = rememberSaveableStateHolder()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val snackbar = remember { SnackbarHostState() }
    val notice by publication?.notice?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(null) }
    LaunchedEffect(notice) { notice?.let { snackbar.showSnackbar(it) } }
    LaunchedEffect(enabled) { if (!enabled) { selectedRanking = false; cityMenu = false } }
    LaunchedEffect(enabled, selectedRanking, state.isAuthorized) {
        if (enabled && selectedRanking && state.isAuthorized &&
            (ranking.response == null || ranking.response!!.periodKey != ranking.filter.period.key())) repository.refresh()
    }
    LaunchedEffect(repository, lifecycle, selectedRanking) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                repository.access.refresh()
                val current = repository.state.value
                if (selectedRanking && repository.access.state.value.enabled == true &&
                    current.response?.periodKey?.let { it != current.filter.period.key() } == true) repository.refresh()
                delay(30_000)
            }
        }
    }
    if (enabled) AppHeaderSegments(listOf("Статистика", "Рейтинг"), if (selectedRanking) 1 else 0,
        onSelect = { selectedRanking = it == 1 }, actions = {
            if (selectedRanking) Box {
                IconButton(onClick = { cityMenu = true }) {
                    Icon(Icons.Default.LocationOn, contentDescription = "Город: ${ranking.cities.firstOrNull { it.key == ranking.filter.city }?.title ?: ranking.filter.city}")
                }
                DropdownMenu(expanded = cityMenu, onDismissRequest = { cityMenu = false }, modifier = Modifier.heightIn(max = 420.dp)) {
                    ranking.cities.forEach { city ->
                        DropdownMenuItem(text = { Text(city.title) }, onClick = {
                            cityMenu = false; repository.select(ranking.filter.copy(city = city.key))
                        }, trailingIcon = { if (ranking.filter.city == city.key) Icon(Icons.Default.Check, "Выбрано") })
                    }
                }
            }
        })
    Box(Modifier.fillMaxSize()) {
        if (enabled && selectedRanking) {
            if (!state.isAuthorized) Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text("Для рейтинга войдите в SimpleOne в разделе «Заявки».", textAlign = TextAlign.Center)
            } else LeaderboardContent(ranking, widthClass, listState,
                onFilter = { repository.select(it) }, onRefresh = { repository.refresh() }, onNext = { repository.loadNext() }, onCity = { cityMenu = true })
        } else savedState.SaveableStateProvider("statistics") { AnalyticsScreen(state, widthClass, onRefresh) }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
internal fun LeaderboardContent(state: LeaderboardState, widthClass: AppWidthClass, listState: LazyListState = rememberLazyListState(),
    onFilter: (LeaderboardFilter) -> Unit, onRefresh: () -> Unit, onNext: () -> Unit, onCity: () -> Unit) {
    val fontScale = LocalDensity.current.fontScale
    AppRefreshContainer(Modifier.fillMaxSize().background(appScreenBackground()),
        isRefreshing = state.isLoading && state.response != null, onRefresh = onRefresh) {
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            val sidePanel = leaderboardUsesSidePanel(widthClass, maxWidth.value, fontScale)
            Column(Modifier.widthIn(max = 1200.dp).fillMaxSize().padding(horizontal = AppContentHorizontalPadding)) {
                RankingFilters(state.filter, onFilter)
                if (sidePanel) Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Column(Modifier.width(280.dp).verticalScroll(rememberScrollState()).padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        OwnPosition(state, skeleton = state.isLoading && state.response == null)
                        RankingFreshness(state)
                    }
                    RankingList(state, listState, false, onRefresh, onNext, onCity, Modifier.weight(1f))
                } else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    RankingList(state, listState, true, onRefresh, onNext, onCity, Modifier.widthIn(max = 760.dp).fillMaxSize())
                }
            }
        }
    }
}

@Composable
private fun RankingFilters(filter: LeaderboardFilter, onFilter: (LeaderboardFilter) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        val vertical = maxWidth.value / LocalDensity.current.fontScale < 360
        if (vertical) Column(Modifier.fillMaxWidth()) {
            RankingMenu(LeaderboardCategory.entries.map { it.title }, LeaderboardCategory.entries.indexOf(filter.category), "Тип оборудования") { onFilter(filter.copy(category = LeaderboardCategory.entries[it])) }
            RankingMenu(LeaderboardPeriod.entries.map { it.title }, LeaderboardPeriod.entries.indexOf(filter.period), "Период") { onFilter(filter.copy(period = LeaderboardPeriod.entries[it])) }
        } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { RankingMenu(LeaderboardCategory.entries.map { it.title }, LeaderboardCategory.entries.indexOf(filter.category), "Тип оборудования") { onFilter(filter.copy(category = LeaderboardCategory.entries[it])) } }
            Box(Modifier.weight(1.35f), contentAlignment = Alignment.CenterEnd) { RankingMenu(LeaderboardPeriod.entries.map { it.title }, LeaderboardPeriod.entries.indexOf(filter.period), "Период") { onFilter(filter.copy(period = LeaderboardPeriod.entries[it])) } }
        }
    }
}
@Composable
private fun RankingMenu(titles: List<String>, selected: Int, label: String, onSelect: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }, modifier = Modifier.semantics { contentDescription = "$label: ${titles[selected]}" }) {
            Text(titles[selected], style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f, fill = false))
            Icon(Icons.Default.KeyboardArrowDown, contentDescription = null, modifier = Modifier.padding(start = 4.dp).size(18.dp))
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            titles.forEachIndexed { index, title ->
                DropdownMenuItem(text = { Text(title) }, trailingIcon = { if (index == selected) Icon(Icons.Default.Check, "Выбрано") },
                    onClick = { expanded = false; onSelect(index) })
            }
        }
    }
}
@Composable
private fun RankingList(state: LeaderboardState, scroll: LazyListState, includeOwn: Boolean, onRefresh: () -> Unit, onNext: () -> Unit, onCity: () -> Unit, modifier: Modifier) {
    val response = state.response?.participantsOnly()
    LazyColumn(modifier, state = scroll, contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item("title") { RequestLoadingTitle(isLoading = state.isLoading && response != null,
            text = "Рейтинг пользователей", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold)) }
        if (includeOwn) {
            if (response?.me != null || state.isLoading && response == null) item("me") { OwnPosition(state, state.isLoading && response == null) }
            if (response != null) item("freshness") { RankingFreshness(state) }
        }
        if (state.error != null) item("error") {
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(state.error, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = if (state.citySelectionRequired) onCity else onRefresh) { Text(if (state.citySelectionRequired) "Выбрать город" else "Повторить") }
            }
        }
        item("columns") { Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Место", Modifier.width(60.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Инженер", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Заявок", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } }
        if (state.isLoading && response == null) items(7, key = { "skeleton:$it" }) { RankingSkeleton() }
        else if (response != null && response.items.isEmpty()) item("empty") {
            Text("Пока нет пользователей с выгрузкой SO", Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else items(response?.items.orEmpty(), key = { "user:${it.userId}" }) { entry -> RankingRow(entry) }
        if (state.isPaging) item("paging") { Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp) } }
        else if (response?.nextOffset != null && !state.citySelectionRequired) item("more") { Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { TextButton(onClick = onNext) { Text("Загрузить ещё") } } }
    }
}
@Composable
private fun OwnPosition(state: LeaderboardState, skeleton: Boolean) {
    if (skeleton) { RankingSkeleton(own = true); return }
    val me = state.response?.participantsOnly()?.me ?: return
    AppCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Ваша позиция", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            RankingRow(me, own = true)
        }
    }
}
@Composable
private fun RankingFreshness(state: LeaderboardState) {
    val response = state.response?.participantsOnly() ?: return
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Участников рейтинга: ${response.usersWithDataCount}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (state.isSaved) Text("Сохранённые данные · ${rankingTime(state.savedAt)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        response.me?.sourceSyncedAt?.let { Text("Ваши заявки обновлены: ${rankingTime(it)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
private fun rankingTime(value: String?): String = runCatching { DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").format(Instant.parse(value).atZone(workStatisticsZone)) }.getOrDefault("—")

@Composable
private fun RankingRow(entry: LeaderboardEntry, own: Boolean = false) {
    val value = if (entry.hasData) entry.value.toString() else "Нет данных"
    val position = entry.rank?.let { "$it место" } ?: "Без места"
    val highlight = entry.isCurrentUser && !own
    BoxWithConstraints(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
        .background(if (highlight) MaterialTheme.colorScheme.primaryContainer.copy(alpha = .35f) else if (own) Color.Transparent else AppSurfaces.card)
        .semantics(mergeDescendants = true) { contentDescription = "${entry.displayName}, $position, ${if (entry.hasData) "$value заявок" else value}" }
        .padding(if (own) 0.dp else 12.dp)) {
        val stacked = maxWidth.value / LocalDensity.current.fontScale < 340
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            RankingPlace(entry.rank)
            if (stacked) Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(entry.displayName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Text(if (entry.hasData) "$value заявок" else value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            } else {
                Text(entry.displayName, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Text(value, style = if (entry.hasData) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.End, fontWeight = if (entry.hasData) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
    }
}
@Composable
private fun RankingPlace(rank: Int?) {
    Box(Modifier.width(48.dp).heightIn(min = 44.dp).clearAndSetSemantics { }, contentAlignment = Alignment.Center) {
        if (rank in 1..3) {
            val color = when (rank) { 1 -> Color(0xFFB78A25); 2 -> Color(0xFF8A969F); else -> Color(0xFFB47446) }
            Canvas(Modifier.size(32.dp, 42.dp)) {
                val ribbon = Path().apply {
                    moveTo(size.width * .24f, size.height * .48f); lineTo(size.width * .16f, size.height)
                    lineTo(size.width * .48f, size.height * .82f); lineTo(size.width * .8f, size.height)
                    lineTo(size.width * .72f, size.height * .48f); close()
                }
                drawPath(ribbon, color.copy(alpha = .65f))
                drawCircle(color, radius = size.width * .46f, center = Offset(size.width / 2, size.width / 2))
            }
            Text(rank.toString(), Modifier.offset(y = (-5).dp), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = Color.White)
        } else Text(rank?.toString() ?: "—", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
@Composable
private fun RankingSkeleton(own: Boolean = false) {
    val transition = rememberInfiniteTransition(label = "leaderboard-loading")
    val phase by transition.animateFloat(-1f, 2f, infiniteRepeatable(tween(1500, easing = LinearEasing)), label = "ranking-shimmer")
    val base = MaterialTheme.colorScheme.onSurface.copy(alpha = .06f)
    val light = MaterialTheme.colorScheme.onSurface.copy(alpha = .13f)
    BoxWithConstraints(Modifier.fillMaxWidth().height(if (own) 120.dp else 72.dp).clip(RoundedCornerShape(16.dp)).background(AppSurfaces.card)
        .clearAndSetSemantics { contentDescription = if (own) "Загружается ваша позиция" else "Загружается рейтинг" }) {
        val pixels = with(LocalDensity.current) { maxWidth.toPx() }
        val brush = Brush.linearGradient(listOf(base, light, base), Offset(pixels * (phase - .4f), 0f), Offset(pixels * (phase + .4f), 0f))
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.Center) {
            if (own) Spacer(Modifier.width(120.dp).height(12.dp).background(brush, RoundedCornerShape(4.dp)))
            Row(Modifier.fillMaxWidth().padding(top = if (own) 16.dp else 0.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Spacer(Modifier.size(32.dp).background(brush, RoundedCornerShape(10.dp)))
                Spacer(Modifier.weight(1f).height(14.dp).background(brush, RoundedCornerShape(4.dp)))
                Spacer(Modifier.width(56.dp).height(18.dp).background(brush, RoundedCornerShape(4.dp)))
            }
        }
    }
}
