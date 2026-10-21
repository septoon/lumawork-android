package ru.lumastack.lumawork.ui.home

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.KeyboardArrowDown

import android.content.ClipData
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import ru.lumastack.lumawork.ui.theme.AppSegmentedControl
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import ru.lumastack.lumawork.domain.route.RouteEndpointKind
import ru.lumastack.lumawork.domain.route.RouteEndpointRole
import ru.lumastack.lumawork.domain.route.RouteStop
import ru.lumastack.lumawork.domain.route.RouteWorkType
import ru.lumastack.lumawork.ui.theme.AppButton
import ru.lumastack.lumawork.ui.theme.AppOutlinedButton
import ru.lumastack.lumawork.ui.theme.AppTextField
import ru.lumastack.lumawork.ui.theme.AppSurfaces
import ru.lumastack.lumawork.ui.theme.AppCard

@Composable
internal fun RouteTimelineCard(
    state: RouteUiState,
    onEditStop: (String) -> Unit,
    onAddStop: () -> Unit,
    onRemoveStop: (String) -> Unit,
    onReorderStop: (String, String) -> Unit,
    onEndpointSelect: (RouteEndpointRole, RouteEndpointKind) -> Unit,
    compact: Boolean = false,
) {
    val bounds = remember(state.record.date, state.record.workType) { mutableStateMapOf<String, Rect>() }
    var draggedId by remember(state.record.date, state.record.workType) { mutableStateOf<String?>(null) }
    var pendingDelete by rememberSaveable(state.record.date, state.record.workType) { mutableStateOf<String?>(null) }
    val middleStops = state.record.stops.drop(1).dropLast(1)
    val currentStops by rememberUpdatedState(middleStops)
    val reorder by rememberUpdatedState(onReorderStop)
    val haptics = LocalHapticFeedback.current

    AppCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(if (compact) 20.dp else 28.dp)) {
        Column(Modifier.padding(horizontal = if (compact) 16.dp else 20.dp)) {
            if (compact) Text("Маршрут", Modifier.padding(top = 16.dp, bottom = 4.dp),
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            RouteEndpointSection(RouteEndpointRole.Start, state, onEndpointSelect, compact)
            HorizontalDivider()
            middleStops.forEachIndexed { index, stop ->
                key(stop.id) {
                    var menuOpen by remember { mutableStateOf(false) }
                    var handleTop by remember { mutableFloatStateOf(0f) }
                    var dragY by remember { mutableFloatStateOf(0f) }
                    var lastTargetId by remember { mutableStateOf<String?>(null) }
                    Box {
                        Row(Modifier.fillMaxWidth().onGloballyPositioned { bounds[stop.id] = it.boundsInRoot() }
                            .alpha(if (draggedId == stop.id) .7f else 1f)
                            .semantics(mergeDescendants = true) {
                                customActions = buildList {
                                    add(CustomAccessibilityAction("Редактировать") { onEditStop(stop.id); true })
                                    if (middleStops.size > 1) add(CustomAccessibilityAction("Удалить") { pendingDelete = stop.id; true })
                                    if (index > 0) add(CustomAccessibilityAction("Выше") { onReorderStop(stop.id, middleStops[index - 1].id); true })
                                    if (index < middleStops.lastIndex) add(CustomAccessibilityAction("Ниже") { onReorderStop(stop.id, middleStops[index + 1].id); true })
                                }
                            }.padding(vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(Modifier.weight(1f).combinedClickable(onClick = { onEditStop(stop.id) }, onLongClick = { menuOpen = true }),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                                    Box(Modifier.size(30.dp), contentAlignment = Alignment.Center) {
                                        Text("${index + 1}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                    }
                                }
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(stop.address.ifBlank { "Адрес не указан" }, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                    Text(stop.requestNumber.ifBlank { "Номер заявки не указан" }, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            Box(Modifier.size(48.dp).onGloballyPositioned { handleTop = it.boundsInRoot().top }
                                .semantics { contentDescription = "Переместить точку ${index + 1}" }
                                .pointerInput(stop.id) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = { offset -> draggedId = stop.id; lastTargetId = null; dragY = handleTop + offset.y; haptics.performHapticFeedback(HapticFeedbackType.LongPress) },
                                        onDragEnd = { draggedId = null }, onDragCancel = { draggedId = null },
                                        onDrag = { change, amount ->
                                            change.consume()
                                            dragY += amount.y
                                            val target = currentStops.firstOrNull { candidate ->
                                                candidate.id != stop.id && bounds[candidate.id]?.let { dragY >= it.top && dragY <= it.bottom } == true
                                            }
                                            if (target != null && target.id != lastTargetId) reorder(stop.id, target.id)
                                            lastTargetId = target?.id
                                        },
                                    )
                                }, contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Menu, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
                            }
                        }
                        DropdownMenu(menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(text = { Text("Редактировать") }, onClick = { menuOpen = false; onEditStop(stop.id) })
                            if (middleStops.size > 1) DropdownMenuItem(text = { Text("Удалить", color = MaterialTheme.colorScheme.error) },
                                onClick = { menuOpen = false; pendingDelete = stop.id })
                        }
                    }
                    HorizontalDivider(Modifier.padding(start = 42.dp))
                }
            }
            TextButton(onClick = onAddStop, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(20.dp))
                Text("Добавить точку", Modifier.padding(start = 8.dp))
            }
            HorizontalDivider()
            RouteEndpointSection(RouteEndpointRole.Finish, state, onEndpointSelect, compact)
        }
    }
    if (pendingDelete != null) AlertDialog(onDismissRequest = { pendingDelete = null },
        title = { Text("Удалить точку?") }, text = { Text("Точка будет удалена из маршрута.") },
        confirmButton = { TextButton(onClick = { pendingDelete?.let(onRemoveStop); pendingDelete = null }) { Text("Удалить") } },
        dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Отмена") } })
}

@Composable
private fun RouteEndpointSection(role: RouteEndpointRole, state: RouteUiState, onSelect: (RouteEndpointRole, RouteEndpointKind) -> Unit, compact: Boolean = false) {
    val stop = if (role == RouteEndpointRole.Start) state.record.stops.first() else state.record.stops.last()
    val isHome = state.settings.homeAddress.isNotBlank() && stop.address.trim().equals(state.settings.homeAddress.trim(), ignoreCase = true)
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    fun copyAddress(address: String) { if (address.isNotBlank()) scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Адрес", address))) } }
    var copyMenuOpen by remember { mutableStateOf(false) }
    var endpointMenuOpen by remember { mutableStateOf(false) }
    if (compact) {
        Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(role.title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Box {
                    androidx.compose.material3.OutlinedButton(onClick = { endpointMenuOpen = true },
                        shape = RoundedCornerShape(10.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                        Text(if (isHome) "Дом" else "Склад")
                        Icon(androidx.compose.material.icons.Icons.Default.KeyboardArrowDown, null, Modifier.size(18.dp))
                    }
                    DropdownMenu(endpointMenuOpen, onDismissRequest = { endpointMenuOpen = false }) {
                        RouteEndpointKind.entries.forEach { kind ->
                            val available = state.settings.address(kind).isNotBlank()
                            DropdownMenuItem(text = { Text(if (available) kind.title else "${kind.title} · адрес не задан") },
                                enabled = available, onClick = { endpointMenuOpen = false; onSelect(role, kind) })
                        }
                    }
                }
            }
            Text(stop.address.ifBlank { "Адрес не указан" }, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxWidth().combinedClickable(onClick = { copyMenuOpen = true }, onLongClick = { copyAddress(stop.address) }))
            DropdownMenu(copyMenuOpen, onDismissRequest = { copyMenuOpen = false }) {
                DropdownMenuItem(text = { Text("Копировать адрес") }, enabled = stop.address.isNotBlank(),
                    onClick = { copyAddress(stop.address); copyMenuOpen = false })
            }
        }
        return
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box {
            Text(role.title, style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.combinedClickable(onClick = { copyMenuOpen = true }, onLongClick = { copyAddress(stop.address) }))
            DropdownMenu(copyMenuOpen, onDismissRequest = { copyMenuOpen = false }) {
                RouteEndpointKind.entries.forEach { kind ->
                    DropdownMenuItem(text = { Text("Копировать адрес: ${kind.title}") }, enabled = state.settings.address(kind).isNotBlank(),
                        onClick = { copyAddress(state.settings.address(kind)); copyMenuOpen = false })
                }
            }
        }
        AppSegmentedControl(
            titles = RouteEndpointKind.entries.map { it.title },
            selectedIndex = RouteEndpointKind.entries.indexOfFirst { isHome == (it == RouteEndpointKind.Home) },
            onSelect = { onSelect(role, RouteEndpointKind.entries[it]) },
            modifier = Modifier.align(Alignment.CenterHorizontally),
            isSegmentEnabled = { RouteEndpointKind.entries[it] != RouteEndpointKind.Home || state.settings.homeAddress.isNotBlank() },
            onLongClick = { copyAddress(state.settings.address(RouteEndpointKind.entries[it])) },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RouteStopEditor(stop: RouteStop, number: Int, date: String, onDismiss: () -> Unit, onSave: (String, String) -> Unit,
    workType: RouteWorkType = RouteWorkType.Pos, suggestions: List<String> = emptyList(),
    offices: List<String> = emptyList(), isLoadingOffices: Boolean = false, onRefreshOffices: () -> Unit = {}) {
    var address by rememberSaveable(date, workType, stop.id) { mutableStateOf(stop.address) }
    var requestNumber by rememberSaveable(date, workType, stop.id) { mutableStateOf(stop.requestNumber) }
    var officePickerOpen by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    ModalBottomSheet(containerColor = AppSurfaces.panel, tonalElevation = 0.dp, scrimColor = AppSurfaces.scrim, onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), sheetMaxWidth = 560.dp) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Text("Точка $number", Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            if (workType == RouteWorkType.Arm) {
                Text("Адрес", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                AppOutlinedButton(onClick = { officePickerOpen = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(address.ifBlank { "Выбрать отделение" }, modifier = Modifier.weight(1f))
                    if (isLoadingOffices) androidx.compose.material3.CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                }
            } else {
                AppTextField(address, { address = it }, "Адрес", modifier = Modifier.fillMaxWidth(), singleLine = true)
                val matches = suggestions.filter { it != address.trim() && (address.isBlank() || it.contains(address.trim(), true)) }
                    .sortedBy { !it.startsWith(address.trim(), true) }.take(6)
                if (matches.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    matches.forEach { suggestion -> AppOutlinedButton(onClick = { address = suggestion }) { Text(suggestion) } }
                }
            }
            AppTextField(requestNumber, { requestNumber = it }, "Номер заявки", modifier = Modifier.fillMaxWidth(), singleLine = true)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AppOutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Отмена") }
                AppButton(onClick = { onSave(address, requestNumber) }, enabled = workType != RouteWorkType.Arm || address.isNotBlank(), modifier = Modifier.weight(1f)) { Text("Сохранить") }
            }
        }
    }

    if (officePickerOpen) ModalBottomSheet(containerColor = AppSurfaces.panel, tonalElevation = 0.dp, scrimColor = AppSurfaces.scrim, onDismissRequest = { officePickerOpen = false },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), sheetMaxWidth = 680.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Отделения", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = onRefreshOffices, enabled = !isLoadingOffices) { Text("Обновить") }
            }
            AppTextField(query, { query = it }, "Поиск отделения", search = true, modifier = Modifier.fillMaxWidth())
            if (isLoadingOffices) androidx.compose.material3.CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            LazyColumn(Modifier.fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)) {
                val filtered = offices.filter { it.contains(query.trim(), true) }
                if (filtered.isEmpty() && !isLoadingOffices) item { Text(if (offices.isEmpty()) "Список отделений пока пуст. Обновите список." else "Ничего не найдено", Modifier.padding(vertical = 16.dp)) }
                items(filtered, key = { it }) { office ->
                    Text(office, Modifier.fillMaxWidth().clickable { address = office; officePickerOpen = false }.padding(vertical = 16.dp))
                    HorizontalDivider()
                }
            }
        }
    }
}
