package ru.lumastack.lumawork.ui.auto

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import ru.lumastack.lumawork.domain.auto.Vehicle
import ru.lumastack.lumawork.domain.auto.VehicleDraft
import ru.lumastack.lumawork.domain.auto.VehicleDraftValidation
import ru.lumastack.lumawork.domain.auto.VehicleInputValidation
import ru.lumastack.lumawork.ui.theme.AppFormDialog
import ru.lumastack.lumawork.ui.theme.AppTextField

private enum class VehicleEditorStep(val title: String) {
    Plate("Госномер"), Vin("VIN"), Identity("Автомобиль"), Details("Детали"), Review("Проверка"),
}

@Composable
internal fun VehicleEditor(
    draft: VehicleDraft,
    editingVehicle: Vehicle?,
    isSaving: Boolean,
    errorMessage: String?,
    onChange: (VehicleDraft) -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
    imageContent: @Composable (Vehicle) -> Unit = {},
    documentsContent: @Composable () -> Unit = {},
) {
    var stepIndex by rememberSaveable(editingVehicle?.id) { mutableStateOf(0) }
    var editing by rememberSaveable(editingVehicle?.id) { mutableStateOf(false) }
    var showDiscard by rememberSaveable(editingVehicle?.id) { mutableStateOf(false) }
    val originalDraft = remember(editingVehicle?.id) { editingVehicle?.let(::VehicleDraft) ?: VehicleDraft() }
    val step = VehicleEditorStep.entries[stepIndex]
    val showingSummary = editingVehicle != null && !editing
    val validationError = when {
        showingSummary -> null
        editingVehicle != null -> VehicleDraftValidation.error(draft)
        step == VehicleEditorStep.Plate -> VehicleInputValidation.plateError(draft.licensePlate)
        step == VehicleEditorStep.Vin -> VehicleInputValidation.vinError(draft.vin)
        step == VehicleEditorStep.Identity -> VehicleDraftValidation.identityError(draft)
        else -> null
    }
    val requestClose = {
        if (!isSaving) {
            if ((!showingSummary && draft != originalDraft)) showDiscard = true else onDismiss()
        }
    }
    AppFormDialog(
        title = when {
            showingSummary -> editingVehicle.displayName
            editingVehicle != null -> "Редактирование"
            else -> step.title
        },
        onDismiss = requestClose,
        dismissLabel = "Закрыть",
        confirmLabel = when {
            showingSummary -> "Редактировать автомобиль"
            editingVehicle != null -> "Сохранить автомобиль"
            step == VehicleEditorStep.Review -> "Добавить автомобиль"
            else -> "Далее"
        },
        onConfirm = {
            when {
                showingSummary -> {
                    onChange(VehicleDraft(editingVehicle))
                    editing = true
                }
                validationError != null -> Unit
                editingVehicle != null || step == VehicleEditorStep.Review -> onSave()
                else -> stepIndex++
            }
        },
        isBusy = isSaving,
        confirmEnabled = validationError == null,
        errorMessage = errorMessage ?: validationError,
        maxWidth = 760.dp,
    ) {
        if (showingSummary) {
            item(key = "vehicle:summary") { VehicleSummary(editingVehicle, imageContent) }
        } else {
            if (editingVehicle == null) {
                item(key = "vehicle:progress") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        LinearProgressIndicator(progress = { (stepIndex + 1) / 5f }, modifier = Modifier.fillMaxWidth())
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("Шаг ${stepIndex + 1} из 5", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (stepIndex > 0) TextButton(onClick = { stepIndex-- }, enabled = !isSaving) { Text("Назад") }
                        }
                    }
                }
            }
            item(key = "vehicle:fields") {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    if (editingVehicle != null) VehicleEditorFields(draft, onChange, enabled = !isSaving)
                    else when (step) {
                        VehicleEditorStep.Plate -> {
                            PlateField(draft, onChange, !isSaving)
                            EditorHint("Можно продолжить без региона и дописать его позже.")
                        }
                        VehicleEditorStep.Vin -> {
                            VinField(draft, onChange, !isSaving)
                            EditorHint("Латинские буквы и цифры без I, O и Q. Стандартный VIN — 17 символов.")
                        }
                        VehicleEditorStep.Identity -> IdentityFields(draft, onChange, !isSaving)
                        VehicleEditorStep.Details -> DetailFields(draft, onChange, !isSaving)
                        VehicleEditorStep.Review -> {
                            SummaryRow("Автомобиль", listOf(draft.make, draft.model, draft.generation).filter(String::isNotBlank).joinToString(" "))
                            SummaryRow("Госномер", draft.licensePlate)
                            SummaryRow("VIN", draft.vin.ifBlank { "Не указан" })
                            SummaryRow("Год", draft.year.ifBlank { "Не указан" })
                            SummaryRow("Кузов и цвет", listOf(draft.bodyType, draft.colorName).filter(String::isNotBlank).joinToString(", ").ifBlank { "Не указаны" })
                        }
                    }
                }
            }
        }
        if (editingVehicle != null) item(key = "vehicle:documents") { documentsContent() }
    }
    if (showDiscard) AlertDialog(
        onDismissRequest = { showDiscard = false },
        title = { Text(if (editingVehicle == null) "Удалить введённые данные?" else "Не сохранять изменения?") },
        confirmButton = {
            TextButton(onClick = { showDiscard = false; onDismiss() }, enabled = !isSaving) {
                Text(if (editingVehicle == null) "Удалить" else "Не сохранять", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = { showDiscard = false }) { Text("Продолжить заполнение") } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun VehicleSummary(vehicle: Vehicle, imageContent: @Composable (Vehicle) -> Unit = {}) {
    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            imageContent(vehicle)
            vehicle.licensePlate?.takeIf(String::isNotBlank)?.let { SummaryChip(it, monospaced = true) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SummaryChip(vehicle.year?.toString() ?: "Год не указан")
                SummaryChip(vehicle.colorName.summaryValue())
                if (vehicle.isPrimary) SummaryChip("Основной")
            }
        }
        SpecificationSection("Автомобиль", listOf("Марка" to vehicle.make.summaryValue(), "Модель" to vehicle.model.summaryValue(),
            "Поколение" to vehicle.generation.summaryValue(), "Кузов" to vehicle.bodyType.summaryValue()))
        SpecificationSection("Документы", listOf("VIN" to vehicle.vin.summaryValue(), "СТС" to vehicle.sts.summaryValue(),
            "ПТС" to vehicle.pts.summaryValue(), "Название" to vehicle.customName.summaryValue()), monospaced = true)
        SpecificationSection("Параметры", listOf("Пробег" to (vehicle.currentMileageKm?.let { "$it км" } ?: "Не указано"),
            "Объём" to (vehicle.engineVolumeCm3?.let { "$it см³" } ?: "Не указано"),
            "Мощность" to (vehicle.enginePowerHp?.let { "$it л.с." } ?: "Не указано"), "Цвет" to vehicle.colorName.summaryValue()))
    }
}

@Composable
private fun SpecificationSection(title: String, values: List<Pair<String, String>>, monospaced: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title.uppercase(), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        values.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.fillMaxWidth()) {
                row.forEach { (label, value) ->
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        HorizontalDivider()
                        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                            fontFamily = if (monospaced) FontFamily.Monospace else FontFamily.Default)
                    }
                }
            }
        }
    }
}

@Composable
private fun SummaryChip(value: String, monospaced: Boolean = false) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Text(value, Modifier.padding(horizontal = 12.dp, vertical = 7.dp), style = MaterialTheme.typography.labelMedium,
            fontFamily = if (monospaced) FontFamily.Monospace else FontFamily.Default)
    }
}

@Composable
private fun SummaryRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
internal fun VehicleEditorFields(
    draft: VehicleDraft,
    onChange: (VehicleDraft) -> Unit,
    showPrimaryToggle: Boolean = true,
    enabled: Boolean = true,
) {
    PlateField(draft, onChange, enabled)
    VinField(draft, onChange, enabled)
    EditorField("СТС", draft.sts, { onChange(draft.copy(sts = it.uppercase())) }, enabled, KeyboardType.Ascii, monospaced = true, placeholder = "00 00 000000")
    EditorField("ПТС", draft.pts, { onChange(draft.copy(pts = it.uppercase())) }, enabled, KeyboardType.Ascii, monospaced = true, placeholder = "00 АА 000000")
    IdentityFields(draft, onChange, enabled)
    DetailFields(draft, onChange, enabled, showPrimaryToggle)
}

@Composable
private fun PlateField(draft: VehicleDraft, onChange: (VehicleDraft) -> Unit, enabled: Boolean) {
    EditorField("Государственный номер", draft.licensePlate, { onChange(draft.copy(licensePlate = VehicleInputValidation.formatPlate(it))) },
        enabled, KeyboardType.Ascii, monospaced = true, placeholder = "А123ВС 00")
}

@Composable
private fun VinField(draft: VehicleDraft, onChange: (VehicleDraft) -> Unit, enabled: Boolean) {
    EditorField("VIN (необязательно)", draft.vin, { onChange(draft.copy(vin = VehicleInputValidation.normalizeVin(it))) },
        enabled, KeyboardType.Ascii, monospaced = true, placeholder = "17 символов VIN")
}

@Composable
private fun IdentityFields(draft: VehicleDraft, onChange: (VehicleDraft) -> Unit, enabled: Boolean) {
    EditorField("Марка", draft.make, { onChange(draft.copy(make = it)) }, enabled)
    EditorField("Модель", draft.model, { onChange(draft.copy(model = it)) }, enabled)
    EditorField("Поколение (необязательно)", draft.generation, { onChange(draft.copy(generation = it)) }, enabled)
    EditorField("Год выпуска", draft.year, { onChange(draft.copy(year = it)) }, enabled, KeyboardType.Number)
}

@Composable
private fun DetailFields(draft: VehicleDraft, onChange: (VehicleDraft) -> Unit, enabled: Boolean, showPrimaryToggle: Boolean = true) {
    EditorField("Тип кузова", draft.bodyType, { onChange(draft.copy(bodyType = it)) }, enabled)
    EditorField("Цвет", draft.colorName, { onChange(draft.copy(colorName = it)) }, enabled)
    EditorField("Текущий пробег, км", draft.currentMileageKm, { onChange(draft.copy(currentMileageKm = it)) }, enabled, KeyboardType.Number)
    EditorField("Объём двигателя, см³", draft.engineVolumeCm3, { onChange(draft.copy(engineVolumeCm3 = it)) }, enabled, KeyboardType.Number)
    EditorField("Мощность, л.с.", draft.enginePowerHp, { onChange(draft.copy(enginePowerHp = it)) }, enabled, KeyboardType.Number)
    EditorField("Моё название (необязательно)", draft.customName, { onChange(draft.copy(customName = it)) }, enabled)
    if (showPrimaryToggle) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Основной автомобиль", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = draft.isPrimary, onCheckedChange = { onChange(draft.copy(isPrimary = it)) }, enabled = enabled)
    }
}

@Composable
private fun EditorField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    enabled: Boolean,
    keyboardType: KeyboardType = KeyboardType.Text,
    monospaced: Boolean = false,
    placeholder: String = label,
) {
    AppTextField(value = value, onValueChange = onChange, label = label, enabled = enabled, placeholder = placeholder,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, capitalization = when {
            keyboardType == KeyboardType.Ascii -> KeyboardCapitalization.Characters
            keyboardType == KeyboardType.Text -> KeyboardCapitalization.Words
            else -> KeyboardCapitalization.None
        }), singleLine = true, modifier = Modifier.fillMaxWidth(),
        textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = if (monospaced) FontFamily.Monospace else FontFamily.Default))
}

@Composable
private fun EditorHint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private fun String?.summaryValue(): String = this?.trim()?.ifBlank { null } ?: "Не указано"
