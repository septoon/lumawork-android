package ru.lumastack.lumawork.ui.fuel

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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
import ru.lumastack.lumawork.ui.theme.LumaBackgroundBottomDark
import ru.lumastack.lumawork.ui.theme.LumaBackgroundBottomLight
import ru.lumastack.lumawork.ui.theme.LumaBackgroundTopDark
import ru.lumastack.lumawork.ui.theme.LumaBackgroundTopLight

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
        key = "fuel-${session.user.id}-$startOffline",
        factory = FuelViewModel.factory(repository, routeRepository, session, startOffline),
    )
    val state by fuelViewModel.state.collectAsStateWithLifecycle()
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
        modifier = modifier,
    )
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
    modifier: Modifier = Modifier,
) {
    val isDark = MaterialTheme.colorScheme.background == LumaBackgroundTopDark
    val background = remember(isDark) {
        Brush.verticalGradient(
            if (isDark) listOf(LumaBackgroundTopDark, LumaBackgroundBottomDark)
            else listOf(LumaBackgroundTopLight, LumaBackgroundBottomLight),
        )
    }
    Box(modifier.fillMaxSize().background(background), contentAlignment = Alignment.TopCenter) {
        if (widthClass == AppWidthClass.Compact) {
            LazyColumn(
                Modifier.widthIn(max = 680.dp).fillMaxSize(),
                contentPadding = PaddingValues(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                fuelHeader(state, onRefresh, onAddFuel, onAddAdjustment, onSelectYear)
                fuelMessages(state)
                fuelMonths(state, onToggleMonth, onEdit, onRequestDelete)
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
                    fuelHeader(state, onRefresh, onAddFuel, onAddAdjustment, onSelectYear)
                    fuelMessages(state)
                    item(key = "archive") { ArchivePanel(state.archivedCount) }
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
                        fuelMonths(state, onToggleMonth, onEdit, onRequestDelete, includeArchive = false)
                    }
                }
            }
        }
    }

    state.editorDraft?.let { draft ->
        FuelEditorDialog(
            draft = draft,
            isEditing = state.editingRecordId != null,
            isSaving = state.isSaving,
            errorMessage = state.editorErrorMessage,
            onChange = onUpdateDraft,
            onDismiss = onCloseEditor,
            onSave = onSave,
        )
    }
    if (state.pendingDelete != null) {
        AlertDialog(
            onDismissRequest = onCancelDelete,
            title = { Text("Удалить запись?") },
            text = { Text("Запись будет удалена с сервера и исчезнет в iOS.") },
            confirmButton = { TextButton(onClick = onConfirmDelete) { Text("Удалить") } },
            dismissButton = { TextButton(onClick = onCancelDelete) { Text("Отмена") } },
        )
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.fuelHeader(
    state: FuelUiState,
    onRefresh: () -> Unit,
    onAddFuel: () -> Unit,
    onAddAdjustment: (FuelAdjustmentKind) -> Unit,
    onSelectYear: (String) -> Unit,
) {
    item(key = "balance") {
        FuelBalancePanel(state, onRefresh)
    }
    item(key = "actions") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onAddFuel, modifier = Modifier.fillMaxWidth()) { Text("Добавить заправку") }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onAddAdjustment(FuelAdjustmentKind.CompensationPayment) }, modifier = Modifier.weight(1f)) { Text("Выплата") }
                OutlinedButton(onClick = { onAddAdjustment(FuelAdjustmentKind.DebtDeduction) }, modifier = Modifier.weight(1f)) { Text("Вычет долга") }
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
    includeArchive: Boolean = true,
) {
    if (state.visibleMonths.isEmpty() && !state.isLoading) {
        item(key = "empty") { FuelMessage("Данных пока нет", "Добавьте пробег, заправку или корректировку.") }
    }
    items(state.visibleMonths, key = FuelSummaryMonth::key) { month ->
        FuelMonthPanel(
            month = month,
            records = state.currentRecords.filter { it.resolvedMonthKey == month.key },
            expanded = state.expandedMonthKey == month.key,
            onToggle = { onToggleMonth(month.key) },
            onEdit = onEdit,
            onDelete = onDelete,
        )
    }
    if (includeArchive) item(key = "archive") { ArchivePanel(state.archivedCount) }
}

@Composable
private fun FuelBalancePanel(state: FuelUiState, onRefresh: () -> Unit) {
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
                TextButton(onClick = onRefresh, enabled = !state.isLoading) { Text("Обновить") }
            }
            if (!state.summary.hasData) {
                Text("Пока нет данных.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text(
                    totals.adjustedDiffLabel,
                    color = if (totals.adjustedFuelDiff < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(state.summary.explanation, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FuelMetric("Пробег", "${number(totals.totalMileage)} км", Modifier.weight(1f))
                    FuelMetric("Заправлено", "${number(totals.totalLiters)} л", Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FuelMetric("Компенсация", rubles(totals.totalCompensation), Modifier.weight(1f))
                    FuelMetric("К выплате", rubles(totals.netCompensation), Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun FuelMonthPanel(
    month: FuelSummaryMonth,
    records: List<FuelRecord>,
    expanded: Boolean,
    onToggle: () -> Unit,
    onEdit: (FuelRecord) -> Unit,
    onDelete: (FuelRecord) -> Unit,
) {
    Surface(
        Modifier.fillMaxWidth().clickable(onClick = onToggle),
        shape = RoundedCornerShape(19.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
    ) {
        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text(month.label, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Text("${number(month.totalMileage)} км · ${number(month.totalLiters)} л · ${rubles(month.fuelCost)}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(month.diffLabel, color = if (month.fuelDiff < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    Text(month.compensationStatusLabel, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                }
            }
            if (expanded) {
                HorizontalDivider()
                FuelLine("Норма топлива", "${number(month.fuelNorm)} л")
                FuelLine("Компенсация", rubles(month.compensation))
                FuelLine("Выплачено", rubles(month.paidCompensation))
                FuelLine("Вычет долга", "${rubles(month.effectiveDebtDeductionAmount)} · ${number(month.effectiveDebtDeductionLiters)} л")
                FuelLine("Ожидаемая выплата", rubles(month.projectedPayout))
                records.sortedByDescending(FuelRecord::date).forEach { record ->
                    HorizontalDivider()
                    FuelRecordRow(record, onEdit = { onEdit(record) }, onDelete = { onDelete(record) })
                }
            }
        }
    }
}

@Composable
private fun FuelRecordRow(record: FuelRecord, onEdit: () -> Unit, onDelete: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(if (record.recordType == FuelRecordType.Fuel) record.date else adjustmentTitle(record), fontWeight = FontWeight.SemiBold)
            Text(recordDescription(record), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        TextButton(onClick = onEdit) { Text("Изменить") }
        if (record.id != null) TextButton(onClick = onDelete) { Text("Удалить") }
    }
}

@Composable
private fun FuelEditorDialog(
    draft: FuelDraft,
    isEditing: Boolean,
    isSaving: Boolean,
    errorMessage: String?,
    onChange: (FuelDraft) -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            Modifier.widthIn(max = 680.dp).fillMaxWidth().fillMaxHeight(0.92f).padding(16.dp),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (isEditing) "Редактирование" else if (draft.recordType == FuelRecordType.Fuel) "Новая запись" else "Новая корректировка",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    TextButton(onClick = onDismiss, enabled = !isSaving) { Text("Закрыть") }
                }
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (draft.recordType == FuelRecordType.Fuel) {
                                FuelField("Дата, ГГГГ-ММ-ДД", draft.date, { onChange(draft.copy(date = it)) })
                                FuelField("Пробег, км", draft.mileage, { onChange(draft.copy(mileage = it)) }, KeyboardType.Decimal)
                                FuelField("Литры", draft.liters, { onChange(draft.copy(liters = it)) }, KeyboardType.Decimal)
                                FuelField("Сумма заправки", draft.fuelCost, { onChange(draft.copy(fuelCost = it)) }, KeyboardType.Decimal)
                                FuelField("Комментарий", draft.comment, { onChange(draft.copy(comment = it)) })
                            } else {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    AdjustmentButton("Выплата", draft.adjustmentKind == FuelAdjustmentKind.CompensationPayment) { onChange(draft.copy(adjustmentKind = FuelAdjustmentKind.CompensationPayment)) }
                                    AdjustmentButton("Вычет долга", draft.adjustmentKind == FuelAdjustmentKind.DebtDeduction) { onChange(draft.copy(adjustmentKind = FuelAdjustmentKind.DebtDeduction)) }
                                }
                                FuelField("Месяц, ГГГГ-ММ", draft.monthKey, { onChange(draft.copy(monthKey = it)) })
                                FuelField("Сумма", draft.amount, { onChange(draft.copy(amount = it)) }, KeyboardType.Decimal)
                                FuelField("Литры", draft.liters, { onChange(draft.copy(liters = it)) }, KeyboardType.Decimal)
                                if (draft.adjustmentKind == FuelAdjustmentKind.DebtDeduction) {
                                    FuelField("Остаток долга", draft.carryoverDebtRub, { onChange(draft.copy(carryoverDebtRub = it)) }, KeyboardType.Decimal)
                                }
                                FuelField("Комментарий", draft.comment, { onChange(draft.copy(comment = it)) })
                            }
                        }
                    }
                }
                errorMessage?.let { FuelMessage("Проверьте данные", it, true) }
                Button(onClick = onSave, enabled = !isSaving, modifier = Modifier.fillMaxWidth()) {
                    if (isSaving) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Сохранить")
                }
            }
        }
    }
}

@Composable
private fun RowScope.AdjustmentButton(title: String, selected: Boolean, onClick: () -> Unit) {
    if (selected) Button(onClick = onClick, modifier = Modifier.weight(1f)) { Text(title) }
    else OutlinedButton(onClick = onClick, modifier = Modifier.weight(1f)) { Text(title) }
}

@Composable
private fun FuelField(label: String, value: String, onChange: (String) -> Unit, keyboard: KeyboardType = KeyboardType.Text) {
    AppTextField(
        value = value,
        onValueChange = onChange,
        label = label,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        singleLine = true,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun FuelMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier.background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f), RoundedCornerShape(14.dp)).padding(11.dp)) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
        Text(value, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun FuelLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ArchivePanel(count: Int) {
    Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f), shape = RoundedCornerShape(18.dp)) {
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
private fun number(value: Double): String = NUMBER_FORMAT.format(value)
private fun rubles(value: Double): String = CURRENCY_FORMAT.format(value)
private val RU_LOCALE = Locale.forLanguageTag("ru-RU")
private val NUMBER_FORMAT = NumberFormat.getNumberInstance(RU_LOCALE).apply { maximumFractionDigits = 2 }
private val CURRENCY_FORMAT = NumberFormat.getCurrencyInstance(RU_LOCALE).apply { maximumFractionDigits = 0 }
