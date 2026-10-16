package ru.lumastack.lumawork.ui.backpack

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import ru.lumastack.lumawork.ui.theme.AppOutlinedButton as OutlinedButton
import ru.lumastack.lumawork.ui.theme.AppTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import ru.lumastack.lumawork.data.backpack.BackpackImageRepository
import ru.lumastack.lumawork.data.backpack.BackpackPhotoRepository
import ru.lumastack.lumawork.data.backpack.BackpackRepository
import ru.lumastack.lumawork.domain.backpack.BackpackItem
import ru.lumastack.lumawork.domain.backpack.BackpackItemGroup
import ru.lumastack.lumawork.domain.backpack.BackpackPhotoCatalog
import ru.lumastack.lumawork.domain.backpack.NOT_SET
import ru.lumastack.lumawork.domain.backpack.backpackGroups
import ru.lumastack.lumawork.domain.backpack.filterBackpackGroups
import ru.lumastack.lumawork.domain.backpack.photoFor
import ru.lumastack.lumawork.ui.adaptive.AppWidthClass
import ru.lumastack.lumawork.ui.simpleone.SimpleOneUiState
import ru.lumastack.lumawork.ui.theme.LumaBackgroundBottomDark
import ru.lumastack.lumawork.ui.theme.LumaBackgroundBottomLight
import ru.lumastack.lumawork.ui.theme.LumaBackgroundTopDark
import ru.lumastack.lumawork.ui.theme.LumaBackgroundTopLight

@Composable
fun BackpackRoute(
    ownerUserId: String,
    startOffline: Boolean,
    simpleOneState: SimpleOneUiState,
    repository: BackpackRepository,
    photoRepository: BackpackPhotoRepository,
    imageRepository: BackpackImageRepository,
    widthClass: AppWidthClass,
    modifier: Modifier = Modifier,
) {
    val backpackViewModel: BackpackViewModel = viewModel(
        key = "backpack-$ownerUserId-$startOffline",
        factory = BackpackViewModel.factory(repository, photoRepository, ownerUserId, startOffline),
    )
    val state by backpackViewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(simpleOneState.snapshot.currentUser?.sysId, simpleOneState.isAuthorized) {
        backpackViewModel.activate(
            simpleOneUserId = simpleOneState.snapshot.currentUser?.sysId.orEmpty(),
            isAuthorized = simpleOneState.isAuthorized,
        )
    }
    BackpackScreen(
        state = state,
        widthClass = widthClass,
        imageRepository = imageRepository,
        onQueryChange = backpackViewModel::updateQuery,
        onRefresh = backpackViewModel::refresh,
        onOpenGroup = backpackViewModel::openGroup,
        onBack = backpackViewModel::closeGroup,
        modifier = modifier,
    )
}

@Composable
private fun BackpackScreen(
    state: BackpackUiState,
    widthClass: AppWidthClass,
    imageRepository: BackpackImageRepository,
    onQueryChange: (String) -> Unit,
    onRefresh: () -> Unit,
    onOpenGroup: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val groups = remember(state.snapshot?.items) { backpackGroups(state.snapshot?.items.orEmpty()) }
    val visibleGroups = remember(groups, state.query) { filterBackpackGroups(groups, state.query) }
    val selected = groups.firstOrNull { it.id == state.selectedGroupId }
    val isDark = MaterialTheme.colorScheme.background == LumaBackgroundTopDark
    val background = remember(isDark) {
        Brush.verticalGradient(
            if (isDark) listOf(LumaBackgroundTopDark, LumaBackgroundBottomDark)
            else listOf(LumaBackgroundTopLight, LumaBackgroundBottomLight),
        )
    }
    val listState = rememberLazyGridState()
    val detailState = rememberLazyGridState()
    var displayedDetailId by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(selected?.id) {
        if (displayedDetailId != selected?.id) {
            detailState.scrollToItem(0)
            displayedDetailId = selected?.id
        }
    }
    BackHandler(enabled = selected != null, onBack = onBack)
    Box(modifier.fillMaxSize().background(background), contentAlignment = Alignment.TopCenter) {
        if (widthClass == AppWidthClass.Expanded) {
            Row(Modifier.widthIn(max = 1400.dp).fillMaxSize().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Surface(Modifier.weight(.45f).fillMaxHeight(), shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = .65f)) {
                    BackpackOverview(state, groups, visibleGroups, AppWidthClass.Compact,
                        imageRepository, onQueryChange, onRefresh, onOpenGroup, listState)
                }
                Surface(Modifier.weight(.55f).fillMaxHeight(), shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = .65f)) {
                    if (selected != null) {
                        BackpackDetails(selected, state.photoCatalog, imageRepository,
                            AppWidthClass.Compact, onBack, detailState, showBackButton = false)
                    } else {
                        Column(Modifier.fillMaxSize().padding(28.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center) {
                            Text("Выберите оборудование", style = MaterialTheme.typography.titleLarge)
                            Text("Экземпляры и серийные номера откроются здесь.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        } else if (selected == null) {
            BackpackOverview(state, groups, visibleGroups, widthClass, imageRepository,
                onQueryChange, onRefresh, onOpenGroup, listState)
        } else {
            BackpackDetails(selected, state.photoCatalog, imageRepository, widthClass,
                onBack, detailState)
        }
    }
}

@Composable
private fun BackpackOverview(
    state: BackpackUiState,
    groups: List<BackpackItemGroup>,
    visibleGroups: List<BackpackItemGroup>,
    widthClass: AppWidthClass,
    imageRepository: BackpackImageRepository,
    onQueryChange: (String) -> Unit,
    onRefresh: () -> Unit,
    onOpenGroup: (String) -> Unit,
    gridState: LazyGridState,
) {
    val columns = when (widthClass) {
        AppWidthClass.Compact -> 2
        AppWidthClass.Medium -> 3
        AppWidthClass.Expanded -> 4
    }
    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Fixed(columns),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.widthIn(max = 1120.dp).fillMaxSize(),
    ) {
        item(key = "header", span = { GridItemSpan(maxLineSpan) }) {
            BackpackHeader(state, groups, visibleGroups, onRefresh)
        }
        if (groups.isNotEmpty()) {
            item(key = "search", span = { GridItemSpan(maxLineSpan) }) {
                AppTextField(
                    value = state.query,
                    onValueChange = onQueryChange,
                    label = "Название, серийный номер или дата", search = true,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        when {
            state.simpleOneUserId.isBlank() -> item(key = "not-connected", span = { GridItemSpan(maxLineSpan) }) {
                MessageCard("SimpleOne не подключён", "Войдите в SimpleOne на экране «Заявки».")
            }
            groups.isEmpty() -> item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                MessageCard(
                    if (state.isLoading) "Загружаю оборудование…" else "Рюкзак пуст",
                    state.errorMessage ?: "SimpleOne не вернул оборудование по текущему фильтру.",
                )
            }
            visibleGroups.isEmpty() -> item(key = "not-found", span = { GridItemSpan(maxLineSpan) }) {
                MessageCard("Ничего не найдено", "Измените название, серийный номер или дату.")
            }
            else -> items(visibleGroups, key = BackpackItemGroup::id) { group ->
                BackpackTile(
                    group = group,
                    imageUrl = state.photoCatalog.photoFor(group.name)?.resolvedUrl,
                    imageRepository = imageRepository,
                    onClick = { onOpenGroup(group.id) },
                    selected = group.id == state.selectedGroupId,
                )
            }
        }
    }
}

@Composable
private fun BackpackHeader(
    state: BackpackUiState,
    groups: List<BackpackItemGroup>,
    visibleGroups: List<BackpackItemGroup>,
    onRefresh: () -> Unit,
) {
    val totalQuantity = groups.sumOf(BackpackItemGroup::quantity)
    Card(
        colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)),
        shape = RoundedCornerShape(22.dp),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("Мой рюкзак", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
                    Text(
                        if (state.query.isBlank()) "Оборудование на руках" else "Показано ${visibleGroups.size} из ${groups.size} моделей",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (state.isLoading) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                else OutlinedButton(onClick = onRefresh, enabled = state.isAuthorized) { Text("Обновить") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                HeaderMetric(totalQuantity.toString(), "единиц", Modifier.weight(1f))
                HeaderMetric(groups.size.toString(), "моделей", Modifier.weight(1f))
            }
            state.snapshot?.updatedAtEpochMillis?.let {
                Text(
                    "Обновлено ${UPDATED_FORMATTER.format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()))}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            state.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun HeaderMetric(value: String, title: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f), RoundedCornerShape(14.dp))
            .padding(12.dp),
    ) {
        Text(value, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
        Text(title, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun BackpackTile(
    group: BackpackItemGroup,
    imageUrl: String?,
    imageRepository: BackpackImageRepository,
    onClick: () -> Unit,
    selected: Boolean = false,
) {
    Card(
        onClick = onClick,
        border = if (selected) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
        colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            BackpackImage(imageUrl, imageRepository, Modifier.fillMaxWidth().aspectRatio(1.25f))
            Text(
                group.name,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                minLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "Остаток: ${group.quantity}",
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Composable
private fun BackpackDetails(
    group: BackpackItemGroup,
    photoCatalog: BackpackPhotoCatalog,
    imageRepository: BackpackImageRepository,
    widthClass: AppWidthClass,
    onBack: () -> Unit,
    gridState: LazyGridState,
    showBackButton: Boolean = true,
) {
    val columns = if (widthClass == AppWidthClass.Compact) 1 else 2
    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Fixed(columns),
        contentPadding = PaddingValues(20.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.widthIn(max = 1050.dp).fillMaxSize(),
    ) {
        item(key = "detail-header", span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (showBackButton) OutlinedButton(onClick = onBack) { Text("Назад") }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BackpackImage(
                        photoCatalog.photoFor(group.name)?.resolvedUrl,
                        imageRepository,
                        Modifier.size(if (widthClass == AppWidthClass.Compact) 96.dp else 138.dp),
                    )
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(group.name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.headlineSmall)
                        Text(
                            "${group.quantity} шт. · ${group.items.size} записей",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        items(group.items, key = BackpackItem::id) { item -> BackpackInstanceCard(item) }
    }
}

@Composable
private fun BackpackInstanceCard(item: BackpackItem) {
    Card(
        colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
            Text("Экземпляр", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            InstanceField("Серийный номер", item.serialNumber)
            InstanceField("Дата выдачи", item.receivedAt)
            if (item.responsible != NOT_SET) InstanceField("Ответственный", item.responsible)
            if (item.quantity > 1) InstanceField("Остаток", "${item.quantity} шт.")
        }
    }
}

@Composable
private fun InstanceField(title: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
        Text(value, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun BackpackImage(
    url: String?,
    repository: BackpackImageRepository,
    modifier: Modifier = Modifier,
) {
    var bitmap by remember(url) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(url) { bitmap = url?.let { repository.load(it) } }
    Box(
        modifier = modifier.background(
            Brush.linearGradient(
                listOf(
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                    MaterialTheme.colorScheme.secondary.copy(alpha = 0.07f),
                ),
            ),
            RoundedCornerShape(14.dp),
        ),
        contentAlignment = Alignment.Center,
    ) {
        val image = bitmap
        if (image == null) {
            Text("POS", color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
        } else {
            Image(
                bitmap = image.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().padding(8.dp),
            )
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

private val UPDATED_FORMATTER = DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.forLanguageTag("ru"))
