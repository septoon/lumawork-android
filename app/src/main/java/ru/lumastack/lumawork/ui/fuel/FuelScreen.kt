package ru.lumastack.lumawork.ui.fuel

import ru.lumastack.lumawork.ui.theme.AppRefreshContainer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import ru.lumastack.lumawork.ui.theme.AppButton as Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import ru.lumastack.lumawork.ui.theme.AppOutlinedButton as OutlinedButton
import ru.lumastack.lumawork.ui.theme.AppTextField
import ru.lumastack.lumawork.ui.theme.AppFormDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import kotlinx.coroutines.delay
import ru.lumastack.lumawork.BuildConfig
import ru.lumastack.lumawork.domain.fuel.FuelArchivePolicy
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.text.NumberFormat
import java.util.Locale
import ru.lumastack.lumawork.data.fuel.FuelRepository
import ru.lumastack.lumawork.data.route.RouteRepository
import ru.lumastack.lumawork.domain.fuel.FuelAdjustmentKind
import ru.lumastack.lumawork.domain.fuel.FuelDraft
import ru.lumastack.lumawork.domain.fuel.FuelRecord
import ru.lumastack.lumawork.domain.fuel.FuelRecordType
import ru.lumastack.lumawork.domain.fuel.FuelSummaryMonth
import ru.lumastack.lumawork.domain.model.AppSession
import ru.lumastack.lumawork.ui.adaptive.AppWidthClass

@Composable
fun FuelRoute(
    session: AppSession,
    startOffline: Boolean,
    repository: FuelRepository,
    routeRepository: RouteRepository,
    widthClass: AppWidthClass,
    modifier: Modifier = Modifier,
) {
    val fuelViewModel: FuelViewModel = viewModel(
        key = "fuel-${session.user.id}-${session.token.hashCode()}-$startOffline",
        factory = FuelViewModel.factory(repository, routeRepository, session, startOffline),
    )
    val state by fuelViewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(fuelViewModel) { fuelViewModel.reloadCached() }
    LaunchedEffect(state.notice, state.errorMessage) {
        if (state.notice != null || state.errorMessage != null) { delay(5000); fuelViewModel.dismissMessages() }
    }
    var archiveOpen by rememberSaveable(session.user.id) { mutableStateOf(false) }
    var analyticsOpen by rememberSaveable(session.user.id) { mutableStateOf(false) }
    var importOpen by rememberSaveable(session.user.id) { mutableStateOf(false) }
    val archiveAvailable = FuelArchivePolicy.isAvailable(session.user.email, BuildConfig.FUEL_ARCHIVE_OWNER_EMAIL)
    FuelScreen(
        state = state,
        widthClass = widthClass,
        onRefresh = fuelViewModel::refresh,
        onSelectYear = fuelViewModel::selectYear,
        onToggleMonth = fuelViewModel::toggleMonth,
        onAddFuel = fuelViewModel::addFuel,
        onAddAdjustment = fuelViewModel::addAdjustment,
        onEdit = fuelViewModel::edit,
        onUpdateDraft = fuelViewModel::updateDraft,
        onCloseEditor = fuelViewModel::closeEditor,
        onSave = fuelViewModel::save,
        onRequestDelete = fuelViewModel::requestDelete,
        onCancelDelete = fuelViewModel::cancelDelete,
        onConfirmDelete = fuelViewModel::confirmDelete,
        archiveAvailable = archiveAvailable,
        onOpenArchive = { archiveOpen = true },
        onOpenAnalytics = { analyticsOpen = true },
        onOpenImport = { importOpen = true },
        modifier = modifier,
    )
    if (analyticsOpen) Dialog(onDismissRequest = { analyticsOpen = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        FuelAnalyticsScreen(state.snapshot.records, onDismiss = { analyticsOpen = false })
    }
    if (archiveOpen && archiveAvailable) Dialog(onDismissRequest = { if (!state.isSaving) archiveOpen = false }, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = !state.isSaving)) {
        FuelArchiveScreen(state, onDismiss = { archiveOpen = false }, onEdit = fuelViewModel::edit, onDelete = fuelViewModel::requestDelete)
    }
    if (importOpen) Dialog(onDismissRequest = {}, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = false, dismissOnClickOutside = false)) {
        FuelImportScreen(session, startOffline, onDismiss = { importOpen = false }, onCommitted = { result -> importOpen = false; fuelViewModel.importsCommitted(result.notice) })
    }
    state.editorDraft?.let { draft ->
        FuelEditorDialog(draft, state.editingRecord != null, state.isSaving, state.editorErrorMessage,
            state.snapshot.fuelTypes, startOffline, fuelViewModel::updateDraft, fuelViewModel::closeEditor, fuelViewModel::save)
    }
    state.pendingDelete?.let {
        AlertDialog(onDismissRequest = fuelViewModel::cancelDelete, title = { Text("Удалить запись?") },
            confirmButton = { TextButton(onClick = fuelViewModel::confirmDelete, enabled = !state.isSaving && !startOffline) { Text("Удалить") } },
            dismissButton = { TextButton(onClick = fuelViewModel::cancelDelete, enabled = !state.isSaving) { Text("Отмена") } })
    }
}

@Composable
private fun FuelScreen(
    state: FuelUiState,
    widthClass: AppWidthClass,
    onRefresh: () -> Unit,
    onSelectYear: (String) -> Unit,
    onToggleMonth: (String) -> Unit,
    onAddFuel: () -> Unit,
    onAddAdjustment: (FuelAdjustmentKind) -> Unit,
    onEdit: (FuelRecord) -> Unit,
    onUpdateDraft: (FuelDraft) -> Unit,
    onCloseEditor: () -> Unit,
    onSave: () -> Unit,
    onRequestDelete: (FuelRecord) -> Unit,
    onCancelDelete: () -> Unit,
    onConfirmDelete: () -> Unit,
    archiveAvailable: Boolean,
    onOpenArchive: () -> Unit,
    onOpenAnalytics: () -> Unit,
    onOpenImport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val background = ru.lumastack.lumawork.ui.theme.appScreenBackground()
    AppRefreshContainer(modifier.fillMaxSize().background(background), isRefreshing = state.isLoading, onRefresh = onRefresh,
        enabled = !state.isSaving && !state.isOffline, contentAlignment = Alignment.TopCenter) {
        if (widthClass != AppWidthClass.Expanded) {
            LazyColumn(
                Modifier.widthIn(max = 680.dp).fillMaxSize(),
                contentPadding = PaddingValues(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                fuelHeader(state, onAddFuel, onAddAdjustment, onSelectYear, onOpenImport, onOpenAnalytics)
                fuelMessages(state)
                fuelMonths(state, onToggleMonth, onEdit, onRequestDelete)
                if (archiveAvailable) item(key = "archive") { ArchivePanel(state.archivedCount, onOpenArchive) }
            }
        } else {
            Row(
                Modifier.widthIn(max = 1180.dp).fillMaxSize().padding(20.dp),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                LazyColumn(
                    Modifier.weight(0.42f).fillMaxHeight().widthIn(max = 470.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    fuelHeader(state, onAddFuel, onAddAdjustment, onSelectYear, onOpenImport, onOpenAnalytics)
                    fuelMessages(state)
                    if (archiveAvailable) item(key = "archive") { ArchivePanel(state.archivedCount, onOpenArchive) }
                }
                Surface(
                    Modifier.weight(0.58f).fillMaxHeight(),
                    shape = RoundedCornerShape(26.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                    tonalElevation = 2.dp,
                ) {
                    LazyColumn(
                        contentPadding = PaddingValues(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        item(key = "months-title") {
                            Column {
                                Text("По месяцам", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                                Text("Расчёт и записи", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        fuelMonths(state, onToggleMonth, onEdit, onRequestDelete)
                    }
                }
            }
        }
    }

}

private fun androidx.compose.foundation.lazy.LazyListScope.fuelHeader(
    state: FuelUiState,
    onAddFuel: () -> Unit,
    onAddAdjustment: (FuelAdjustmentKind) -> Unit,
    onSelectYear: (String) -> Unit,
    onOpenImport: () -> Unit,
    onOpenAnalytics: () -> Unit,
) {
    item(key = "balance") {
        FuelBalancePanel(state)
    }
    item(key = "actions") {
        var menu by remember { mutableStateOf(false) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onAddFuel, enabled = !state.isSaving && !state.isOffline, modifier = Modifier.weight(1f)) { Text("Добавить заправку") }
            Box {
                IconButton(onClick = { menu = true }, enabled = !state.isSaving && !state.isOffline) { Icon(Icons.Default.MoreVert, "Другие действия с топливом") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text("Добавить выплату") }, onClick = { menu = false; onAddAdjustment(FuelAdjustmentKind.CompensationPayment) })
                    DropdownMenuItem(text = { Text("Добавить вычет долга") }, onClick = { menu = false; onAddAdjustment(FuelAdjustmentKind.DebtDeduction) })
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text("Импортировать отчёты") }, onClick = { menu = false; onOpenImport() })
                }
            }
        }
    }
    if (state.snapshot.records.isNotEmpty()) item(key = "analytics") {
        Surface(onClick = onOpenAnalytics, shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.primaryContainer) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Аналитика  ›", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("Цена литра, расходы и динамика по месяцам", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    if (state.years.isNotEmpty()) {
        item(key = "years") {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                state.years.forEach { year ->
                    if (state.selectedYear == year) Button(onClick = { onSelectYear(year) }) { Text(year) }
                    else OutlinedButton(onClick = { onSelectYear(year) }) { Text(year) }
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.fuelMessages(state: FuelUiState) {
    state.errorMessage?.let { item(key = "error") { FuelMessage("Не удалось обновить данные", it, true) } }
    state.notice?.let { item(key = "notice") { FuelMessage("Готово", it) } }
    if (state.isLoading && state.snapshot.records.isEmpty()) {
        item(key = "loading") {
            Row(Modifier.fillMaxWidth().padding(28.dp), horizontalArrangement = Arrangement.Center) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.fuelMonths(
    state: FuelUiState,
    onToggleMonth: (String) -> Unit,
    onEdit: (FuelRecord) -> Unit,
    onDelete: (FuelRecord) -> Unit,
) {
    if (state.visibleMonths.isEmpty() && !state.isLoading) {
        item(key = "empty") { FuelMessage("Данных пока нет", "Добавьте пробег, заправку или корректировку.") }
    }
    items(state.visibleMonths, key = FuelSummaryMonth::key) { month ->
        FuelMonthPanel(
            month = month,
            records = state.currentRecords.filter { it.recordType == FuelRecordType.Fuel && it.resolvedMonthKey == month.key },
            enabled = !state.isSaving && !state.isOffline,
            expanded = state.expandedMonthKey == month.key,
            onToggle = { onToggleMonth(month.key) },
            onEdit = onEdit,
            onDelete = onDelete,
        )
    }
}

@Composable
private fun FuelBalancePanel(state: FuelUiState) {
    val totals = state.summary.totals
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f)),
        shape = RoundedCornerShape(24.dp),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("Топливный баланс", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("Расход, компенсации и задолженность", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (!state.summary.hasData) {
                Text("Пока нет данных.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text(
                    when {
                        totals.adjustedFuelDiff < 0 -> "Перерасход ${number(-totals.adjustedFuelDiff)} л"
                        totals.adjustedFuelDiff > 0 -> "Остаток ${number(totals.adjustedFuelDiff)} л"
                        else -> "Баланс соблюдён"
                    },
                    color = if (totals.adjustedFuelDiff < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(state.summary.explanation, color = MaterialTheme.colorScheme.onSurfaceVariant)

            }
        }
    }
}

@Composable
private fun FuelMonthPanel(
    month: FuelSummaryMonth,
    records: List<FuelRecord>,
    enabled: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
    onEdit: (FuelRecord) -> Unit,
    onDelete: (FuelRecord) -> Unit,
) {
    var inRubles by rememberSaveable(month.key) { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    Surface(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(19.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
    ) {
        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth().clickable { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onToggle() }, horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text(month.label, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Text("${number(month.totalMileage)} км · ${number(month.totalLiters)} л · ${rubles(month.fuelCost)}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(month.diffLabel, color = if (month.fuelDiff < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    Text(month.compensationStatusLabel, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                }
                Icon(if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (expanded) "Свернуть месяц" else "Раскрыть месяц", modifier = Modifier.padding(start = 8.dp))
            }
            if (expanded) {
                HorizontalDivider()
                FuelLine("Пройдено км", "${number(month.totalMileage)} км")
                FuelLine("Норма топлива", "${number(month.fuelNorm)} л")
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Заправлено", Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); inRubles = !inRubles }) {
                        Text(if (inRubles) rubles(month.fuelCost) else "${number(month.totalLiters)} л")
                    }
                }
                FuelLine("Разница", month.diffLabel)
                FuelLine("Компенсация", rubles(month.compensation))
                if (month.paidCompensation > 0) FuelLine("Выплачено", rubles(month.paidCompensation))
                if (month.effectiveDebtDeductionAmount > 0) FuelLine("Списано", rubles(month.effectiveDebtDeductionAmount))
                if (month.incomingCarryoverDebtRub > 0) FuelLine("Долг из прошлого месяца", rubles(month.incomingCarryoverDebtRub))
                if (month.monthCarryoverDebtRub > 0) FuelLine("Перенос на след. месяц", rubles(month.monthCarryoverDebtRub))
                if (records.isNotEmpty()) Text("Записи топлива", style = MaterialTheme.typography.labelLarge)
                records.sortedByDescending(FuelRecord::date).forEach { record ->
                    HorizontalDivider()
                    FuelRecordRow(record, enabled = enabled, onEdit = { onEdit(record) }, onDelete = { onDelete(record) })
                }
                if (month.adjustments.isNotEmpty()) Text("Корректировки месяца", style = MaterialTheme.typography.labelLarge)
                month.adjustments.forEach { record ->
                    HorizontalDivider()
                    FuelRecordRow(record, enabled = enabled, onEdit = { onEdit(record) }, onDelete = { onDelete(record) })
                }
            }
        }
    }
}

@Composable
internal fun FuelRecordRow(record: FuelRecord, enabled: Boolean = true, onEdit: () -> Unit, onDelete: () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Column {
            Text(if (record.recordType == FuelRecordType.Fuel) record.date else adjustmentTitle(record), fontWeight = FontWeight.SemiBold)
            record.fuelType?.takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) }
            if (record.source.equals("XLSX_IMPORT", ignoreCase = true)) Text("XLSX", style = MaterialTheme.typography.labelSmall)
            Text(recordDescription(record), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onEdit, enabled = enabled && !record.id.isNullOrBlank()) { Text("Изменить") }
            if (!record.id.isNullOrBlank()) TextButton(onClick = onDelete, enabled = enabled) { Text("Удалить") }
        }
    }
}

@Composable
private fun FuelLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ArchivePanel(count: Int, onClick: () -> Unit) {
    Surface(onClick = onClick, color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f), shape = RoundedCornerShape(18.dp)) {
        Row(Modifier.fillMaxWidth().padding(15.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Архив до 13 мая 2026", fontWeight = FontWeight.SemiBold)
            Text("$count записей", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun FuelMessage(title: String, message: String, error: Boolean = false) {
    Surface(
        color = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(15.dp)) {
            Text(title, fontWeight = FontWeight.Bold)
            Text(message, color = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun adjustmentTitle(record: FuelRecord): String = when (record.adjustmentKind) {
    FuelAdjustmentKind.CompensationPayment -> "Выплата компенсации"
    FuelAdjustmentKind.DebtDeduction -> "Вычет долга"
    null -> "Корректировка"
}
private fun recordDescription(record: FuelRecord): String = if (record.recordType == FuelRecordType.Fuel) {
    listOfNotNull(record.mileage?.let { "${number(it)} км" }, record.liters?.let { "${number(it)} л" }, record.fuelCost?.let(::rubles)).joinToString(" · ")
} else listOfNotNull(record.amount?.let(::rubles), record.liters?.let { "${number(it)} л" }, record.comment).joinToString(" · ")
internal fun number(value: Double): String = NUMBER_FORMAT.format(value)
internal fun rubles(value: Double): String = CURRENCY_FORMAT.format(value)
private val RU_LOCALE = Locale.forLanguageTag("ru-RU")
private val NUMBER_FORMAT = NumberFormat.getNumberInstance(RU_LOCALE).apply { maximumFractionDigits = 2 }
private val CURRENCY_FORMAT = NumberFormat.getCurrencyInstance(RU_LOCALE).apply { maximumFractionDigits = 2 }
