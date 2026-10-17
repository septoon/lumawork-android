package ru.lumastack.lumawork.ui.fuel

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import ru.lumastack.lumawork.domain.fuel.*
import ru.lumastack.lumawork.ui.theme.AppFormDialog
import ru.lumastack.lumawork.ui.theme.AppOutlinedButton
import ru.lumastack.lumawork.ui.theme.AppTextField

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FuelEditorDialog(draft: FuelDraft, isEditing: Boolean, isSaving: Boolean, errorMessage: String?,
    fuelTypes: List<String>, offline: Boolean, onChange: (FuelDraft) -> Unit, onDismiss: () -> Unit, onSave: () -> Unit) {
    var dateOpen by rememberSaveable { mutableStateOf(false) }
    var typeOpen by remember { mutableStateOf(false) }
    val types = (fuelTypes + draft.fuelType).filter(String::isNotBlank).distinct()
    AppFormDialog(title = if (draft.recordType == FuelRecordType.Fuel) {
        if (isEditing) "Редактировать запись" else "Добавить запись топлива"
    } else if (isEditing) "Редактировать корректировку" else "Добавить корректировку",
        onDismiss = onDismiss, confirmLabel = "Сохранить", onConfirm = onSave, isBusy = isSaving,
        confirmEnabled = !offline, maxWidth = 680.dp, errorMessage = errorMessage) {
        if (draft.recordType == FuelRecordType.Fuel) {
            item {
                AppOutlinedButton(onClick = { dateOpen = true }, enabled = !isSaving, modifier = Modifier.fillMaxWidth()) {
                    val date = runCatching { LocalDate.parse(draft.date.trim()) }.getOrNull()
                    Text("Дата: ${date?.format(DateTimeFormatter.ofPattern("dd.MM.yyyy")) ?: draft.date}")
                }
            }
            if (types.isNotEmpty()) item {
                Box {
                    AppOutlinedButton(onClick = { typeOpen = true }, enabled = !isSaving, modifier = Modifier.fillMaxWidth()) { Text(draft.fuelType.ifBlank { "Тип топлива" }) }
                    DropdownMenu(typeOpen, { typeOpen = false }) {
                        types.forEach { type -> DropdownMenuItem(text = { Text(type) }, onClick = { typeOpen = false; onChange(draft.copy(fuelType = type)) }) }
                    }
                }
            }
            item { FuelEditorField("Пробег (км)", draft.mileage, isSaving, KeyboardType.Number) { onChange(draft.copy(mileage = it)) } }
        } else {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(FuelAdjustmentKind.CompensationPayment to "Выплата", FuelAdjustmentKind.DebtDeduction to "Вычет долга").forEach { (kind, title) ->
                        FilterChip(selected = draft.adjustmentKind == kind, onClick = { onChange(draft.copy(adjustmentKind = kind)) }, label = { Text(title) }, enabled = !isSaving)
                    }
                }
            }
            item { FuelEditorField("Месяц, ГГГГ-ММ", draft.monthKey, isSaving) { onChange(draft.copy(monthKey = it)) } }
            item { FuelEditorField("Сумма", draft.amount, isSaving, KeyboardType.Decimal) { onChange(draft.copy(amount = it)) } }
            item { FuelEditorField("Литры", draft.liters, isSaving, KeyboardType.Decimal) { onChange(draft.copy(liters = it)) } }
            if (draft.adjustmentKind == FuelAdjustmentKind.DebtDeduction) item { FuelEditorField("Остаток долга", draft.carryoverDebtRub, isSaving, KeyboardType.Decimal) { onChange(draft.copy(carryoverDebtRub = it)) } }
            item { FuelEditorField("Комментарий", draft.comment, isSaving) { onChange(draft.copy(comment = it)) } }
        }
        if (offline) item { Text("Для сохранения требуется подключение к интернету.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    if (dateOpen) {
        val selected = runCatching { LocalDate.parse(draft.date.trim()) }.getOrDefault(LocalDate.now())
        val picker = rememberDatePickerState(initialSelectedDateMillis = selected.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(onDismissRequest = { dateOpen = false }, confirmButton = {
            TextButton(onClick = {
                picker.selectedDateMillis?.let { onChange(draft.copy(date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString())) }
                dateOpen = false
            }, enabled = picker.selectedDateMillis != null) { Text("Готово") }
        }, dismissButton = { TextButton(onClick = { dateOpen = false }) { Text("Отмена") } }) { DatePicker(picker) }
    }
}

@Composable
private fun FuelEditorField(label: String, value: String, busy: Boolean, keyboard: KeyboardType = KeyboardType.Text, onChange: (String) -> Unit) {
    AppTextField(value, onChange, label, enabled = !busy, singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = keyboard))
}
