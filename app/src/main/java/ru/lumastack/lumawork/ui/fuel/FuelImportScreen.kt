package ru.lumastack.lumawork.ui.fuel

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.lumastack.lumawork.data.fuel.FuelImportFailure
import ru.lumastack.lumawork.data.fuel.OkHttpFuelImportApi
import ru.lumastack.lumawork.domain.fuel.*
import ru.lumastack.lumawork.domain.model.AppSession
import ru.lumastack.lumawork.ui.theme.AppFormDialog
import ru.lumastack.lumawork.ui.theme.AppButton
import ru.lumastack.lumawork.ui.theme.AppOutlinedButton
import ru.lumastack.lumawork.ui.theme.AppTextField

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FuelImportScreen(
    session: AppSession,
    offline: Boolean,
    onDismiss: () -> Unit,
    onCommitted: (FuelImportCommitResponse) -> Unit,
) {
    // Only a digest identifies the retained in-memory operation. Credentials and XLSX bytes are never saved in a Bundle.
    val operationKey = remember(session.user.id, session.token) {
        MessageDigest.getInstance("SHA-256").digest("${session.user.id}\n${session.token}".toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
    val model: FuelImportViewModel = viewModel(key = "fuel-import:$operationKey")
    val dialogContext = LocalContext.current
    val context = dialogContext.applicationContext
    val activity = remember(dialogContext) {
        generateSequence(dialogContext) { (it as? ContextWrapper)?.baseContext }.filterIsInstance<Activity>().firstOrNull()
    }
    DisposableEffect(model, activity) {
        onDispose { if (activity?.isChangingConfigurations != true) model.reset() }
    }
    LaunchedEffect(offline) { model.setOffline(offline) }
    LaunchedEffect(model.committed) {
        model.committed?.let { result ->
            model.reset()
            onCommitted(result)
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (model.pickerPending) {
            model.pickerPending = false
            if (!offline && uris.isNotEmpty()) model.preview(context, session.token, uris)
        }
    }
    val importableCount = model.items.count { it.canImport(model.correction(it), model.replacements) }
    BackHandler { if (!model.busy) { model.reset(); onDismiss() } }
    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Импорт отчётов") }, navigationIcon = {
                IconButton(enabled = !model.busy, onClick = { model.reset(); onDismiss() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Закрыть")
                }
            })
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                AppButton(
                    onClick = { if (!offline) model.commit(session.token) },
                    enabled = !offline && !model.busy && importableCount > 0,
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                ) { Text(if (model.committing) "Импортируем…" else "Импортировать $importableCount ${monthWord(importableCount)}") }
            }
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            model.error?.let { message -> item {
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.errorContainer) {
                    Text(message, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onErrorContainer)
                }
            } }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Проверьте историю перед сохранением", style = MaterialTheme.typography.titleMedium)
                    Text("Каждая строка останется отдельной заправкой. Итоги рассчитаны по видам топлива и по месяцу.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("До 20 XLSX, каждый до 6 МБ, всего до 7 МБ.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (offline) Text("Импорт отчётов доступен при подключении к интернету.", color = MaterialTheme.colorScheme.error)
                    AppOutlinedButton(
                        enabled = !offline && !model.busy && !model.pickerPending,
                        onClick = {
                            model.pickerPending = true
                            try { picker.launch(arrayOf("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")) }
                            catch (_: Exception) { model.pickerPending = false; model.error = "Не удалось открыть выбор XLSX-файлов." }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (model.items.isEmpty()) "Выбрать XLSX-отчёты" else "Выбрать другие отчёты") }
                    if (model.busy) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(if (model.committing) "Импортируем отчёты…" else "Разбираем XLSX-отчёты…", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            items(model.items) { item ->
                ImportMonthCard(item, model.correction(item), model.replacements, !offline && !model.busy,
                    onCorrect = { model.editing = item.id; model.editDraft = model.correction(item); model.editError = null },
                    onReplacement = { id, checked -> model.replacements = if (checked) model.replacements + id else model.replacements - id },
                )
            }
            model.lastResult?.let { result ->
                items(result.results) { entry -> Text("${entry.fileName}: ${entry.message}", style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
    model.editDraft?.takeIf { model.editing != null }?.let { correction ->
        AppFormDialog(
            title = "Данные отчёта", dismissLabel = "Отмена", confirmLabel = "Готово",
            onDismiss = { model.editing = null; model.editDraft = null; model.editError = null },
            onConfirm = {
                model.editError = correction.validationMessage()
                if (model.editError == null) {
                    model.corrections = model.corrections + (requireNotNull(model.editing) to correction)
                    model.editing = null; model.editDraft = null
                }
            },
            confirmEnabled = !offline, errorMessage = model.editError,
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppTextField(correction.period.orEmpty(), { model.editDraft = correction.copy(period = it) }, "Месяц и год (ГГГГ-ММ)", singleLine = true)
                    AppOutlinedButton(onClick = {
                        val date = runCatching { YearMonth.parse(correction.period) }.getOrDefault(YearMonth.now())
                        android.app.DatePickerDialog(dialogContext, { _, year, month, _ ->
                            model.editDraft = (model.editDraft ?: correction).copy(period = YearMonth.of(year, month + 1).toString())
                        }, date.year, date.monthValue - 1, 1).show()
                    }) { Text("Выбрать в календаре") }
                    Text("День в календаре не учитывается. Месячная запись сохраняется на последний день выбранного месяца.", style = MaterialTheme.typography.bodySmall)
                }
            }
            items(correction.entries, key = { it.row }) { entry ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Строка ${entry.row}", style = MaterialTheme.typography.labelLarge)
                    fun update(next: FuelImportEntryCorrection) { model.editDraft = correction.copy(entries = correction.entries.map { if (it.row == next.row) next else it }) }
                    AppTextField(entry.date, { update(entry.copy(date = it)) }, "Дата YYYY-MM-DD", singleLine = true)
                    AppTextField(entry.fuelType, { update(entry.copy(fuelType = it)) }, "Вид топлива", singleLine = true)
                    AppTextField(entry.liters, { update(entry.copy(liters = it)) }, "Литры", singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    AppTextField(entry.cost, { update(entry.copy(cost = it)) }, "Сумма", singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    HorizontalDivider()
                }
            }
            item { Text("Пустые поля не блокируют импорт. Вы сможете дополнить записи позже.", style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun ImportMonthCard(
    item: FuelImportPreviewItem,
    correction: FuelImportCorrection,
    replacements: Set<String>,
    enabled: Boolean,
    onCorrect: () -> Unit,
    onReplacement: (String, Boolean) -> Unit,
) {
    val statusColor = when (item.status) {
        FuelImportStatus.Conflict, FuelImportStatus.Error -> MaterialTheme.colorScheme.error
        FuelImportStatus.Ready -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(importMonthTitle(correction.period), style = MaterialTheme.typography.titleMedium)
            Text(item.status.title, style = MaterialTheme.typography.labelLarge, color = statusColor)
            Text(item.fileName, style = MaterialTheme.typography.bodySmall)
            Text("${item.entries.size} ${refuelWord(item.entries.size)}", style = MaterialTheme.typography.labelMedium)
            val effectiveEntries = correction.effectiveEntries(item)
            effectiveEntries.forEach { entry ->
                val date = entry.date ?: correction.monthEndDate ?: "Дата не указана"
                val type = entry.fuelType ?: "Вид топлива не указан"
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("${importDayTitle(date)} · $type", style = MaterialTheme.typography.bodyMedium)
                    Text("${importNumber(entry.liters)} л · ${importNumber(entry.cost)} ₽",
                        style = MaterialTheme.typography.bodyMedium)
                }
                HorizontalDivider()
            }
            if (item.entries.isNotEmpty()) {
                val byType = effectiveEntries.groupBy { it.fuelType ?: "Вид топлива не указан" }
                if (byType.size > 1) {
                    Text("По видам топлива", style = MaterialTheme.typography.labelLarge)
                    byType.forEach { (type, entries) ->
                        Text("$type: ${importNumber(entries.sumOf { it.liters ?: 0.0 })} л · ${importNumber(entries.sumOf { it.cost ?: 0.0 })} ₽", style = MaterialTheme.typography.bodySmall)
                    }
                }
                Text("Итого: ${importNumber(effectiveEntries.sumOf { it.liters ?: 0.0 })} л · ${importNumber(effectiveEntries.sumOf { it.cost ?: 0.0 })} ₽", style = MaterialTheme.typography.titleSmall)
            }
            if (item.period == null || item.entries.any { it.isIncomplete }) {
                AppOutlinedButton(onClick = onCorrect, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                    Text(if (item.period == null) "Выбрать месяц и год" else "Дополнить данные")
                }
            }
            item.mileage?.let { Text("Пробег: ${importNumber(it)} км", style = MaterialTheme.typography.bodyMedium) }
            item.carModel?.takeIf(String::isNotBlank)?.let { Text("Автомобиль: $it", style = MaterialTheme.typography.bodySmall) }
            item.fuelNorm?.let { Text("Норма из отчёта: ${importNumber(it)} л / 100 км", style = MaterialTheme.typography.bodySmall) }
            item.errors.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            item.warnings.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (item.status == FuelImportStatus.Replaceable) item.existingImportId?.let { id ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Заменить предыдущий XLSX-импорт", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = id in replacements, onCheckedChange = { onReplacement(id, it) }, enabled = enabled)
                }
            }
        }
    }
}

internal class FuelImportViewModel : ViewModel() {
    private val api = OkHttpFuelImportApi()
    private var uploads: List<FuelImportUpload> = emptyList()
    private var operation: Job? = null
    private var offline = false
    var items by mutableStateOf<List<FuelImportPreviewItem>>(emptyList())
    var corrections by mutableStateOf<Map<String, FuelImportCorrection>>(emptyMap())
    var replacements by mutableStateOf<Set<String>>(emptySet())
    var busy by mutableStateOf(false)
    var committing by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var pickerPending by mutableStateOf(false)
    var editing by mutableStateOf<String?>(null)
    var editDraft by mutableStateOf<FuelImportCorrection?>(null)
    var editError by mutableStateOf<String?>(null)
    var committed by mutableStateOf<FuelImportCommitResponse?>(null)
    var lastResult by mutableStateOf<FuelImportCommitResponse?>(null)
    fun correction(item: FuelImportPreviewItem) = corrections[item.id] ?: item.correction()

    fun setOffline(value: Boolean) {
        offline = value
        if (value) { operation?.cancel(); operation = null; busy = false; committing = false; pickerPending = false }
    }
    fun reset() {
        operation?.cancel(); operation = null
        uploads = emptyList(); items = emptyList(); corrections = emptyMap(); replacements = emptySet()
        busy = false; committing = false; pickerPending = false; error = null; committed = null; lastResult = null
        editing = null; editDraft = null; editError = null
    }
    fun preview(context: Context, token: String, uris: List<Uri>) {
        if (busy || offline) return
        busy = true; error = null; lastResult = null
        uploads = emptyList(); items = emptyList(); corrections = emptyMap(); replacements = emptySet()
        operation = viewModelScope.launch {
            try {
                val files = readFuelImportFiles(context, uris)
                val preview = api.preview(token, files)
                uploads = files; items = preview
            } catch (error: Exception) { this@FuelImportViewModel.error = importError(error, "Не удалось разобрать XLSX-отчёты.") }
            finally { busy = false }
        }
    }
    fun commit(token: String) {
        if (offline || busy || uploads.isEmpty() || items.none { it.canImport(correction(it), replacements) }) return
        busy = true; committing = true; error = null
        operation = viewModelScope.launch {
            try {
                val result = api.commit(token, uploads, replacements, items.map(::correction))
                lastResult = result
                if (result.importedMonths > 0) committed = result
                else error = result.results.firstOrNull()?.message ?: "Нет месяцев, готовых к импорту."
            } catch (error: Exception) { this@FuelImportViewModel.error = importError(error, "Не удалось импортировать отчёты.") }
            finally { busy = false; committing = false }
        }
    }
}

private suspend fun readFuelImportFiles(context: Context, uris: List<Uri>): List<FuelImportUpload> = withContext(Dispatchers.IO) {
    require(uris.size <= FUEL_IMPORT_MAX_FILES) { "Можно выбрать не больше 20 XLSX-отчётов." }
    var totalBytes = 0
    val sizes = mutableListOf<Pair<String, Long>>()
    uris.map { uri ->
        currentCoroutineContext().ensureActive()
        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: uri.lastPathSegment.orEmpty().substringAfterLast('/')
        require(name.endsWith(".xlsx", ignoreCase = true)) { "Поддерживаются только файлы XLSX." }
        require(name.length <= 240) { "Имя XLSX должно быть не длиннее 240 символов." }
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            ByteArrayOutputStream().use { output ->
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= FUEL_IMPORT_MAX_FILE_BYTES) { "Размер каждого XLSX должен быть не больше 6 МБ." }
                    require(totalBytes + output.size() + count <= FUEL_IMPORT_MAX_TOTAL_BYTES) { "Общий размер выбранных отчётов должен быть не больше 7 МБ." }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        } ?: throw IllegalArgumentException("Не удалось прочитать XLSX-отчёт.")
        totalBytes += bytes.size
        sizes += name to bytes.size.toLong()
        validateFuelImportSelection(sizes)?.let { throw IllegalArgumentException(it) }
        FuelImportUpload(name, Base64.getEncoder().encodeToString(bytes))
    }
}

private fun importError(error: Exception, fallback: String): String {
    if (error is CancellationException) throw error
    return if (error is FuelImportFailure || error is IllegalArgumentException) error.message ?: fallback else fallback
}
private val importLocale = Locale.forLanguageTag("ru-RU")
private fun importNumber(value: Double?): String = value?.let { java.text.NumberFormat.getNumberInstance(importLocale).apply { maximumFractionDigits = 2 }.format(it) } ?: "—"
private fun importMonthTitle(period: String?): String = runCatching {
    YearMonth.parse(period).format(DateTimeFormatter.ofPattern("LLLL yyyy", importLocale)).replaceFirstChar { it.titlecase(importLocale) }
}.getOrDefault("Месяц не определён")
private fun importDayTitle(date: String): String = runCatching { LocalDate.parse(date).format(DateTimeFormatter.ofPattern("d MMMM", importLocale)) }.getOrDefault(date)
private fun monthWord(count: Int): String = if (count % 10 == 1 && count % 100 != 11) "месяц" else if (count % 10 in 2..4 && count % 100 !in 12..14) "месяца" else "месяцев"
private fun refuelWord(count: Int): String = if (count % 10 == 1 && count % 100 != 11) "заправка" else if (count % 10 in 2..4 && count % 100 !in 12..14) "заправки" else "заправок"
