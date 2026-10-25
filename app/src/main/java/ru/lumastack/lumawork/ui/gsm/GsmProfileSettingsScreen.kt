package ru.lumastack.lumawork.ui.gsm

import ru.lumastack.lumawork.ui.theme.AppContentHorizontalPadding
import ru.lumastack.lumawork.domain.model.UserProfile
import ru.lumastack.lumawork.data.auth.AuthRepository
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.Month
import java.time.YearMonth
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import ru.lumastack.lumawork.BuildConfig
import ru.lumastack.lumawork.data.auto.AutoRepository
import ru.lumastack.lumawork.data.auto.VehicleImageRepository
import ru.lumastack.lumawork.data.gsm.*
import ru.lumastack.lumawork.domain.auto.Vehicle
import ru.lumastack.lumawork.domain.model.AppSession
import ru.lumastack.lumawork.ui.auto.VehicleImage
import ru.lumastack.lumawork.ui.theme.AppTextField
import ru.lumastack.lumawork.ui.theme.appScreenBackground
import ru.lumastack.lumawork.ui.theme.appTopBarColors
import androidx.compose.foundation.background
import ru.lumastack.lumawork.ui.theme.AppButton
import ru.lumastack.lumawork.ui.theme.AppPanel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GsmProfileSettingsScreen(
    session: AppSession,
    offline: Boolean,
    autoRepository: AutoRepository,
    auth: AuthRepository,
    onBack: () -> Unit,
    onAddVehicle: (() -> Unit)? = null,
    onSaved: suspend (GsmProfile) -> Unit = {},
    importOnOpen: Boolean = false,
) {
    val context = LocalContext.current.applicationContext
    val json = remember { Json { ignoreUnknownKeys = true; encodeDefaults = true } }
    val repository = remember(context) { GsmProfileRepository(OkHttpGsmProfileApi(BuildConfig.API_ORIGIN, json), FileGsmProfileCache(context, json)) }
    val images = remember(context) { VehicleImageRepository(context) }
    val editorSaver = remember(json) { Saver<GsmProfileEditor, String>(save = { json.encodeToString(it) }, restore = { runCatching { json.decodeFromString<GsmProfileEditor>(it) }.getOrNull() }) }
    val owner = session.user.id
    val drafts = remember(context) { context.getSharedPreferences("gsm-profile-drafts", android.content.Context.MODE_PRIVATE) }
    val restored = remember(owner) { drafts.getString(owner, null)?.let { runCatching { json.decodeFromString<GsmLocalDraft>(it) }.getOrNull() } }
    var editor by rememberSaveable(owner, stateSaver = editorSaver) { mutableStateOf(restored?.editor ?: GsmProfileEditor()) }
    val importSaver = remember(json) { Saver<GsmProfileImport, String>(save = { json.encodeToString(it) }, restore = { json.decodeFromString<GsmProfileImport>(it) }) }
    var imported by rememberSaveable(owner, stateSaver = importSaver) { mutableStateOf(restored?.imported ?: GsmProfileImport(GsmProfile(), emptyMap(), emptyList())) }
    var importing by remember(owner) { mutableStateOf(false) }
    var importStarted by rememberSaveable(owner) { mutableStateOf(false) }
    var confirmReplace by remember { mutableStateOf(false) }
    var sourcesOpen by rememberSaveable(owner) { mutableStateOf(false) }
    var vehicles by remember(owner) { mutableStateOf(emptyList<Vehicle>()) }
    var loading by remember(owner) { mutableStateOf(false) }
    var saving by remember(owner) { mutableStateOf(false) }
    var serverConfirmedEmpty by remember(owner, session.token) { mutableStateOf(false) }
    var profileReady by rememberSaveable(owner) { mutableStateOf(false) }
    var pendingVehicleId by rememberSaveable(owner) { mutableStateOf<String?>(null) }
    var normInput by rememberSaveable(owner) { mutableStateOf("") }
    var odometerInput by rememberSaveable(owner) { mutableStateOf(restored?.editor?.draft?.defaultStartOdometer?.toString().orEmpty()) }
    val snackbars = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val currentOnBack by rememberUpdatedState(onBack)
    val currentOnSaved by rememberUpdatedState(onSaved)

    fun edit(transform: (GsmProfile) -> GsmProfile) { editor = editor.edit(transform) }
    // Device-only draft, scoped by account. This never calls PUT /gsm/profile.
    SideEffect {
        if (editor.isEdited) drafts.edit().putString(owner, json.encodeToString(GsmLocalDraft(editor, imported))).apply()
        else drafts.edit().remove(owner).apply()
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            importing = true
            try {
                val projects = editor.snapshot.projects
                val result = withContext(Dispatchers.IO) {
                    val bytes = context.contentResolver.openInputStream(uri)?.use(GsmProfileXlsx::read)
                        ?: throw GsmXlsxFailure("Не удалось открыть выбранный файл.")
                    GsmProfileXlsx.parse(bytes, YearMonth.now(ZoneId.of("Europe/Simferopol")).minusMonths(1), projects)
                }
                imported = result
                edit { result.profile }
                normInput = result.profile.fuelNorm.toString()
                odometerInput = result.profile.defaultStartOdometer.toString()
                snackbars.showSnackbar("Данные прочитаны. Проверьте все поля и нажмите «Сохранить».")
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { snackbars.showSnackbar(error.message ?: "Не удалось прочитать XLSX.") }
            finally { importing = false }
        }
    }
    val openPicker = { picker.launch(arrayOf("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")) }
    LaunchedEffect(serverConfirmedEmpty, loading, importOnOpen) {
        if (serverConfirmedEmpty && !loading && importOnOpen && !importStarted) { importStarted = true; openPicker() }
    }
    val requestBack = { if (!saving && !importing) currentOnBack() }
    BackHandler {
        if (pendingVehicleId != null) pendingVehicleId = null else requestBack()
    }

    LaunchedEffect(repository, owner, session.token, offline) {
        loading = true
        serverConfirmedEmpty = false
        try {
            repository.read(owner)?.let { editor = editor.loaded(it); profileReady = true }
            if (!offline) {
                val fresh = repository.refresh(owner, session.token)
                editor = editor.loaded(fresh)
                serverConfirmedEmpty = fresh.profile.isEmptyForOnboarding
                profileReady = true
            }
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) { scope.launch { snackbars.showSnackbar(error.gsmMessage()) } }
        finally { loading = false }
    }
    LaunchedEffect(autoRepository, owner, session.token, offline) {
        try {
            autoRepository.read(owner)?.let { vehicles = it.vehicles }
            if (!offline) {
                // The regular Auto refresh may migrate legacy AppUser vehicle fields with a PUT.
                // GSM only needs options; omit that migration source so opening this form stays read-only.
                vehicles = autoRepository.refresh(session.copy(user = session.user.copy(profile = null))).vehicles
            }
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) { scope.launch { snackbars.showSnackbar(error.gsmMessage("Не удалось загрузить автомобили.")) } }
    }
    LaunchedEffect(autoRepository, owner) {
        autoRepository.changes.collect { (changedOwner, snapshot) -> if (changedOwner == owner) vehicles = snapshot.vehicles }
    }
    LaunchedEffect(editor.draft.fuelNorm) {
        val number = normInput.replace(',', '.').toDoubleOrNull() ?: 0.0
        if (number != editor.draft.fuelNorm) normInput = editor.draft.fuelNorm.takeUnless { it == 0.0 }?.toString().orEmpty()
    }

    LaunchedEffect(editor.draft.defaultStartOdometer) {
        if (!editor.isEdited && odometerInput.toIntOrNull() != editor.draft.defaultStartOdometer) odometerInput = editor.draft.defaultStartOdometer.toString()
    }

    val draft = editor.draft
    val enabled = !saving && !importing && !loading && !offline && profileReady
    Scaffold(modifier = Modifier.background(appScreenBackground()), containerColor = androidx.compose.ui.graphics.Color.Transparent,
        topBar = {
            TopAppBar(colors = appTopBarColors(),
                title = { Text("ГСМ профиль") },
                navigationIcon = { IconButton(onClick = requestBack, enabled = !saving) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Назад") } },
                actions = {
                    TextButton(enabled = enabled, onClick = {
                        if (enabled) scope.launch {
                            saving = true
                            val submittedRevision = editor.revision
                            try {
                                if (normInput.replace(',', '.').toDoubleOrNull()?.let { it.isFinite() && it > 0 } != true)
                                    throw GsmProfileFailure.Validation("Введите положительную норму топлива.")
                                if (odometerInput.toIntOrNull()?.let { it >= 0 } != true)
                                    throw GsmProfileFailure.Validation("Введите стартовый одометр целым неотрицательным числом.")
                                val saved = repository.saveWithVehicle(session, editor.draft, autoRepository)
                                if (imported.sources.isNotEmpty()) {
                                    try { auth.fillProfileFromGsm(saved.profile, owner) }
                                    catch (error: CancellationException) { throw error }
                                    catch (error: Exception) {
                                        throw GsmXlsxFailure("ГСМ и авто сохранены, но профиль LumaWork не заполнен. Повторите «Сохранить»: ${error.message.orEmpty()}")
                                    }
                                }
                                editor = editor.saved(saved, submittedRevision)
                                drafts.edit().remove(owner).apply()
                                // Consumers can update their local allowed-fuel list from the confirmed profile.
                                currentOnSaved(saved.profile)
                                currentOnBack()
                            } catch (error: CancellationException) { throw error }
                            catch (error: Exception) {
                                repository.read(owner)?.let { persisted ->
                                    editor = editor.loaded(persisted)
                                    serverConfirmedEmpty = persisted.profile.isEmptyForOnboarding
                                }
                                snackbars.showSnackbar(error.gsmMessage())
                            }
                            finally { saving = false }
                        }
                    }, modifier = Modifier.semantics { contentDescription = "Сохранить ГСМ профиль" }) {
                        if (saving) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Сохранить")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbars) },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).imePadding(),
            contentPadding = PaddingValues(horizontal = AppContentHorizontalPadding, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (loading || importing) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (offline) item {
                Text(if (profileReady) "Офлайн. Доступны сохранённые данные." else "ГСМ профиль ещё не сохранён на этом устройстве.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Можно загрузить XLSX отчёт за предыдущий месяц или заполнить поля вручную.", style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(enabled = enabled && serverConfirmedEmpty, onClick = { if (editor.isEdited) confirmReplace = true else openPicker() }) { Text("Загрузить XLSX за предыдущий месяц") }
                    if (imported.sources.isNotEmpty()) {
                        Text("Проверьте все поля. Данные сохранятся в аккаунте только после нажатия «Сохранить».", style = MaterialTheme.typography.bodyMedium)
                        Text("При сохранении ФИО и должность дополнят пустые поля профиля LumaWork. Город переносится только при явном указании в адресе; заполненные поля сохранятся.", style = MaterialTheme.typography.bodySmall)
                        val city = draft.fillingUserProfile(UserProfile()).city
                        if (city != null) Text("Город из адреса: $city", style = MaterialTheme.typography.bodySmall)
                        imported.notes.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        TextButton(onClick = { sourcesOpen = !sourcesOpen }) { Text(if (sourcesOpen) "Скрыть источники" else "Источники полей XLSX") }
                        if (sourcesOpen) imported.sources.forEach { (field, source) -> Text("$field: $source", style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
            item {
                GsmVehicleCard(
                    selected = draft.matchingVehicles(vehicles).singleOrNull(), profile = draft, vehicles = vehicles, enabled = enabled,
                    images = images, onSelect = { pendingVehicleId = it.id }, onAdd = onAddVehicle,
                )
            }
            item { GsmTextField("Марка и модель для отчёта", draft.carModel, enabled && draft.vehicleId == null) { value -> edit { it.copy(carModel = value) } } }
            item { GsmTextField("Госномер для отчёта", draft.licensePlate, enabled && draft.vehicleId == null) { value -> edit { it.copy(licensePlate = value) } } }
            item { GsmProjectSelector("Проект POS", editor.snapshot.projects, draft.posProjectId, enabled, false) { value -> edit { it.copy(posProjectId = value) } } }
            item { GsmProjectSelector("Проект АРМ", editor.snapshot.projects, draft.armProjectId, enabled, true) { value -> edit { it.copy(armProjectId = value) } } }
            item { GsmTextField("ФИО сотрудника", draft.employeeFullName, enabled) { value -> edit { it.copy(employeeFullName = value) } } }
            item { GsmTextField("Короткое имя", draft.employeeShortName, enabled) { value -> edit { it.copy(employeeShortName = value) } } }
            item { GsmTextField("Имя для подписи", draft.employeeReportSignName, enabled) { value -> edit { it.copy(employeeReportSignName = value) } } }
            item { GsmTextField("Уполномоченный ФИО", draft.authorizedFullName, enabled) { value -> edit { it.copy(authorizedFullName = value) } } }
            item { GsmTextField("Уполномоченный кратко", draft.authorizedShortName, enabled) { value -> edit { it.copy(authorizedShortName = value) } } }
            item { GsmTextField("Должность", draft.employeeJobTitle, enabled) { value -> edit { it.copy(employeeJobTitle = value) } } }
            item { GsmTextField("Компания", draft.employeeCompany, enabled) { value -> edit { it.copy(employeeCompany = value) } } }
            item { GsmTextField("Адрес", draft.employeeAddress, enabled) { value -> edit { it.copy(employeeAddress = value) } } }
            item { GsmPhoneField(draft.employeePhone, enabled) { value -> edit { it.copy(employeePhone = value) } } }
            item { GsmTextField("Водительское удостоверение", draft.driverLicenseNumber, enabled) { value -> edit { it.copy(driverLicenseNumber = value) } } }
            item { GsmTextField("Топливная карта", draft.fuelCardNumber, enabled) { value -> edit { it.copy(fuelCardNumber = value) } } }
            item {
                GsmChoiceMenu("Типы топлива", draft.fuelTypes.joinToString(", ").ifEmpty { "Выбрать типы" }, enabled) { close ->
                    editor.snapshot.fuelOptions.forEach { type ->
                        DropdownMenuItem(text = { Text(type) },
                            leadingIcon = { if (type in draft.fuelTypes) Icon(Icons.Outlined.Check, null) },
                            onClick = { edit { it.toggleFuelType(type) }; close() })
                    }
                }
            }
            item {
                AppTextField(odometerInput, { value ->
                    odometerInput = value
                    edit { it.copy(defaultStartOdometer = value.toIntOrNull() ?: 0) }
                }, "Стартовый одометр, км", Modifier.fillMaxWidth(), enabled = enabled && serverConfirmedEmpty,
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                Text("Конечный одометр предыдущего месяца (R8C31 / AE8). Сервер принимает это значение при создании профиля.", style = MaterialTheme.typography.bodySmall)
            }
            item { GsmReportMonth(draft.reportStartMonth, enabled) { value -> edit { it.copy(reportStartMonth = value) } } }
            item {
                AppTextField(normInput, { value ->
                    normInput = value
                    edit { it.copy(fuelNorm = value.replace(',', '.').toDoubleOrNull()?.takeIf(Double::isFinite) ?: 0.0) }
                }, "Норма топлива", Modifier.fillMaxWidth(), enabled = enabled, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            }
        }
    }

    if (confirmReplace) AlertDialog(onDismissRequest = { confirmReplace = false }, title = { Text("Заменить черновик данными XLSX?") },
        text = { Text("Текущие несохранённые поля будут заменены после успешного чтения файла.") },
        confirmButton = { TextButton(onClick = { confirmReplace = false; openPicker() }) { Text("Выбрать файл") } },
        dismissButton = { TextButton(onClick = { confirmReplace = false }) { Text("Отмена") } })

    val pending = vehicles.firstOrNull { it.id == pendingVehicleId }
    if (pending != null) AlertDialog(
        onDismissRequest = { pendingVehicleId = null },
        title = { Text("Изменить автомобиль для ГСМ-отчёта?") },
        text = { Text("Выбор в карусели и назначение основного авто не меняют рабочий автомобиль автоматически.") },
        confirmButton = { TextButton(onClick = { edit { it.withVehicle(pending) }; pendingVehicleId = null }) { Text("Подтвердить") } },
        dismissButton = { TextButton(onClick = { pendingVehicleId = null }) { Text("Отмена") } },
    )
}

@Composable
private fun GsmVehicleCard(
    selected: Vehicle?, profile: GsmProfile, vehicles: List<Vehicle>, enabled: Boolean, images: VehicleImageRepository,
    onSelect: (Vehicle) -> Unit, onAdd: (() -> Unit)?,
) {
    val profileVehicle = profile.carModel.isNotBlank() && profile.licensePlate.isNotBlank()
    AppPanel {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Автомобиль для ГСМ-отчёта", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (selected != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                VehicleImage(selected, images, Modifier.width(104.dp).height(64.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(selected.displayName, style = MaterialTheme.typography.titleMedium)
                    Text(selected.licensePlate ?: "Номер не указан", style = MaterialTheme.typography.bodyMedium)
                }
            } else if (profileVehicle) {
                Text(profile.carModel, style = MaterialTheme.typography.titleMedium)
                Text(profile.licensePlate, style = MaterialTheme.typography.bodyMedium)
                Text("Автомобиль из ГСМ-профиля. Будет добавлен в «Авто» после нажатия «Сохранить».", style = MaterialTheme.typography.bodySmall)
            } else Text("Автомобиль не выбран", style = MaterialTheme.typography.titleMedium)
            if (vehicles.isEmpty() && !profileVehicle) {
                if (onAdd != null) AppButton(onClick = onAdd, enabled = enabled) { Text("Добавить автомобиль") }
            } else if (vehicles.isNotEmpty()) {
                GsmChoiceMenu(null, "Выбрать другой автомобиль", enabled) { close ->
                    vehicles.forEach { vehicle -> DropdownMenuItem(text = { Text(vehicle.displayName) }, onClick = { onSelect(vehicle); close() }) }
                }
                vehicles.firstOrNull { it.isPrimary && it.id != selected?.id }?.let { primary ->
                    TextButton(onClick = { onSelect(primary) }, enabled = enabled) { Text("Использовать основное авто") }
                }
            }
            Text("При выборе автомобиля его марка, модель и госномер используются в отчёте. Без привязки их можно заполнить ниже.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun GsmProjectSelector(title: String, projects: List<GsmProjectOption>, selectedId: String?, enabled: Boolean, allowClear: Boolean, onSelect: (String?) -> Unit) {
    val selected = projects.firstOrNull { it.id == selectedId }
    GsmChoiceMenu(title, selected?.let { "${it.name}\n${it.budgetCode}" } ?: "Выбрать проект", enabled && (projects.isNotEmpty() || (allowClear && selectedId != null))) { close ->
        projects.forEach { project ->
            DropdownMenuItem(text = { Column { Text(project.name); Text(project.budgetCode, style = MaterialTheme.typography.bodySmall) } },
                leadingIcon = { if (project.id == selectedId) Icon(Icons.Outlined.Check, null) }, onClick = { onSelect(project.id); close() })
        }
        if (allowClear && selectedId != null) {
            HorizontalDivider()
            DropdownMenuItem(text = { Text("Не использовать АРМ", color = MaterialTheme.colorScheme.error) }, onClick = { onSelect(null); close() })
        }
    }
}

@Composable
private fun GsmChoiceMenu(title: String?, value: String, enabled: Boolean, items: @Composable ColumnScope.(close: () -> Unit) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (title != null) Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box {
            Surface(onClick = { expanded = true }, enabled = enabled, shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(value, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else .45f))
                    Icon(Icons.Outlined.ArrowDropDown, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) { items { expanded = false } }
        }
    }
}

@Composable
private fun GsmReportMonth(value: String, enabled: Boolean, onChange: (String) -> Unit) {
    val year = value.substringBefore('-').toIntOrNull() ?: 2026
    val month = value.substringAfter('-').toIntOrNull()?.coerceIn(1, 12) ?: 4
    val locale = remember { Locale.forLanguageTag("ru-RU") }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Стартовый месяц", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.weight(1f)) {
                GsmChoiceMenu(null, Month.of(month).getDisplayName(TextStyle.FULL_STANDALONE, locale).replaceFirstChar { it.titlecase(locale) }, enabled) { close ->
                    (1..12).forEach { option -> DropdownMenuItem(text = { Text(Month.of(option).getDisplayName(TextStyle.FULL_STANDALONE, locale).replaceFirstChar { it.titlecase(locale) }) },
                        onClick = { onChange(String.format(Locale.ROOT, "%04d-%02d", year, option)); close() }) }
                }
            }
            Box(Modifier.widthIn(min = 100.dp).weight(.7f)) {
                GsmChoiceMenu(null, year.toString(), enabled) { close ->
                    (2020..LocalDate.now().year + 2).forEach { option -> DropdownMenuItem(text = { Text(option.toString()) },
                        onClick = { onChange(String.format(Locale.ROOT, "%04d-%02d", option, month)); close() }) }
                }
            }
        }
    }
}

@Composable
private fun GsmTextField(title: String, value: String, enabled: Boolean, onChange: (String) -> Unit) {
    AppTextField(value, onChange, title, Modifier.fillMaxWidth(), enabled = enabled)
}

@Composable
private fun GsmPhoneField(value: String, enabled: Boolean, onChange: (String) -> Unit) {
    var editing by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
    LaunchedEffect(value) { if (editing.text != value) editing = TextFieldValue(value, TextRange(value.length)) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Телефон", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(value = editing, onValueChange = { next ->
            if (next.text == editing.text) editing = next
            else {
                val previous = editing.text
                val prefix = previous.commonPrefixWith(next.text).length
                val suffix = previous.drop(prefix).commonSuffixWith(next.text.drop(prefix)).length
                val result = GsmPhoneFormatter.edit(previous, prefix, previous.length - suffix, next.text.substring(prefix, next.text.length - suffix))
                editing = TextFieldValue(result.text, TextRange(result.cursorOffset))
                onChange(result.text)
            }
        }, enabled = enabled, singleLine = true, placeholder = { Text("+7(XXX)XXX-XX-XX") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Телефон" })
    }
}

private fun Throwable.gsmMessage(fallback: String = "Не удалось выполнить действие с ГСМ профилем."): String =
    (this as? GsmProfileFailure)?.message ?: fallback

@kotlinx.serialization.Serializable
private data class GsmLocalDraft(val editor: GsmProfileEditor, val imported: GsmProfileImport)
