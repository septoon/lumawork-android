package ru.lumastack.lumawork.ui.shell

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.lumastack.lumawork.R
import ru.lumastack.lumawork.data.auth.AuthRepository
import ru.lumastack.lumawork.data.auto.AutoRepository
import ru.lumastack.lumawork.data.auto.VehicleImageRepository
import ru.lumastack.lumawork.data.feedback.FeedbackRepository
import ru.lumastack.lumawork.data.fuel.FuelRepository
import ru.lumastack.lumawork.data.settings.AppSettingsPreferences
import ru.lumastack.lumawork.data.settings.DeviceSettings
import ru.lumastack.lumawork.domain.model.AppSession
import ru.lumastack.lumawork.domain.model.UserProfile
import ru.lumastack.lumawork.domain.route.RouteSettings
import ru.lumastack.lumawork.ui.adaptive.appContentWidthClass
import ru.lumastack.lumawork.ui.auto.AutoViewModel
import ru.lumastack.lumawork.ui.auto.VehicleEditor
import ru.lumastack.lumawork.ui.feedback.FeedbackRoute
import ru.lumastack.lumawork.ui.gsm.GsmProfileSettingsScreen
import ru.lumastack.lumawork.ui.notifications.NotificationSettingsScreen
import ru.lumastack.lumawork.ui.profile.ProfileAvatarEditor
import ru.lumastack.lumawork.ui.salary.salaryBiometricAvailable
import ru.lumastack.lumawork.ui.theme.AppAppearance
import ru.lumastack.lumawork.ui.theme.LocalAppAppearance
import ru.lumastack.lumawork.ui.theme.appScreenBackground

private enum class SettingsPage(val title: String) {
    Root("Настройки"), Appearance("Оформление"), Screens("Экраны"), Route("Маршрут"), Gsm("ГСМ профиль"), Notifications("Уведомления")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppSettingsSheet(session: AppSession, offline: Boolean, auth: AuthRepository,
    images: VehicleImageRepository, auto: AutoRepository, fuel: FuelRepository,
    preferences: AppSettingsPreferences, settings: DeviceSettings, destinations: List<AppDestination>,
    onDismiss: () -> Unit, onOpenProfile: () -> Unit, onLogout: () -> Unit, onGsmProfileSaved: () -> Unit = {}) {
    var page by rememberSaveable(session.user.id) { mutableStateOf(SettingsPage.Root) }
    var logoutRequested by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var addVehicle by rememberSaveable { mutableStateOf(false) }
    val snackbars = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val dispatcher = LocalOnBackPressedDispatcherOwner.current!!
    val appearance = LocalAppAppearance.current
    val checkUpdates = ru.lumastack.lumawork.ui.update.LocalUpdateCheck.current
    fun back() { if (!busy && !addVehicle) { if (page == SettingsPage.Root) onDismiss() else page = SettingsPage.Root } }
    fun avatar(action: suspend () -> Unit) {
        if (busy || offline) return
        busy = true
        scope.launch {
            try { action() } catch (error: CancellationException) { throw error }
            catch (error: Exception) { snackbars.showSnackbar(error.message ?: "Не удалось изменить фото.") }
            finally { busy = false }
        }
    }
    // Forward the dialog's system Back to the screen handlers, including their saving guards.
    Dialog(onDismissRequest = { dispatcher.onBackPressedDispatcher.onBackPressed() },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        CompositionLocalProvider(LocalOnBackPressedDispatcherOwner provides dispatcher) {
            Surface(Modifier.widthIn(max = 760.dp).fillMaxSize(), shape = RoundedCornerShape(20.dp)) {
                when (page) {
                    SettingsPage.Gsm -> GsmProfileSettingsScreen(session, offline, auto,
                        onBack = { page = SettingsPage.Root }, onAddVehicle = { addVehicle = true },
                        onSaved = { saved -> onGsmProfileSaved(); fuel.setFuelTypes(session.user.id, saved.fuelTypes.ifEmpty { listOf(saved.fuelType) }) })
                    SettingsPage.Notifications -> NotificationSettingsScreen(session, offline) { page = SettingsPage.Root }
                    SettingsPage.Route -> RouteSettingsPage(session, offline, auth) { page = SettingsPage.Root }
                    else -> {
                        BackHandler { back() }
                        Scaffold(topBar = {
                            TopAppBar(title = { Text(page.title) }, navigationIcon = {
                                if (page != SettingsPage.Root) IconButton(onClick = ::back, enabled = !busy) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад")
                                }
                            }, actions = {
                                IconButton(onClick = onDismiss, enabled = !busy) { Icon(Icons.Default.Close, "Закрыть настройки") }
                            })
                        }, snackbarHost = { SnackbarHost(snackbars) }) { padding ->
                            LazyColumn(Modifier.fillMaxSize().background(appScreenBackground()).padding(padding),
                                contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                when (page) {
                                    SettingsPage.Root -> {
                                        item("avatar") { ProfileAvatarEditor(session.user, images, !offline && !busy, busy,
                                            onUpload = { read -> avatar { auth.uploadAvatar(withContext(Dispatchers.IO) { read() }) } },
                                            onDelete = { avatar { auth.deleteAvatar() } },
                                            onError = { scope.launch { snackbars.showSnackbar(it) } }, size = 60.dp, showEmail = false) }
                                        item { SettingsSectionLabel("Профиль") }
                                        item { SettingsLink("Профиль", R.drawable.ic_nav_groups, !busy, onOpenProfile) }
                                        item { SettingsLink("ГСМ профиль", R.drawable.ic_nav_local_gas_station, !busy) { page = SettingsPage.Gsm } }
                                        item { SettingsSectionLabel("Маршрут") }
                                        item { SettingsLink("Старт и финиш", R.drawable.ic_nav_home, !busy) { page = SettingsPage.Route } }
                                        item { SettingsSectionLabel("Приложение") }
                                        item { SettingsLink("Уведомления", null, !busy) { page = SettingsPage.Notifications } }
                                        item { SettingsLink("Экраны", R.drawable.ic_nav_assignment, !busy) { page = SettingsPage.Screens } }
                                        item { SettingsLink("Оформление", null, !busy) { page = SettingsPage.Appearance } }
                                        item { SettingsLink("Проверить обновления", null, !busy, checkUpdates) }
                                        item { SettingsSectionLabel("Безопасность") }
                                        item {
                                            var available by remember { mutableStateOf(salaryBiometricAvailable(context)) }
                                            androidx.lifecycle.compose.LifecycleResumeEffect(context) {
                                                available = salaryBiometricAvailable(context)
                                                onPauseOrDispose { }
                                            }
                                            SettingsToggle("Использовать биометрию",
                                                if (available) "Для входа в раздел «Зарплата»" else "Биометрия недоступна на этом устройстве",
                                                settings.salaryBiometricEnabled, !busy && available, preferences::biometric)
                                        }
                                        item { SettingsSectionLabel("Аккаунт") }
                                        item { Surface(shape = MaterialTheme.shapes.medium) {
                                            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                Text("Электронная почта", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                Text(session.user.email)
                                            }
                                        } }
                                        item { TextButton(onClick = { logoutRequested = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                                            Text("Выйти", color = MaterialTheme.colorScheme.error)
                                        } }
                                    }
                                    SettingsPage.Appearance -> {
                                        item { SettingsSectionLabel("Режим") }
                                        items(AppAppearance.entries, key = { it.name }) { mode ->
                                            Surface(onClick = { appearance.onChange(mode) }, shape = MaterialTheme.shapes.medium) {
                                                Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                                    RadioButton(appearance.mode == mode, onClick = { appearance.onChange(mode) })
                                                    Text(mode.title)
                                                }
                                            }
                                        }
                                        item { SettingsSectionLabel("Фон") }
                                        item { SettingsToggle("Фоновый цвет", "Цветной фон экранов приложения", appearance.backgroundEnabled, true, appearance.onBackgroundChange) }
                                    }
                                    SettingsPage.Screens -> {
                                        item { Text("Выберите экраны, которые будут отображаться в меню. Должен остаться хотя бы один экран.",
                                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                        items(destinations, key = { it.name }) { destination ->
                                            val ids = destinations.map { it.name }
                                            val checked = settings.navigation.enabled(destination.name, ids)
                                            SettingsToggle(destination.title, null, checked,
                                                !checked || settings.navigation.canDisable(destination.name, ids)) {
                                                preferences.visibility(destination.name, it, ids)
                                            }
                                        }
                                    }
                                    else -> Unit
                                }
                            }
                        }
                    }
                }
            }
            if (addVehicle) {
                val vm: AutoViewModel = viewModel(key = "settings-auto-${session.user.id}-$offline",
                    factory = AutoViewModel.factory(auto, session.copy(user = session.user.copy(profile = null)), offline))
                val state by vm.state.collectAsStateWithLifecycle()
                var initialized by rememberSaveable { mutableStateOf(false) }
                LaunchedEffect(vm) {
                    if (!initialized && vm.state.value.vehicleEditor == null && !vm.state.value.isSaving) vm.addVehicle()
                    initialized = true
                }
                LaunchedEffect(session) { vm.updateSession(session.copy(user = session.user.copy(profile = null))) }
                val draft = state.vehicleEditor
                if (draft != null) VehicleEditor(draft, null, state.isSaving, state.editorErrorMessage,
                    vm::updateVehicleDraft, { vm.closeVehicleEditor(); addVehicle = false }, vm::saveVehicle)
                else if (initialized && !state.isSaving) LaunchedEffect(state.vehicleEditor) { addVehicle = false }
            }
        }
    }
    if (logoutRequested) AlertDialog(onDismissRequest = { logoutRequested = false }, title = { Text("Выйти из приложения?") },
        text = { Text("Для следующего входа понадобится код с почты.") },
        confirmButton = { TextButton(onClick = { logoutRequested = false; onLogout() }) { Text("Выйти") } },
        dismissButton = { TextButton(onClick = { logoutRequested = false }) { Text("Отмена") } })
}

@Composable
private fun SettingsLink(title: String, icon: Int?, enabled: Boolean, onClick: () -> Unit) {
    Surface(onClick = onClick, enabled = enabled, shape = MaterialTheme.shapes.medium) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (icon != null) Icon(painterResource(icon), null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
            else Icon(Icons.Default.Settings, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
            Text(title, Modifier.weight(1f))
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SettingsToggle(title: String, subtitle: String?, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Surface(shape = MaterialTheme.shapes.medium) {
        Row(Modifier.fillMaxWidth().toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text(title)
                if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked, onCheckedChange = null, enabled = enabled)
        }
    }
}

@Composable
private fun SettingsSectionLabel(title: String) {
    Text(title, Modifier.padding(start = 12.dp, top = 16.dp, bottom = 2.dp), style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RouteSettingsPage(session: AppSession, offline: Boolean, auth: AuthRepository, onBack: () -> Unit) {
    val initial = remember(session.user.id) { RouteSettings.from(session.user.profile) }
    var warehouse by rememberSaveable(session.user.id) { mutableStateOf(initial.warehouseAddress) }
    var home by rememberSaveable(session.user.id) { mutableStateOf(initial.homeAddress) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val snackbars = remember { SnackbarHostState() }
    val back = { if (!busy) onBack() }
    BackHandler { back() }
    Scaffold(topBar = {
        TopAppBar(title = { Text("Маршрут") }, navigationIcon = {
            IconButton(onClick = back, enabled = !busy) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") }
        }, actions = {
            TextButton(enabled = !busy && !offline, onClick = {
                if (!busy && !offline) {
                    busy = true
                    scope.launch {
                        try {
                            val current = auth.session?.takeIf { it.user.id == session.user.id && it.token == session.token }
                                ?: error("Сессия изменилась. Войдите заново.")
                            val profile = current.user.profile ?: UserProfile()
                            val updated = profile.copy(routeWarehouseAddress = warehouse.trim(), routeHomeAddress = home.trim())
                            if (updated != profile) auth.updateProfile(updated)
                            onBack()
                        } catch (error: CancellationException) { throw error }
                        catch (error: Exception) { snackbars.showSnackbar(error.message ?: "Не удалось сохранить адреса.") }
                        finally { busy = false }
                    }
                }
            }) { if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Сохранить") }
        })
    }, snackbarHost = { SnackbarHost(snackbars) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().background(appScreenBackground()).padding(padding).imePadding(),
            contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (offline) item { Text("Подключитесь к интернету, чтобы сохранить адреса.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            item { SettingsSectionLabel("Карты") }
            item { Surface(shape = MaterialTheme.shapes.medium) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Яндекс Карты", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                        Icon(Icons.Default.Check, "Выбрано", tint = MaterialTheme.colorScheme.primary)
                    }
                    Text("Введите пробег дня вручную перед отправкой отчёта.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } }
            item { SettingsSectionLabel("Адреса") }
            item { OutlinedTextField(warehouse, { warehouse = it }, Modifier.fillMaxWidth(), label = { Text("Склад") }, enabled = !busy && !offline) }
            item { OutlinedTextField(home, { home = it }, Modifier.fillMaxWidth(), label = { Text("Дом") }, enabled = !busy && !offline) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppFeedbackDialog(session: AppSession, offline: Boolean, repository: FeedbackRepository, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(Modifier.fillMaxSize(), topBar = {
            TopAppBar(title = { Text("Обратная связь") }, navigationIcon = {
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Закрыть обратную связь") }
            })
        }) { padding ->
            BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
                val widthClass = appContentWidthClass(maxWidth.value.roundToInt(), maxHeight.value.roundToInt(), LocalDensity.current.fontScale)
                FeedbackRoute(session, offline, repository, widthClass)
            }
        }
    }
}
