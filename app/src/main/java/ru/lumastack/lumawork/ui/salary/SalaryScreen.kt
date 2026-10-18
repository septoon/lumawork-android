package ru.lumastack.lumawork.ui.salary

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import ru.lumastack.lumawork.data.salary.*
import ru.lumastack.lumawork.domain.model.AppSession
import ru.lumastack.lumawork.ui.adaptive.AppWidthClass
import ru.lumastack.lumawork.ui.theme.AppButton
import ru.lumastack.lumawork.ui.theme.AppFormDialog
import ru.lumastack.lumawork.ui.theme.AppOutlinedButton
import ru.lumastack.lumawork.ui.theme.AppRefreshContainer
import ru.lumastack.lumawork.ui.theme.AppTextField

@Composable
fun SalaryRoute(session: AppSession, startOffline: Boolean, repository: SalaryRepository, widthClass: AppWidthClass,
    modifier: Modifier = Modifier, onCancel: () -> Unit = {}) {
    key(session.user.id, session.token, startOffline) {
        SalaryAccessGate(session, startOffline, onCancel, modifier) {
            val context = LocalContext.current
            val preferences = remember(session.user.id) { SalaryDisplayPreferences(context, session.user.id) }
            val model: SalaryViewModel = viewModel(key = "salary-${session.user.id}-${session.token.hashCode()}-$startOffline",
                factory = SalaryViewModel.factory(repository, session, startOffline, preferences.read(), preferences::save))
            val state by model.state.collectAsStateWithLifecycle()
            SalaryScreen(session, startOffline, state, widthClass, model)
        }
    }
}

@Composable
private fun SalaryScreen(session: AppSession, offline: Boolean, state: SalaryUiState, width: AppWidthClass, model: SalaryViewModel) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.error, state.notice) {
        (state.error ?: state.notice)?.let { snackbar.showSnackbar(it); model.clearMessage() }
    }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Зарплата", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            TextButton(onClick = model::add, enabled = !offline && !state.saving) { Text("Добавить выплату") }
        }
        Box(Modifier.weight(1f)) {
            AppRefreshContainer(Modifier.fillMaxSize(), isRefreshing = state.refreshing, onRefresh = model::refresh,
                enabled = !offline && !state.saving && state.draft == null && state.selectedMonth == null) {
                if (state.months.isEmpty()) {
                    Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (state.loading || state.initializing) CircularProgressIndicator()
                        Text(if (state.loading || state.initializing) "Загружаем зарплату" else "Нет записей по зарплате", style = MaterialTheme.typography.titleMedium)
                        if (!state.loading && !state.initializing) Text("Добавьте первую выплату, чтобы увидеть месячные сводки.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else if (width == AppWidthClass.Expanded) {
                    Row(Modifier.widthIn(max = 1180.dp).fillMaxSize().padding(20.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                        Column(Modifier.weight(.45f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            SalarySummary(state, model::hidden, model::summaryPeriod)
                            SalaryAnalytics(state, model::toggleKind)
                        }
                        SalaryPeriods(state, model::year, model::month, Modifier.weight(.55f).fillMaxHeight())
                    }
                } else {
                    LazyVerticalGrid(columns = GridCells.Adaptive(145.dp), contentPadding = PaddingValues(20.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.widthIn(max = 760.dp).fillMaxSize()) {
                        item(key = "summary", span = { GridItemSpan(maxLineSpan) }) { SalarySummary(state, model::hidden, model::summaryPeriod) }
                        item(key = "analytics", span = { GridItemSpan(maxLineSpan) }) { SalaryAnalytics(state, model::toggleKind) }
                        item(key = "years", span = { GridItemSpan(maxLineSpan) }) { SalaryYears(state, model::year) }
                        items(state.visibleMonths, key = SalaryMonth::key) { month -> SalaryMonthButton(month, state.hidden) { model.month(month.key) } }
                    }
                }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
        }
    }
    state.months.firstOrNull { it.key == state.selectedMonth }?.let { month ->
        SalaryMonthDialog(session, offline, month, state.hidden, { model.month(null) }, model::edit, model::deleteAsk, model::message)
    }
    state.draft?.let { draft -> SalaryEditor(draft, state.editingId != null, state.saving, state.editorError, model::draft, model::close, model::save) }
    state.pendingDelete?.let { entry ->
        AlertDialog(onDismissRequest = model::deleteCancel, title = { Text("Удалить запись по зарплате?") },
            text = { Text("${entry.resolvedKind.title} · ${salaryDate(entry.date)}") },
            confirmButton = { TextButton(onClick = model::deleteConfirm, enabled = !state.saving) { Text("Удалить", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = model::deleteCancel, enabled = !state.saving) { Text("Отмена") } })
    }
}

@Composable
private fun SalarySummary(state: SalaryUiState, onHidden: () -> Unit, onPeriod: () -> Unit) {
    val projection = state.projection
    val latest = projection.latest
    val records = if (state.showAllTime) projection.paymentDates else latest?.entries.orEmpty().map { it.date }.sorted()
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.primaryContainer) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Перечислено за ${if (state.showAllTime) "${projection.months.size} мес." else salaryMonthTitle(latest?.key.orEmpty())}", style = MaterialTheme.typography.titleSmall)
            Text(salaryAmount(if (state.showAllTime) projection.total else latest?.total ?: 0.0, state.hidden),
                style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Text("${salaryDate(records.firstOrNull().orEmpty())} — ${salaryDate(records.lastOrNull().orEmpty())}", style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppOutlinedButton(onClick = onHidden) { Text(if (state.hidden) "Показать сумму" else "Скрыть сумму") }
                AppOutlinedButton(onClick = onPeriod) { Text(if (state.showAllTime) "За последний месяц" else "За всё время") }
            }
        }
    }
}

@Composable
private fun SalaryAnalytics(state: SalaryUiState, onToggle: (SalaryKind) -> Unit) {
    val projection = state.projection
    val selected = projection.filteredTotal(state.options.excludedKinds)
    val colors = SalaryKind.entries.associateWith { salaryKindColor(it) }
    val border = MaterialTheme.colorScheme.outlineVariant
    Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Аналитика", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("Структура выплат и средние значения за всё время", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SalaryMetric("В среднем выплат", String.format(Locale.forLanguageTag("ru"), "%.1f", projection.averagePaymentCount), Modifier.weight(1f))
                SalaryMetric("В среднем за месяц", salaryAmount(projection.average, state.hidden), Modifier.weight(1f))
            }
            Box(Modifier.fillMaxWidth().height(220.dp).semantics {
                contentDescription = "Распределение выплат по типам. Выбрано ${salaryAmount(selected, state.hidden)}"
            }, contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(210.dp)) {
                    val stroke = 30.dp.toPx()
                    val diameter = size.minDimension - stroke
                    val top = Offset(stroke / 2, stroke / 2)
                    val arcSize = Size(diameter, diameter)
                    drawArc(border, 0f, 360f, false, top, arcSize, style = Stroke(stroke))
                    var start = -90f
                    if (selected > 0) projection.slices.forEach { slice ->
                        if (slice.kind !in state.options.excludedKinds && slice.amount > 0) {
                            val sweep = (slice.amount / selected * 360).toFloat()
                            drawArc(colors.getValue(slice.kind), start + 1f, (sweep - 2f).coerceAtLeast(0f), false, top, arcSize, style = Stroke(stroke))
                            start += sweep
                        }
                    }
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.widthIn(max = 145.dp)) {
                    Text("Выбрано", style = MaterialTheme.typography.labelMedium)
                    Text(salaryAmount(selected, state.hidden), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                }
            }
            projection.slices.forEach { slice ->
                val included = slice.kind !in state.options.excludedKinds
                Row(Modifier.fillMaxWidth().toggleable(included, role = Role.Checkbox, onValueChange = { onToggle(slice.kind) }).padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = included, onCheckedChange = null, colors = CheckboxDefaults.colors(checkedColor = colors.getValue(slice.kind)))
                    Text(slice.kind.title, Modifier.weight(1f).padding(horizontal = 8.dp), style = MaterialTheme.typography.bodyMedium)
                    Column(horizontalAlignment = Alignment.End) {
                        Text(salaryAmount(slice.amount, state.hidden), style = MaterialTheme.typography.labelLarge)
                        Text(if (included && selected > 0) "${kotlin.math.round(slice.amount / selected * 100).toInt()}%" else "—",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun SalaryMetric(title: String, value: String, modifier: Modifier) {
    Column(modifier.background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SalaryYears(state: SalaryUiState, onYear: (String?) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Все периоды", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            state.years.forEach { year -> FilterChip(selected = state.selectedYear == year, onClick = { onYear(year) }, label = { Text(year) }) }
        }
    }
}

@Composable
private fun SalaryPeriods(state: SalaryUiState, onYear: (String?) -> Unit, onMonth: (String?) -> Unit, modifier: Modifier) {
    LazyVerticalGrid(columns = GridCells.Adaptive(145.dp), modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item(span = { GridItemSpan(maxLineSpan) }) { SalaryYears(state, onYear) }
        items(state.visibleMonths, key = SalaryMonth::key) { month -> SalaryMonthButton(month, state.hidden) { onMonth(month.key) } }
    }
}

@Composable
private fun SalaryMonthButton(month: SalaryMonth, hidden: Boolean, onClick: () -> Unit) {
    Card(onClick = onClick, shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(salaryMonthTitle(month.key), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            Text(if (hidden) "Сумма скрыта" else salaryAmount(month.total, false), fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
            Text(salaryPaymentCount(month.entries.size), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SalaryMonthDialog(session: AppSession, offline: Boolean, month: SalaryMonth, hidden: Boolean, close: () -> Unit,
    edit: (SalaryEntry) -> Unit, delete: (SalaryEntry) -> Unit, message: (String) -> Unit) {
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.widthIn(max = 760.dp).fillMaxWidth().fillMaxHeight(.94f), shape = RoundedCornerShape(24.dp)) {
            Column {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(salaryMonthTitle(month.key), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = close) { Text("Закрыть") }
                }
                LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    item(key = "total") {
                        SalaryInset {
                            Text("Итог за месяц", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Text(salaryPaymentCount(month.entries.size), style = MaterialTheme.typography.bodySmall)
                            val legacy = month.entries.filterNot { it.usesNetAmount }
                            if (legacy.isNotEmpty()) {
                                SalaryStat("Начислено", salaryAmount(legacy.sumOf { it.baseSalary + it.weekendPay }, hidden))
                                SalaryStat("Удержано", salaryAmount(legacy.sumOf { kotlin.math.floor((it.baseSalary + it.weekendPay) * .13) }, hidden))
                                HorizontalDivider()
                            }
                            SalaryStat("Перечислено", salaryAmount(month.total, hidden))
                        }
                    }
                    itemsIndexed(month.entries.sortedBy { it.date }, key = { index, entry -> entry.id ?: "legacy-$index-${entry.date}" }) { _, entry ->
                        SalaryInset {
                            Text(entry.resolvedKind.title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                            Text("Получено ${salaryDate(entry.date)}", style = MaterialTheme.typography.bodySmall)
                            if (!entry.usesNetAmount) {
                                SalaryStat("Оклад", salaryAmount(entry.baseSalary, hidden))
                                if (entry.weekendPay > 0) {
                                    SalaryStat("Оплата за выходной", salaryAmount(entry.weekendPay / 2, hidden))
                                    SalaryStat("Доплата за выходной", salaryAmount(entry.weekendPay / 2, hidden))
                                }
                                SalaryStat("Налог", salaryAmount(kotlin.math.floor((entry.baseSalary + entry.weekendPay) * .13), hidden))
                            }
                            if (entry.resolvedKind == SalaryKind.Vacation && !entry.vacationStartDate.isNullOrBlank() && !entry.vacationEndDate.isNullOrBlank())
                                SalaryStat("Период отпуска", "${salaryDate(entry.vacationStartDate)} — ${salaryDate(entry.vacationEndDate)}")
                            entry.comment?.takeIf { it.isNotBlank() }?.let { SalaryStat("Комментарий", it) }
                            SalaryStat("Выплата", salaryAmount(entry.payout, hidden))
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { edit(entry) }, enabled = !offline && !entry.id.isNullOrBlank()) { Text("Изменить") }
                                TextButton(onClick = { delete(entry) }, enabled = !offline && !entry.id.isNullOrBlank()) { Text("Удалить", color = MaterialTheme.colorScheme.error) }
                            }
                        }
                    }
                    item(key = "document") { SalaryDocumentsCard(session, offline, month.key, message) }
                }
            }
        }
    }
}

@Composable
private fun SalaryInset(content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

@Composable
private fun SalaryStat(title: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun SalaryEditor(draft: SalaryDraft, editing: Boolean, saving: Boolean, error: String?, change: (SalaryDraft) -> Unit, close: () -> Unit, save: () -> Unit) {
    AppFormDialog(if (editing) "Редактировать запись зарплаты" else "Добавить запись зарплаты", close, "Сохранить", save,
        isBusy = saving, dismissLabel = "Отмена", maxWidth = 680.dp, errorMessage = error) {
        item { SalaryDateField("Дата выплаты", draft.date) { value ->
            val oldDefault = defaultSalaryPeriod(draft.date)
            change(draft.copy(date = value, periodMonth = if (draft.periodMonth.isBlank() || draft.periodMonth == oldDefault) defaultSalaryPeriod(value) else draft.periodMonth))
        } }
        if (draft.date >= SalaryEntry.NET_START) item {
            var expanded by remember { mutableStateOf(false) }
            val base = runCatching { YearMonth.parse(draft.date.take(7)) }.getOrElse { YearMonth.now() }
            val months = ((-4L..4L).map { base.plusMonths(it).toString() } + draft.periodMonth).filter { it.isNotBlank() }.distinct().sorted()
            Box {
                AppOutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) { Text("За месяц: ${salaryMonthTitle(draft.periodMonth)}") }
                DropdownMenu(expanded, { expanded = false }) { months.forEach { month ->
                    DropdownMenuItem(text = { Text(salaryMonthTitle(month)) }, onClick = { expanded = false; change(draft.copy(periodMonth = month)) })
                } }
            }
        }
        item {
            var expanded by remember { mutableStateOf(false) }
            Box {
                AppOutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) { Text("Тип выплаты: ${draft.kind.title}") }
                DropdownMenu(expanded, { expanded = false }) { SalaryKind.entries.forEach { kind ->
                    DropdownMenuItem(text = { Text(kind.title) }, onClick = { expanded = false; change(draft.copy(kind = kind)) })
                } }
            }
        }
        if (draft.kind == SalaryKind.Vacation) {
            item { Text("Период отпуска", fontWeight = FontWeight.Bold) }
            item { SalaryDateField("Начало отпуска", draft.vacationStartDate) { change(draft.copy(vacationStartDate = it)) } }
            item { SalaryDateField("Окончание отпуска", draft.vacationEndDate) { change(draft.copy(vacationEndDate = it)) } }
        }
        if (draft.date >= SalaryEntry.NET_START) item { SalaryAmountField("Выплата на руки", draft.amount) { change(draft.copy(amount = it)) } }
        else {
            item { SalaryAmountField("Оклад", draft.baseSalary) { change(draft.copy(baseSalary = it)) } }
            item { SalaryAmountField("Оплата в выходной", draft.weekendPay) { change(draft.copy(weekendPay = it)) } }
        }
        if (draft.kind == SalaryKind.Other) item { AppTextField(draft.comment, { change(draft.copy(comment = it)) }, "Комментарий", modifier = Modifier.fillMaxWidth()) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SalaryDateField(title: String, value: String, change: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    AppOutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) { Text("$title: ${value.takeIf { it.isNotBlank() }?.let(::salaryDate) ?: "Выбрать дату"}") }
    if (open) {
        val initial = runCatching { LocalDate.parse(value) }.getOrElse { LocalDate.now() }
        val state = rememberDatePickerState(initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(onDismissRequest = { open = false }, confirmButton = {
            TextButton(onClick = { state.selectedDateMillis?.let { change(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString()) }; open = false },
                enabled = state.selectedDateMillis != null) { Text("Выбрать") }
        }, dismissButton = { TextButton(onClick = { open = false }) { Text("Отмена") } }) { DatePicker(state, title = { Text(title, Modifier.padding(16.dp)) }) }
    }
}

@Composable
private fun SalaryAmountField(title: String, value: String, change: (String) -> Unit) {
    AppTextField(value, change, title, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
}

private fun salaryKindColor(kind: SalaryKind): Color = when (kind) {
    SalaryKind.Advance -> Color(0xFF239C91); SalaryKind.Salary -> Color(0xFF3F68CC); SalaryKind.Weekend -> Color(0xFF249FE1)
    SalaryKind.Gsm -> Color(0xFF9260BB); SalaryKind.Vacation -> Color(0xFFE58F25); SalaryKind.Other -> Color(0xFFD5669A)
}
private fun salaryAmount(value: Double, hidden: Boolean): String = if (hidden) "••••••" else
    NumberFormat.getCurrencyInstance(Locale.forLanguageTag("ru-RU")).apply { maximumFractionDigits = 2 }.format(value)
private fun salaryDate(value: String): String = runCatching { LocalDate.parse(value).format(DateTimeFormatter.ofPattern("dd.MM.yyyy")) }.getOrDefault(value)
private fun salaryMonthTitle(value: String): String = runCatching {
    YearMonth.parse(value).format(DateTimeFormatter.ofPattern("LLLL yyyy", Locale.forLanguageTag("ru"))).replaceFirstChar { it.titlecase(Locale.forLanguageTag("ru")) }
}.getOrDefault(value)
private fun salaryPaymentCount(count: Int): String {
    val noun = when { count % 100 in 11..14 -> "выплат"; count % 10 == 1 -> "выплата"; count % 10 in 2..4 -> "выплаты"; else -> "выплат" }
    return "$count $noun"
}
