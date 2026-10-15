package ru.lumastack.lumawork.ui.auto

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import ru.lumastack.lumawork.data.auto.AutoRepository
import ru.lumastack.lumawork.data.auto.VehicleImageRepository
import ru.lumastack.lumawork.domain.auto.MaintenanceDraft
import ru.lumastack.lumawork.domain.auto.MaintenancePartDraft
import ru.lumastack.lumawork.domain.auto.MaintenanceRecord
import ru.lumastack.lumawork.domain.auto.Vehicle
import ru.lumastack.lumawork.domain.auto.VehicleDraft
import ru.lumastack.lumawork.domain.auto.VehicleInputValidation
import ru.lumastack.lumawork.domain.model.AppSession
import ru.lumastack.lumawork.ui.adaptive.AppWidthClass
import ru.lumastack.lumawork.ui.theme.LumaBackgroundBottomDark
import ru.lumastack.lumawork.ui.theme.LumaBackgroundBottomLight
import ru.lumastack.lumawork.ui.theme.LumaBackgroundTopDark
import ru.lumastack.lumawork.ui.theme.LumaBackgroundTopLight

@Composable
fun AutoRoute(
    session: AppSession,
    startOffline: Boolean,
    repository: AutoRepository,
    imageRepository: VehicleImageRepository,
    widthClass: AppWidthClass,
    modifier: Modifier = Modifier,
) {
    val autoViewModel: AutoViewModel = viewModel(
        key = "auto-${session.user.id}-$startOffline",
        factory = AutoViewModel.factory(repository, session, startOffline),
    )
    val state by autoViewModel.state.collectAsStateWithLifecycle()
    AutoScreen(
        state = state,
        widthClass = widthClass,
        imageRepository = imageRepository,
        onRefresh = autoViewModel::refresh,
        onSelectVehicle = autoViewModel::selectVehicle,
        onAddVehicle = autoViewModel::addVehicle,
        onEditVehicle = autoViewModel::editVehicle,
        onUpdateVehicleDraft = autoViewModel::updateVehicleDraft,
        onCloseVehicleEditor = autoViewModel::closeVehicleEditor,
        onSaveVehicle = autoViewModel::saveVehicle,
        onAddMaintenance = autoViewModel::addMaintenance,
        onEditMaintenance = autoViewModel::editMaintenance,
        onUpdateMaintenanceDraft = autoViewModel::updateMaintenanceDraft,
        onCloseMaintenanceEditor = autoViewModel::closeMaintenanceEditor,
        onSaveMaintenance = autoViewModel::saveMaintenance,
        modifier = modifier,
    )
}

@Composable
private fun AutoScreen(
    state: AutoUiState,
    widthClass: AppWidthClass,
    imageRepository: VehicleImageRepository,
    onRefresh: () -> Unit,
    onSelectVehicle: (String) -> Unit,
    onAddVehicle: () -> Unit,
    onEditVehicle: (Vehicle) -> Unit,
    onUpdateVehicleDraft: (VehicleDraft) -> Unit,
    onCloseVehicleEditor: () -> Unit,
    onSaveVehicle: () -> Unit,
    onAddMaintenance: () -> Unit,
    onEditMaintenance: (MaintenanceRecord) -> Unit,
    onUpdateMaintenanceDraft: (MaintenanceDraft) -> Unit,
    onCloseMaintenanceEditor: () -> Unit,
    onSaveMaintenance: () -> Unit,
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
                Modifier.fillMaxSize().widthIn(max = 720.dp),
                contentPadding = PaddingValues(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item(key = "vehicles") {
                    VehicleSelector(
                        state = state,
                        imageRepository = imageRepository,
                        onSelect = onSelectVehicle,
                        onAdd = onAddVehicle,
                        onEdit = onEditVehicle,
                    )
                }
                item(key = "overview") { MaintenanceOverview(state, onRefresh, onAddMaintenance) }
                autoMessages(state)
                maintenanceItems(state.selectedMaintenance, onEditMaintenance)
            }
        } else {
            Row(
                Modifier.fillMaxSize().widthIn(max = 1180.dp).padding(20.dp),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                LazyColumn(
                    Modifier.weight(0.44f).fillMaxHeight().widthIn(max = 500.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    item(key = "vehicles") {
                        VehicleSelector(
                            state = state,
                            imageRepository = imageRepository,
                            onSelect = onSelectVehicle,
                            onAdd = onAddVehicle,
                            onEdit = onEditVehicle,
                            tablet = true,
                        )
                    }
                }
                Surface(
                    Modifier.weight(0.56f).fillMaxHeight(),
                    shape = RoundedCornerShape(26.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                    tonalElevation = 2.dp,
                ) {
                    LazyColumn(
                        contentPadding = PaddingValues(20.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        item(key = "overview") { MaintenanceOverview(state, onRefresh, onAddMaintenance) }
                        autoMessages(state)
                        maintenanceItems(state.selectedMaintenance, onEditMaintenance)
                    }
                }
            }
        }
    }

    state.vehicleEditor?.let { draft ->
        VehicleEditorDialog(
            draft = draft,
            isEditing = state.editingVehicleId != null,
            isSaving = state.isSaving,
            errorMessage = state.editorErrorMessage,
            onChange = onUpdateVehicleDraft,
            onDismiss = onCloseVehicleEditor,
            onSave = onSaveVehicle,
        )
    }
    state.maintenanceEditor?.let { draft ->
        MaintenanceEditorDialog(
            draft = draft,
            vehicles = state.snapshot.vehicles,
            isEditing = state.editingMaintenanceId != null,
            isSaving = state.isSaving,
            errorMessage = state.editorErrorMessage,
            onChange = onUpdateMaintenanceDraft,
            onDismiss = onCloseMaintenanceEditor,
            onSave = onSaveMaintenance,
        )
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.autoMessages(state: AutoUiState) {
    state.errorMessage?.let { error ->
        item(key = "error") { AutoMessage("Не удалось обновить данные", error, true) }
    }
    state.notice?.let { notice -> item(key = "notice") { AutoMessage("Готово", notice) } }
    if (state.isLoading && state.snapshot.vehicles.isEmpty()) {
        item(key = "loading") {
            Row(Modifier.fillMaxWidth().padding(28.dp), horizontalArrangement = Arrangement.Center) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.maintenanceItems(
    records: List<MaintenanceRecord>,
    onEdit: (MaintenanceRecord) -> Unit,
) {
    if (records.isEmpty()) {
        item(key = "empty-maintenance") {
            AutoMessage("Записей пока нет", "Добавьте первое обслуживание выбранного автомобиля.")
        }
    } else {
        item(key = "history-title") { SectionLabel("ИСТОРИЯ") }
        items(records, key = MaintenanceRecord::stableId) { record ->
            MaintenanceRow(record, onClick = { onEdit(record) })
        }
    }
}

@Composable
private fun VehicleSelector(
    state: AutoUiState,
    imageRepository: VehicleImageRepository,
    onSelect: (String) -> Unit,
    onAdd: () -> Unit,
    onEdit: (Vehicle) -> Unit,
    tablet: Boolean = false,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("АВТОМОБИЛИ")
            TextButton(onClick = onAdd) { Text("Добавить") }
        }
        if (state.snapshot.vehicles.isEmpty()) {
            Surface(
                Modifier.fillMaxWidth().clickable(onClick = onAdd),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
            ) {
                Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Добавьте автомобиль", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("Данные синхронизируются с iOS через общий сервер.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else if (tablet) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                state.snapshot.vehicles.forEach { vehicle ->
                    VehicleCard(
                        vehicle = vehicle,
                        selected = vehicle.id == state.snapshot.selectedVehicleId,
                        imageRepository = imageRepository,
                        onSelect = { onSelect(vehicle.id) },
                        onEdit = { onEdit(vehicle) },
                        compact = true,
                    )
                }
            }
        } else {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                state.snapshot.vehicles.forEach { vehicle ->
                    VehicleCard(
                        vehicle = vehicle,
                        selected = vehicle.id == state.snapshot.selectedVehicleId,
                        imageRepository = imageRepository,
                        onSelect = { onSelect(vehicle.id) },
                        onEdit = { onEdit(vehicle) },
                        modifier = Modifier.width(310.dp),
                    )
                }
                Surface(
                    Modifier.width(130.dp).height(230.dp).clickable(onClick = onAdd),
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                ) {
                    Box(contentAlignment = Alignment.Center) { Text("+\nДобавить", fontWeight = FontWeight.Bold) }
                }
            }
        }
    }
}

@Composable
private fun VehicleCard(
    vehicle: Vehicle,
    selected: Boolean,
    imageRepository: VehicleImageRepository,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    Card(
        onClick = onSelect,
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface.copy(alpha = 0.97f),
        ),
        shape = RoundedCornerShape(24.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text(vehicle.displayName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(vehicle.modelLine.ifBlank { "Марка и модель не указаны" }, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
                TextButton(onClick = onEdit) { Text("Изменить") }
            }
            if (!compact) VehicleImage(vehicle, imageRepository, Modifier.fillMaxWidth().height(105.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                vehicle.licensePlate?.takeIf(String::isNotBlank)?.let { PlateBadge(it) }
                vehicle.year?.let { InfoBadge(it.toString()) }
                if (vehicle.isPrimary) InfoBadge("Основной")
            }
            val info = listOfNotNull(
                vehicle.currentMileageKm?.let { "${formatInt(it)} км" },
                vehicle.colorName?.takeIf(String::isNotBlank),
            ).joinToString(" · ")
            if (info.isNotBlank()) Text(info, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun VehicleImage(vehicle: Vehicle, repository: VehicleImageRepository, modifier: Modifier = Modifier) {
    var bitmap by remember(vehicle.imageUrl) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(vehicle.imageUrl) { bitmap = vehicle.imageUrl?.let { repository.load(it) } }
    Box(
        modifier.background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f), RoundedCornerShape(16.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(bitmap!!.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        } else {
            Text("АВТО", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Black, style = MaterialTheme.typography.headlineSmall)
        }
    }
}

@Composable
private fun MaintenanceOverview(state: AutoUiState, onRefresh: () -> Unit, onAdd: () -> Unit) {
    val records = state.selectedMaintenance
    val total = records.mapNotNull(MaintenanceRecord::resolvedTotalCost).sum()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("ОБСЛУЖИВАНИЕ")
            Row {
                TextButton(onClick = onRefresh, enabled = !state.isLoading) { Text("Обновить") }
                Button(onClick = onAdd, enabled = state.selectedVehicle != null) { Text("Добавить") }
            }
        }
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
            tonalElevation = 1.dp,
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(state.selectedVehicle?.displayName ?: "Автомобиль не выбран", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AutoMetric("Записей", records.size.toString(), Modifier.weight(1f))
                    AutoMetric("Расходы", formatRubles(total), Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AutoMetric("Последнее ТО", records.firstOrNull()?.date?.shortDate() ?: "Нет данных", Modifier.weight(1f))
                    AutoMetric("Пробег", records.firstOrNull()?.let { "${formatInt(it.mileage)} км" } ?: "Нет данных", Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun AutoMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier.background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f), RoundedCornerShape(14.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
        Text(value, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun MaintenanceRow(record: MaintenanceRecord, onClick: () -> Unit) {
    Surface(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        tonalElevation = 1.dp,
    ) {
        Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            Box(Modifier.padding(top = 5.dp).size(10.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(record.procedure, modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                    record.resolvedTotalCost?.let { Text(formatRubles(it), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold) }
                }
                Text("${record.date.shortDate()} · ${formatInt(record.mileage)} км", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                if (record.parts.isNotEmpty()) {
                    Text(record.parts.joinToString(" • ") { "${it.name} — ${formatRubles(it.cost)}" }, style = MaterialTheme.typography.bodySmall)
                }
                record.workCost?.let { Text("Работа: ${formatRubles(it)}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium) }
            }
        }
    }
}

@Composable
private fun VehicleEditorDialog(
    draft: VehicleDraft,
    isEditing: Boolean,
    isSaving: Boolean,
    errorMessage: String?,
    onChange: (VehicleDraft) -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
) {
    AutoEditorDialog(if (isEditing) "Редактирование" else "Новый автомобиль", isSaving, errorMessage, onDismiss, onSave) {
        VehicleTextField("Государственный номер", draft.licensePlate, { onChange(draft.copy(licensePlate = VehicleInputValidation.formatPlate(it))) }, KeyboardType.Ascii)
        VehicleTextField("VIN", draft.vin, { onChange(draft.copy(vin = VehicleInputValidation.normalizeVin(it))) }, KeyboardType.Ascii)
        VehicleTextField("СТС", draft.sts, { onChange(draft.copy(sts = it.uppercase())) }, KeyboardType.Ascii)
        VehicleTextField("ПТС", draft.pts, { onChange(draft.copy(pts = it.uppercase())) }, KeyboardType.Ascii)
        VehicleTextField("Марка", draft.make, { onChange(draft.copy(make = it)) })
        VehicleTextField("Модель", draft.model, { onChange(draft.copy(model = it)) })
        VehicleTextField("Поколение", draft.generation, { onChange(draft.copy(generation = it)) })
        VehicleTextField("Год выпуска", draft.year, { onChange(draft.copy(year = it.filter(Char::isDigit).take(4))) }, KeyboardType.Number)
        VehicleTextField("Тип кузова", draft.bodyType, { onChange(draft.copy(bodyType = it)) })
        VehicleTextField("Цвет", draft.colorName, { onChange(draft.copy(colorName = it)) })
        VehicleTextField("Текущий пробег, км", draft.currentMileageKm, { onChange(draft.copy(currentMileageKm = it.filter(Char::isDigit))) }, KeyboardType.Number)
        VehicleTextField("Объём двигателя, см³", draft.engineVolumeCm3, { onChange(draft.copy(engineVolumeCm3 = it.filter(Char::isDigit))) }, KeyboardType.Number)
        VehicleTextField("Мощность, л.с.", draft.enginePowerHp, { onChange(draft.copy(enginePowerHp = it.filter(Char::isDigit))) }, KeyboardType.Number)
        VehicleTextField("Моё название", draft.customName, { onChange(draft.copy(customName = it)) })
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = draft.isPrimary, onCheckedChange = { onChange(draft.copy(isPrimary = it)) })
            Text("Основной автомобиль")
        }
        Text("Выбор в карусели не меняет автомобиль GSM.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun MaintenanceEditorDialog(
    draft: MaintenanceDraft,
    vehicles: List<Vehicle>,
    isEditing: Boolean,
    isSaving: Boolean,
    errorMessage: String?,
    onChange: (MaintenanceDraft) -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
) {
    AutoEditorDialog(if (isEditing) "Редактирование ТО" else "Новое обслуживание", isSaving, errorMessage, onDismiss, onSave) {
        if (vehicles.size > 1) {
            SectionLabel("АВТОМОБИЛЬ")
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                vehicles.forEach { vehicle ->
                    if (vehicle.id == draft.vehicleId) Button(onClick = {}) { Text(vehicle.displayName) }
                    else OutlinedButton(onClick = { onChange(draft.copy(vehicleId = vehicle.id)) }) { Text(vehicle.displayName) }
                }
            }
        }
        VehicleTextField("Дата, ГГГГ-ММ-ДД", draft.date, { onChange(draft.copy(date = it)) }, KeyboardType.Ascii)
        VehicleTextField("Процедура", draft.procedure, { onChange(draft.copy(procedure = it)) })
        VehicleTextField("Пробег", draft.mileage, { onChange(draft.copy(mileage = it.filter(Char::isDigit))) }, KeyboardType.Number)
        VehicleTextField("Стоимость работы", draft.workCost, { onChange(draft.copy(workCost = it)) }, KeyboardType.Decimal)
        SectionLabel("ЗАПЧАСТИ")
        draft.parts.forEachIndexed { index, part ->
            Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    VehicleTextField("Название", part.name, { value -> onChange(draft.copy(parts = draft.parts.replace(index, part.copy(name = value)))) })
                    VehicleTextField("Стоимость", part.cost, { value -> onChange(draft.copy(parts = draft.parts.replace(index, part.copy(cost = value)))) }, KeyboardType.Decimal)
                    TextButton(onClick = { onChange(draft.copy(parts = draft.parts.filterIndexed { partIndex, _ -> partIndex != index })) }) { Text("Удалить") }
                }
            }
        }
        OutlinedButton(onClick = { onChange(draft.copy(parts = draft.parts + MaintenancePartDraft())) }, modifier = Modifier.fillMaxWidth()) {
            Text("Добавить запчасть")
        }
    }
}

@Composable
private fun AutoEditorDialog(
    title: String,
    isSaving: Boolean,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
    content: @Composable () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            Modifier.fillMaxWidth().fillMaxHeight(0.94f).widthIn(max = 760.dp).padding(16.dp),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    TextButton(onClick = onDismiss, enabled = !isSaving) { Text("Закрыть") }
                }
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { content() } }
                }
                errorMessage?.let { AutoMessage("Проверьте данные", it, true) }
                Button(onClick = onSave, enabled = !isSaving, modifier = Modifier.fillMaxWidth()) {
                    if (isSaving) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Text("Сохранить")
                }
            }
        }
    }
}

@Composable
private fun VehicleTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        singleLine = true,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SectionLabel(value: String) {
    Text(value, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
}

@Composable
private fun PlateBadge(value: String) {
    Surface(color = Color.White, shape = RoundedCornerShape(6.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Color.DarkGray)) {
        Text(value, Modifier.padding(horizontal = 8.dp, vertical = 4.dp), color = Color.Black, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun InfoBadge(value: String) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = CircleShape) {
        Text(value, Modifier.padding(horizontal = 9.dp, vertical = 5.dp), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun AutoMessage(title: String, message: String, isError: Boolean = false) {
    Surface(
        color = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(15.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, fontWeight = FontWeight.Bold)
            Text(message, color = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun List<MaintenancePartDraft>.replace(index: Int, value: MaintenancePartDraft): List<MaintenancePartDraft> =
    mapIndexed { current, item -> if (current == index) value else item }

private fun String.shortDate(): String = runCatching {
    LocalDate.parse(this).format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))
}.getOrDefault(this)

private fun formatRubles(value: Double): String = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("ru-RU")).format(value)
private fun formatInt(value: Int): String = NumberFormat.getIntegerInstance(Locale.forLanguageTag("ru-RU")).format(value)
