package ru.lumastack.lumawork.ui.backpack

import ru.lumastack.lumawork.ui.theme.AppContentHorizontalPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import ru.lumastack.lumawork.ui.theme.AppRefreshContainer
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import ru.lumastack.lumawork.ui.shell.AppHeaderBackHandler as BackHandler
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TextButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.ui.res.painterResource
import ru.lumastack.lumawork.R
import ru.lumastack.lumawork.domain.backpack.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import ru.lumastack.lumawork.ui.theme.AppTextField
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
import ru.lumastack.lumawork.domain.backpack.photoFor
import ru.lumastack.lumawork.ui.adaptive.AppWidthClass
import ru.lumastack.lumawork.ui.simpleone.SimpleOneUiState
import ru.lumastack.lumawork.ui.theme.AppSurfaces
import ru.lumastack.lumawork.ui.theme.appScreenBackground
import ru.lumastack.lumawork.ui.theme.AppCard
import ru.lumastack.lumawork.ui.theme.AppPanel

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
    LaunchedEffect(simpleOneState.snapshot.currentUser?.sysId, simpleOneState.isAuthorized, simpleOneState.snapshot.updatedAtEpochMillis) {
        backpackViewModel.activate(
            simpleOneUserId = simpleOneState.snapshot.currentUser?.sysId.orEmpty(),
            isAuthorized = simpleOneState.isAuthorized,
        )
    }
    var scannerOpen by rememberSaveable { mutableStateOf(false) }
    var serial by rememberSaveable { mutableStateOf<String?>(null) }
    if (scannerOpen) BackpackScannerSheet(onDismiss = { scannerOpen = false }, onScan = { backpackViewModel.applyScan(it); scannerOpen = false })
    serial?.let { value -> SerialBarcodeSheet(value, onDismiss = { serial = null }) }
    if (state.officeOpen) OfficeEquipmentSheet(state, imageRepository,
        onDismiss = backpackViewModel::closeOffice, onQuery = backpackViewModel::updateOfficeQuery,
        onRefresh = backpackViewModel::refreshOffice, onSelect = backpackViewModel::openOfficeItem,
        onBack = backpackViewModel::closeOfficeItem, onSerial = { serial = SerialBarcode.serial(it) })
    BackpackScreen(
        state = state,
        widthClass = widthClass,
        imageRepository = imageRepository,
        onQueryChange = backpackViewModel::updateQuery,
        onRefresh = backpackViewModel::refresh,
        onOpenGroup = backpackViewModel::openGroup,
        onBack = backpackViewModel::closeGroup,
        onScan = { scannerOpen = true }, onSelectLocation = backpackViewModel::selectLocation,
        onSetLocation = backpackViewModel::setLocation, onOpenOffice = backpackViewModel::openOffice,
        onSerial = { serial = SerialBarcode.serial(it) },
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BackpackScreen(
    state: BackpackUiState,
    widthClass: AppWidthClass,
    imageRepository: BackpackImageRepository,
    onQueryChange: (String) -> Unit,
    onRefresh: () -> Unit,
    onOpenGroup: (String) -> Unit,
    onBack: () -> Unit,
    onScan: () -> Unit, onSelectLocation: (BackpackLocation?) -> Unit,
    onSetLocation: (BackpackItem, BackpackLocation) -> Unit, onOpenOffice: () -> Unit, onSerial: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val groups = remember(state.snapshot?.items) { backpackGroups(state.snapshot?.items.orEmpty()) }
    val visibleGroups = remember(state.snapshot, state.query, state.locationFilter) { backpackGroups(visibleBackpackItems(state.snapshot, state.query, state.locationFilter)) }
    val selected = visibleGroups.firstOrNull { it.id == state.selectedGroupId } ?: groups.firstOrNull { it.id == state.selectedGroupId }
    val background = appScreenBackground()
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
    AppRefreshContainer(modifier.fillMaxSize().background(background), isRefreshing = state.isLoading, onRefresh = onRefresh,
        enabled = state.isAuthorized, contentAlignment = Alignment.TopCenter) {
        if (widthClass == AppWidthClass.Expanded) {
            Row(Modifier.widthIn(max = 1400.dp).fillMaxSize().padding(horizontal = AppContentHorizontalPadding, vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                AppPanel(Modifier.weight(.45f).fillMaxHeight()) {
                    BackpackOverview(state, groups, visibleGroups, AppWidthClass.Compact,
                        imageRepository, onQueryChange, onOpenGroup, listState, onScan, onSelectLocation, onOpenOffice)
                }
                AppPanel(Modifier.weight(.55f).fillMaxHeight()) {
                    if (selected != null) {
                        BackpackDetails(selected, state.photoCatalog, imageRepository,
                            AppWidthClass.Compact, onBack, detailState, state, onSetLocation, onSerial, showBackButton = false)
                    } else {
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center) {
                            Text("Выберите оборудование", style = MaterialTheme.typography.titleLarge)
                            Text("Экземпляры и серийные номера откроются здесь.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        } else {
            BackpackOverview(state, groups, visibleGroups, widthClass, imageRepository,
                onQueryChange, onOpenGroup, listState, onScan, onSelectLocation, onOpenOffice)
        }
        if (selected != null && widthClass != AppWidthClass.Expanded) {
            ModalBottomSheet(containerColor = AppSurfaces.panel, tonalElevation = 0.dp, scrimColor = AppSurfaces.scrim, onDismissRequest = onBack, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), sheetMaxWidth = 800.dp) {
                BackpackDetails(selected, state.photoCatalog, imageRepository, widthClass, onBack, detailState, state, onSetLocation, onSerial)
            }
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
    onOpenGroup: (String) -> Unit,
    gridState: LazyGridState,
    onScan: () -> Unit, onSelectLocation: (BackpackLocation?) -> Unit, onOpenOffice: () -> Unit,
) {
    val columns = when (widthClass) {
        AppWidthClass.Compact -> 2
        AppWidthClass.Medium -> 3
        AppWidthClass.Expanded -> 4
    }
    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Fixed(columns),
        contentPadding = PaddingValues(horizontal = AppContentHorizontalPadding, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.widthIn(max = 1120.dp).fillMaxSize(),
    ) {
        if (state.simpleOneUserId.isNotBlank()) {
            item(key = "search", span = { GridItemSpan(maxLineSpan) }) {
                AppTextField(
                    value = state.query,
                    onValueChange = onQueryChange,
                    label = "Название или S/N", search = true,
                    trailingIcon = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (state.query.isNotBlank()) IconButton(onClick = { onQueryChange("") }) { Icon(Icons.Default.Close, "Очистить поиск") }
                            IconButton(onClick = onScan) { Icon(painterResource(R.drawable.ic_barcode_scan), "Сканировать штрихкод оборудования") }
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        item(key = "header", span = { GridItemSpan(maxLineSpan) }) { BackpackHeader(state, groups, visibleGroups, onSelectLocation, onOpenOffice) }
        when {
            state.simpleOneUserId.isBlank() -> item(key = "not-connected", span = { GridItemSpan(maxLineSpan) }) {
                MessageCard("SimpleOne не подключён", "Войдите в SimpleOne на экране «Заявки».")
            }
            groups.isEmpty() -> item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                MessageCard(
                    if (state.isLoading) "Загружаю оборудование…" else "Рюкзак пуст",
                    state.errorMessage ?: "Оборудование по выбранному фильтру не найдено.",
                )
            }
            visibleGroups.isEmpty() -> item(key = "not-found", span = { GridItemSpan(maxLineSpan) }) {
                MessageCard(if (state.query.isBlank()) "Нет оборудования" else "Ничего не найдено",
                    if (state.query.isBlank()) "Для фильтра «${state.locationFilter?.title ?: "Все"}» ничего не найдено." else "Оборудование по текущему поиску и фильтру не найдено.")
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
    onSelectLocation: (BackpackLocation?) -> Unit, onOpenOffice: () -> Unit,
) {
    var filterOpen by remember { mutableStateOf(false) }
    val totalQuantity = visibleGroups.sumOf(BackpackItemGroup::quantity)
    AppCard(
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
                        if (state.query.isBlank() && state.locationFilter == null) "Оборудование на руках" else "Показано ${visibleGroups.size} из ${groups.size} моделей",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Box {
                    TextButton(onClick = { filterOpen = true }, enabled = state.simpleOneUserId.isNotBlank()) {
                        Text(state.locationFilter?.title ?: "Все")
                        Icon(Icons.Default.KeyboardArrowDown, null)
                    }
                    DropdownMenu(filterOpen, onDismissRequest = { filterOpen = false }) {
                        DropdownMenuItem(text = { Text("Все") }, onClick = { onSelectLocation(null); filterOpen = false })
                        BackpackLocation.entries.forEach { location -> DropdownMenuItem(text = { Text(location.title) }, onClick = { onSelectLocation(location); filterOpen = false }) }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                HeaderMetric(totalQuantity.toString(), "единиц", Modifier.weight(1f))
                HeaderMetric(visibleGroups.size.toString(), "моделей", Modifier.weight(1f))
            }
            ru.lumastack.lumawork.ui.theme.AppButton(onClick = onOpenOffice, enabled = state.simpleOneUserId.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text("Моё оборудование", fontWeight = FontWeight.Bold)
                    Text("Техника, закреплённая в SimpleOne", style = MaterialTheme.typography.bodySmall)
                }
            }
            if (state.isLoading || state.loadingDetailIds.isNotEmpty() || state.isLoadingReturns) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text("Загрузка данных", style = MaterialTheme.typography.bodySmall)
                }
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
    AppCard(
        onClick = onClick,
        border = if (selected) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
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
    state: BackpackUiState, onSetLocation: (BackpackItem, BackpackLocation) -> Unit, onSerial: (String) -> Unit,
    showBackButton: Boolean = true,
) {
    val columns = if (widthClass == AppWidthClass.Compact) 1 else 2
    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Fixed(columns),
        contentPadding = PaddingValues(horizontal = AppContentHorizontalPadding, vertical = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.widthIn(max = 1050.dp).fillMaxSize(),
    ) {
        item(key = "detail-header", span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (showBackButton) ru.lumastack.lumawork.ui.shell.AppBackButton(onBack)
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
                            "Количество: ${group.quantity} шт. · Экземпляры: ${group.items.size}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        items(group.items, key = BackpackItem::id) { item -> BackpackInstanceCard(item, state, onSetLocation, onSerial) }
    }
}

@Composable
private fun BackpackInstanceCard(item: BackpackItem, state: BackpackUiState, onSetLocation: (BackpackItem, BackpackLocation) -> Unit, onSerial: (String) -> Unit) {
    var locationOpen by remember { mutableStateOf(false) }
    val snapshot = state.snapshot
    AppCard(
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
            Text("Экземпляр", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).clickable(enabled = SerialBarcode.serial(item.serialNumber) != null) { onSerial(item.serialNumber) }) {
                    Text("Серийный номер", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                    Text(item.serialNumber, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                    if (SerialBarcode.serial(item.serialNumber) != null) Text("Открыть штрихкод", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
                if (snapshot?.isReturn(item) == true) Text("Возврат ТО", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
            }
            if (item.id in state.loadingDetailIds && item.receivedAt == NOT_SET) Text("Загружается дата выдачи…", style = MaterialTheme.typography.bodySmall)
            else InstanceField("Дата выдачи", item.receivedAt)
            Box {
                TextButton(onClick = { locationOpen = true }) {
                    Text(snapshot?.location(item)?.title ?: "На складе")
                    Icon(Icons.Default.KeyboardArrowDown, null)
                }
                DropdownMenu(locationOpen, onDismissRequest = { locationOpen = false }) {
                    BackpackLocation.entries.forEach { location -> DropdownMenuItem(text = { Text(location.title) }, onClick = { onSetLocation(item, location); locationOpen = false }) }
                }
            }
            if (item.responsible != NOT_SET) InstanceField("Ответственный", item.responsible)
            if (item.quantity > 1) InstanceField("Остаток", "${item.quantity} шт.")
        }
    }
}

@Composable
private fun InstanceField(title: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
        SelectionContainer { Text(value, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable
internal fun BackpackImage(
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
    AppCard(
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
            Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private val UPDATED_FORMATTER = DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.forLanguageTag("ru"))
