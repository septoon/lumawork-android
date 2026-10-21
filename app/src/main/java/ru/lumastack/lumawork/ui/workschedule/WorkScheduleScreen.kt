package ru.lumastack.lumawork.ui.workschedule

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.ZoneId
import java.util.Locale
import ru.lumastack.lumawork.data.workschedule.WorkScheduleRepository
import ru.lumastack.lumawork.domain.workschedule.*
import ru.lumastack.lumawork.ui.theme.AppRefreshContainer
import ru.lumastack.lumawork.ui.theme.appScreenBackground
import ru.lumastack.lumawork.ui.theme.AppOutlinedButton

@Composable
fun WorkScheduleRoute(
    repository: WorkScheduleRepository,
    ownerUserId: String,
    simpleOneUserId: String,
    isAuthorized: Boolean,
    startOffline: Boolean,
    profileCity: String?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    authorizationRevision: Long? = null,
) {
    val model: WorkScheduleViewModel = viewModel(
        key = "schedule|$ownerUserId|$simpleOneUserId|$startOffline",
        factory = WorkScheduleViewModel.factory(repository, ownerUserId, simpleOneUserId, startOffline, profileCity),
    )
    val state by model.state.collectAsStateWithLifecycle()
    LaunchedEffect(isAuthorized, authorizationRevision) { model.activate(isAuthorized) }
    BackHandler(onBack = onBack)
    WorkScheduleScreen(state, model::refresh, model::selectCity, model::selectPeriod, modifier)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WorkScheduleScreen(
    state: WorkScheduleUiState,
    onRefresh: () -> Unit,
    onCity: (String) -> Unit,
    onPeriod: (Int, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val horizontal = rememberScrollState()
    val density = LocalDensity.current
    val today = LocalDate.now()
    val schedule = state.schedule
    val dayWidth = 48.dp
    LaunchedEffect(schedule?.journal?.sysId) {
        val journal = schedule?.journal ?: return@LaunchedEffect
        val offset = WorkScheduleViewport.initialVisibleDay(journal, today) - 1
        horizontal.scrollTo(with(density) { (dayWidth * offset).roundToPx() })
    }
    BoxWithConstraints(modifier.fillMaxSize().background(appScreenBackground()), contentAlignment = Alignment.TopCenter) {
        val employeeWidth = if (maxWidth < 400.dp) 132.dp else 180.dp
        val rowHeight = 76.dp * density.fontScale.coerceAtLeast(1f)
        AppRefreshContainer(
            modifier = Modifier.widthIn(max = 1280.dp).fillMaxSize(),
            isRefreshing = state.isLoading,
            onRefresh = onRefresh,
            enabled = state.isAuthorized && !state.isOffline,
        ) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
                item(key = "toolbar") {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = onRefresh, enabled = state.isAuthorized && !state.isOffline && !state.isLoading) { Text("Обновить") }
                    }
                }
                item(key = "filters") {
                    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CityMenu(state, onCity, Modifier.weight(1f))
                            PeriodMenu(state, onPeriod, Modifier.weight(1f))
                        }
                        if (state.isOffline) Text("Офлайн · сохранённые данные", style = MaterialTheme.typography.bodySmall)
                        if (!state.isAuthorized) Text("SimpleOne не подключён. Войдите в SimpleOne в разделе «Заявки».", color = MaterialTheme.colorScheme.error)
                        state.errorMessage?.let { error ->
                            Text(error, color = MaterialTheme.colorScheme.error)
                            if (state.isAuthorized && !state.isOffline) TextButton(onClick = onRefresh) { Text("Повторить") }
                        }
                        if (state.isLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
                if (schedule != null && schedule.employees.isNotEmpty()) {
                    item(key = "table-header") {
                        Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant)) {
                            ScheduleCell("Сотрудники", employeeWidth, 58.dp)
                            Row(Modifier.weight(1f).horizontalScroll(horizontal)) {
                                (1..schedule.daysInMonth).forEach { day ->
                                    val date = scheduleDate(schedule.journal, day)
                                    val weekend = date?.dayOfWeek in listOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
                                    ScheduleCell("${date?.dayOfWeek?.getDisplayName(TextStyle.SHORT, RUSSIAN).orEmpty()}\n$day", dayWidth, 58.dp,
                                        dayBackground(schedule.journal, day, today, if (weekend) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface))
                                }
                                ScheduleCell("Часы", 72.dp, 58.dp)
                            }
                        }
                    }
                    itemsIndexed(schedule.employees, key = { index, employee -> "employee|${employee.id}|$index" }) { _, employee ->
                        Row(Modifier.fillMaxWidth()) {
                            Column(Modifier.width(employeeWidth).height(rowHeight).background(MaterialTheme.colorScheme.surface).padding(10.dp), verticalArrangement = Arrangement.Center) {
                                Text(employee.name, maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                                if (employee.login.isNotBlank()) Text(employee.login, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Row(Modifier.weight(1f).horizontalScroll(horizontal)) {
                                (1..schedule.daysInMonth).forEach { day ->
                                    val value = employee.value(day)
                                    val base = when { value == null -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                        !value.isActive -> Color(0xFF656A6E)
                                        value.hours >= 24 -> Color(0xFF32804A)
                                        else -> Color(0xFFF1D56F) }
                                    val foreground = when { value == null -> MaterialTheme.colorScheme.onSurface; !value.isActive || value.hours >= 24 -> Color.White; else -> Color(0xFF252116) }
                                    ScheduleCell(value?.hours?.let(::hours) ?: "–", dayWidth, rowHeight,
                                        dayBackground(schedule.journal, day, today, base), foreground,
                                        "${employee.name}, $day ${monthName(schedule.journal.month)} ${schedule.journal.year}: ${value?.hours?.let { "${hours(it)} часов" } ?: "нет в графике"}")
                                }
                                ScheduleCell(hours(employee.totalHours), 72.dp, rowHeight)
                            }
                        }
                        HorizontalDivider()
                    }
                    item(key = "table-footer") {
                        Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant)) {
                            ScheduleCell("Работают", employeeWidth, 54.dp)
                            Row(Modifier.weight(1f).horizontalScroll(horizontal)) {
                                schedule.dailyTotals.forEach { total -> ScheduleCell(total.count.toString(), dayWidth, 54.dp, dayBackground(schedule.journal, total.day, today, MaterialTheme.colorScheme.surfaceVariant)) }
                                ScheduleCell("", 72.dp, 54.dp)
                            }
                        }
                    }
                    item(key = "legend") {
                        Column(Modifier.fillMaxWidth().padding(vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Обозначения", style = MaterialTheme.typography.titleMedium)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                schedule.employees.flatMap { it.dayValues }.map { it.hours }.distinct().sorted().forEach { value ->
                                    Surface(color = if (value >= 24) Color(0xFF32804A) else Color(0xFFF1D56F), contentColor = if (value >= 24) Color.White else Color.Black, shape = RoundedCornerShape(8.dp)) {
                                        Text("${hours(value)} ч", Modifier.padding(8.dp), style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                                Text("– Нет в графике", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    schedule.auditInfo?.takeIf { it.hasContent }?.let { info ->
                        item(key = "audit") {
                            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
                                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("Информация о графике", style = MaterialTheme.typography.titleMedium)
                                    AuditRow("Создано", info.createdBy, info.createdAtEpochMillis)
                                    AuditRow("Изменено", info.updatedBy, info.updatedAtEpochMillis)
                                }
                            }
                        }
                    }
                } else if (!state.isLoading) {
                    item(key = "empty") {
                        Text(if (schedule != null) "Сотрудников в графике нет. SimpleOne вернул пустой график за выбранный период."
                            else "Для выбранного города и месяца график пока не опубликован.", Modifier.fillMaxWidth().padding(vertical = 24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun CityMenu(state: WorkScheduleUiState, onCity: (String) -> Unit, modifier: Modifier) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        AppOutlinedButton(onClick = { expanded = true }, enabled = state.snapshot.journals.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
            Text(state.selection.cityName.ifBlank { "Город" }, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        DropdownMenu(expanded, { expanded = false }) {
            WorkScheduleSelection.cities(state.snapshot.journals).forEach { city ->
                DropdownMenuItem(text = { Text(city) }, onClick = { expanded = false; onCity(city) })
            }
        }
    }
}

@Composable
private fun PeriodMenu(state: WorkScheduleUiState, onPeriod: (Int, Int) -> Unit, modifier: Modifier) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        AppOutlinedButton(onClick = { expanded = true }, enabled = state.snapshot.journals.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
            Text("${monthName(state.selection.month)} ${state.selection.year.takeIf { it > 0 } ?: ""}", maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        DropdownMenu(expanded, { expanded = false }) {
            WorkScheduleSelection.years(state.snapshot.journals, state.selection.cityName).forEach { year ->
                WorkScheduleSelection.months(state.snapshot.journals, state.selection.cityName, year).forEach { month ->
                    DropdownMenuItem(text = { Text("${monthName(month)} $year") }, onClick = { expanded = false; onPeriod(year, month) })
                }
            }
        }
    }
}

@Composable
private fun ScheduleCell(text: String, width: Dp, height: Dp, background: Color = MaterialTheme.colorScheme.surfaceVariant,
    foreground: Color = MaterialTheme.colorScheme.onSurface, description: String = text) {
    Box(Modifier.width(width).height(height).background(background).padding(4.dp).semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = foreground)
    }
}

@Composable
private fun AuditRow(title: String, person: String?, epochMillis: Long?) {
    if (person == null && epochMillis == null) return
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        person?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        epochMillis?.let { Text(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm", RUSSIAN).format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault())), style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun dayBackground(journal: WorkScheduleJournal, day: Int, today: LocalDate, background: Color): Color =
    if (WorkScheduleViewport.currentDay(journal, today) == day) {
        androidx.compose.ui.graphics.lerp(background, MaterialTheme.colorScheme.primary, 0.16f)
    } else background

private val RUSSIAN = Locale.forLanguageTag("ru")
private fun scheduleDate(journal: WorkScheduleJournal, day: Int): LocalDate? = runCatching { LocalDate.of(journal.year, journal.month, day) }.getOrNull()
private fun monthName(month: Int): String = listOf("Январь", "Февраль", "Март", "Апрель", "Май", "Июнь", "Июль", "Август", "Сентябрь", "Октябрь", "Ноябрь", "Декабрь").getOrNull(month - 1) ?: "Месяц"
private fun hours(value: Double): String = if (value % 1.0 == 0.0) value.toLong().toString() else String.format(RUSSIAN, "%.1f", value)
