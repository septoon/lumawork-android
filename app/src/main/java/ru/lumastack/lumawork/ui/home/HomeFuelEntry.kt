package ru.lumastack.lumawork.ui.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import ru.lumastack.lumawork.data.fuel.FuelFailure
import ru.lumastack.lumawork.data.fuel.FuelRepository
import ru.lumastack.lumawork.domain.fuel.FuelDraft
import ru.lumastack.lumawork.domain.fuel.validateRefuelingDraft
import ru.lumastack.lumawork.domain.model.AppSession
import ru.lumastack.lumawork.ui.theme.AppButton
import ru.lumastack.lumawork.ui.theme.AppOutlinedButton
import ru.lumastack.lumawork.ui.theme.AppTextField
import ru.lumastack.lumawork.ui.theme.AppSurfaces

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeFuelEntry(session: AppSession, offline: Boolean, repository: FuelRepository, onDismiss: () -> Unit) {
    var date by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    var fuelType by rememberSaveable { mutableStateOf("") }
    var liters by rememberSaveable { mutableStateOf("") }
    var cost by rememberSaveable { mutableStateOf("") }
    var types by remember { mutableStateOf(emptyList<String>()) }
    var typesLoading by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var dateOpen by rememberSaveable { mutableStateOf(false) }
    var typeOpen by rememberSaveable { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(session.user.id, retry) {
        types = repository.read(session.user.id)?.records.orEmpty().mapNotNull { it.fuelType }.filter(String::isNotBlank).distinct()
        if (!offline) {
            typesLoading = true
            try { types = repository.fuelTypes(session) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error = "Не удалось загрузить типы топлива. Повторите загрузку." }
            finally { typesLoading = false }
        }
        if (fuelType.isBlank()) fuelType = types.firstOrNull().orEmpty()
    }
    fun save() {
        if (saving) return
        val draft = FuelDraft(date = date, fuelType = fuelType, liters = liters, fuelCost = cost)
        error = validateRefuelingDraft(draft) ?: if (offline) "Для сохранения заправки требуется подключение к интернету." else null
        if (error != null) return
        saving = true
        scope.launch {
            try { repository.save(session, null, draft); onDismiss() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = (failure as? FuelFailure)?.message ?: "Не удалось сохранить заправку." }
            finally { saving = false }
        }
    }
    ModalBottomSheet(containerColor = AppSurfaces.panel, tonalElevation = 0.dp, scrimColor = AppSurfaces.scrim, onDismissRequest = { if (!saving) onDismiss() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), sheetMaxWidth = 680.dp) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Новая заправка", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = onDismiss, enabled = !saving) { Text("Отмена") }
            }
            Text("Для листа Отчет по ТК", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            AppOutlinedButton(onClick = { dateOpen = true }, enabled = !saving, modifier = Modifier.fillMaxWidth()) {
                Text("Дата заправки: ${LocalDate.parse(date).format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy"))}")
            }
            Box {
                AppOutlinedButton(onClick = { typeOpen = true }, enabled = !saving && types.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                    Text(fuelType.ifBlank { "Тип топлива" }, Modifier.weight(1f))
                    if (typesLoading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                }
                DropdownMenu(typeOpen, onDismissRequest = { typeOpen = false }) {
                    types.forEach { type -> DropdownMenuItem(text = { Text(type) }, onClick = { fuelType = type; typeOpen = false }) }
                }
            }
            if (types.isEmpty() && !typesLoading) {
                Text("Типы топлива не настроены в профиле ГСМ.", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { retry++ }, enabled = !offline) { Text("Повторить загрузку") }
            }
            AppTextField(liters, { liters = it }, "Литры", enabled = !saving, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
            AppTextField(cost, { cost = it }, "Сумма в рублях", enabled = !saving, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            AppButton(onClick = ::save, enabled = !saving, modifier = Modifier.fillMaxWidth()) {
                if (saving) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = androidx.compose.material3.LocalContentColor.current)
                Text(if (saving) "Сохраняю…" else "Сохранить заправку")
            }
        }
    }
    if (dateOpen) {
        val picker = rememberDatePickerState(initialSelectedDateMillis = LocalDate.parse(date).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(onDismissRequest = { dateOpen = false },
            confirmButton = { TextButton(onClick = {
                picker.selectedDateMillis?.let { date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString() }
                dateOpen = false
            }) { Text("Готово") } }, dismissButton = { TextButton(onClick = { dateOpen = false }) { Text("Отмена") } }) { DatePicker(picker) }
    }
}
