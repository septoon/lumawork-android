package ru.lumastack.lumawork.ui.home

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border

import android.content.ClipData
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
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
import ru.lumastack.lumawork.ui.theme.LocalAppDarkTheme

private val RouteSelectedGreen = Color(0xFF8CE6AE)

@Composable
internal fun RouteTimelineCard(
    state: RouteUiState,
    onEditStop: (String) -> Unit,
    onAddStop: () -> Unit,
    onRemoveStop: (String) -> Unit,
    onReorderStop: (String, String) -> Unit,
    onEndpointSelect: (RouteEndpointRole, RouteEndpointKind) -> Unit,
) {
    val bounds = remember(state.record.date, state.record.workType) { mutableStateMapOf<String, Rect>() }
    var draggedId by remember(state.record.date, state.record.workType) { mutableStateOf<String?>(null) }
    var pendingDelete by rememberSaveable(state.record.date, state.record.workType) { mutableStateOf<String?>(null) }
    val middleStops = state.record.stops.drop(1).dropLast(1)
    val currentStops by rememberUpdatedState(middleStops)
    val reorder by rememberUpdatedState(onReorderStop)
    val haptics = LocalHapticFeedback.current
    val accent = if (LocalAppDarkTheme.current) RouteSelectedGreen else MaterialTheme.colorScheme.primary

    AppCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(horizontal = 18.dp)) {
            RouteEndpointSection(RouteEndpointRole.Start, state, onEndpointSelect)
            HorizontalDivider(color = AppSurfaces.border)
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
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                                    Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) {
                                        Text("${index + 1}", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                    }
                                }
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(stop.address.ifBlank { "Адрес не указан" }, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                    Text(stop.requestNumber.ifBlank { "Номер заявки не указан" }, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                    if (index < middleStops.lastIndex) HorizontalDivider(Modifier.padding(start = 50.dp), color = AppSurfaces.border)
                }
            }
            OutlinedButton(onClick = onAddStop, modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp).heightIn(min = 44.dp),
                shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, AppSurfaces.border),
                colors = ButtonDefaults.outlinedButtonColors(containerColor = AppSurfaces.subpanel, contentColor = accent),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(22.dp))
                Text("Добавить точку", Modifier.padding(start = 8.dp))
            }
            HorizontalDivider(color = AppSurfaces.border)
            RouteEndpointSection(RouteEndpointRole.Finish, state, onEndpointSelect)
        }
    }
    if (pendingDelete != null) AlertDialog(onDismissRequest = { pendingDelete = null },
        title = { Text("Удалить точку?") }, text = { Text("Точка будет удалена из маршрута.") },
        confirmButton = { TextButton(onClick = { pendingDelete?.let(onRemoveStop); pendingDelete = null }) { Text("Удалить") } },
        dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Отмена") } })
}

@Composable
private fun RouteEndpointSection(role: RouteEndpointRole, state: RouteUiState, onSelect: (RouteEndpointRole, RouteEndpointKind) -> Unit) {
    val stop = if (role == RouteEndpointRole.Start) state.record.stops.first() else state.record.stops.last()
    val isHome = state.settings.homeAddress.isNotBlank() && stop.address.trim().equals(state.settings.homeAddress.trim(), ignoreCase = true)
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    fun copyAddress(address: String) { if (address.isNotBlank()) scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Адрес", address))) } }
    var copyMenuOpen by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    val selectedIndex = if (isHome) 1 else 0
    val selectedColor = if (LocalAppDarkTheme.current) RouteSelectedGreen else MaterialTheme.colorScheme.primary
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val controlWidth = (maxWidth * .58f).coerceAtMost(240.dp)
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                Text(role.title, style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.combinedClickable(onClick = { copyMenuOpen = true }, onLongClick = { copyAddress(stop.address) }))
                DropdownMenu(copyMenuOpen, onDismissRequest = { copyMenuOpen = false }) {
                    RouteEndpointKind.entries.forEach { kind ->
                        DropdownMenuItem(text = { Text("Копировать адрес: ${kind.title}") }, enabled = state.settings.address(kind).isNotBlank(),
                            onClick = { copyAddress(state.settings.address(kind)); copyMenuOpen = false })
                    }
                }
            }
            Box(Modifier.width(controlWidth).heightIn(min = 48.dp).selectableGroup()) {
                val warehouseWidth = (controlWidth - 4.dp) * .54f
                val homeWidth = controlWidth - 4.dp - warehouseWidth
                val indicatorOffset by animateDpAsState(if (isHome) warehouseWidth else 0.dp, tween(220), label = "route endpoint position")
                val indicatorWidth by animateDpAsState(if (isHome) homeWidth else warehouseWidth, tween(220), label = "route endpoint width")
                Box(Modifier.fillMaxWidth().height(44.dp).align(Alignment.Center)
                    .background(AppSurfaces.subpanel, CircleShape).border(1.dp, AppSurfaces.border, CircleShape))
                Box(Modifier.align(Alignment.CenterStart).offset(x = 2.dp + indicatorOffset).width(indicatorWidth).height(40.dp)
                    .background(selectedColor, CircleShape))
                Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp)) {
                    RouteEndpointKind.entries.forEachIndexed { index, kind ->
                        val enabled = kind != RouteEndpointKind.Home || state.settings.homeAddress.isNotBlank()
                        Box(Modifier.weight(if (kind == RouteEndpointKind.Warehouse) .54f else .46f).heightIn(min = 48.dp).clip(CircleShape)
                            .semantics { selected = selectedIndex == index; contentDescription = "${role.title}: ${kind.title}" }
                            .combinedClickable(enabled = enabled, role = Role.Tab,
                                onClick = {
                                    if (selectedIndex != index) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onSelect(role, kind)
                                },
                                onLongClick = { copyAddress(state.settings.address(kind)) }),
                            contentAlignment = Alignment.Center) {
                            Text(kind.title, style = MaterialTheme.typography.labelLarge, maxLines = 1,
                                color = (if (selectedIndex == index) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
                                    .copy(alpha = if (enabled) 1f else .38f))
                        }
                    }
                }
            }
        }
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
