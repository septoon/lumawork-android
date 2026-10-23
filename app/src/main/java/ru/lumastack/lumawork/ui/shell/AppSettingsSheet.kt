package ru.lumastack.lumawork.ui.shell

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
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
import ru.lumastack.lumawork.ui.theme.appTopBarColors
import ru.lumastack.lumawork.ui.theme.AppPanel

private enum class SettingsPage(val title: String) {
    Root("Настройки"), Appearance("Оформление"), Screens("Экраны"), Route("Маршрут"), Gsm("ГСМ профиль"), Notifications("Уведомления")
}

private enum class SettingsIcon(val color: Color, path: String) {
    Profile(Color(0xFF1A73E8), "M12,12c2.21,0 4,-1.79 4,-4s-1.79,-4 -4,-4 -4,1.79 -4,4 1.79,4 4,4zM12,14c-2.67,0 -8,1.34 -8,4v2h16v-2c0,-2.66 -5.33,-4 -8,-4z"),
    Fuel(Color(0xFF00897B), "M19.77,7.23l0.01,-0.01L16.06,3.5 15,4.56l2.11,2.11C16.17,7.03 15.5,7.93 15.5,9c0,1.38 1.12,2.5 2.5,2.5 0.36,0 0.69,-0.08 1,-0.21V18c0,0.55 -0.45,1 -1,1s-1,-0.45 -1,-1v-4.5c0,-1.1 -0.9,-2 -2,-2h-1V4c0,-1.1 -0.9,-2 -2,-2H6C4.9,2 4,2.9 4,4v16H2v2h14v-2h-2v-7h1.5v5c0,1.38 1.12,2.5 2.5,2.5s2.5,-1.12 2.5,-2.5V9c0,-0.69 -0.28,-1.32 -0.73,-1.77zM12,9H6V4h6v5zM18,10c-0.55,0 -1,-0.45 -1,-1s0.45,-1 1,-1 1,0.45 1,1 -0.45,1 -1,1z"),
    Route(Color(0xFF43A047), "M12,2C8.13,2 5,5.13 5,9c0,5.25 7,13 7,13s7,-7.75 7,-13c0,-3.87 -3.13,-7 -7,-7zM12,11.5c-1.38,0 -2.5,-1.12 -2.5,-2.5s1.12,-2.5 2.5,-2.5 2.5,1.12 2.5,2.5 -1.12,2.5 -2.5,2.5z"),
    Notifications(Color(0xFFEF6C00), "M12,22c1.1,0 2,-0.9 2,-2h-4c0,1.1 0.9,2 2,2zM18,16v-5c0,-3.07 -1.63,-5.64 -4.5,-6.32V4c0,-0.83 -0.67,-1.5 -1.5,-1.5s-1.5,0.67 -1.5,1.5v0.68C7.64,5.36 6,7.92 6,11v5l-2,2v1h16v-1l-2,-2z"),
    Screens(Color(0xFF5C6BC0), "M3,3h8v8H3zM13,3h8v5h-8zM13,10h8v11h-8zM3,13h8v8H3z"),
    Appearance(Color(0xFF8E24AA), "M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10c1.66,0 3,-1.34 3,-3 0,-0.78 -0.3,-1.49 -0.79,-2.02 -0.47,-0.53 -0.69,-0.79 -0.69,-1.25 0,-0.96 0.78,-1.73 1.73,-1.73H17c2.76,0 5,-2.24 5,-5 0,-3.87 -4.48,-7 -10,-7zM6.5,12C5.67,12 5,11.33 5,10.5S5.67,9 6.5,9 8,9.67 8,10.5 7.33,12 6.5,12zM9.5,8C8.67,8 8,7.33 8,6.5S8.67,5 9.5,5 11,5.67 11,6.5 10.33,8 9.5,8zM14.5,8C13.67,8 13,7.33 13,6.5S13.67,5 14.5,5 16,5.67 16,6.5 15.33,8 14.5,8zM17.5,12c-0.83,0 -1.5,-0.67 -1.5,-1.5S16.67,9 17.5,9 19,9.67 19,10.5 18.33,12 17.5,12z"),
    Updates(Color(0xFF039BE5), "M17,1H7C5.9,1 5,1.9 5,3v18c0,1.1 0.9,2 2,2h10c1.1,0 2,-0.9 2,-2V3c0,-1.1 -0.9,-2 -2,-2zM17,19H7V5h10v14zM16,12h-3V8h-2v4H8l4,4 4,-4z");

    val vector = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        .addPath(pathData = addPathNodes(path), fill = SolidColor(Color.White))
        .build()
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
                    SettingsPage.Gsm -> GsmProfileSettingsScreen(session, offline, auto, auth,
                        onBack = { page = SettingsPage.Root }, onAddVehicle = { addVehicle = true },
                        onSaved = { saved -> onGsmProfileSaved(); fuel.setFuelTypes(session.user.id, saved.fuelTypes.ifEmpty { listOf(saved.fuelType) }) })
                    SettingsPage.Notifications -> NotificationSettingsScreen(session, offline) { page = SettingsPage.Root }
                    SettingsPage.Route -> RouteSettingsPage(session, offline, auth) { page = SettingsPage.Root }
                    else -> {
                        BackHandler { back() }
                        Scaffold(modifier = Modifier.background(appScreenBackground()), containerColor = androidx.compose.ui.graphics.Color.Transparent, topBar = {
                            TopAppBar(colors = appTopBarColors(), title = { Text(page.title) }, navigationIcon = {
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
                                        item { SettingsLink("Профиль", SettingsIcon.Profile, !busy, onOpenProfile) }
                                        item { SettingsLink("ГСМ профиль", SettingsIcon.Fuel, !busy) { page = SettingsPage.Gsm } }
                                        item { SettingsSectionLabel("Маршрут") }
                                        item { SettingsLink("Старт и финиш", SettingsIcon.Route, !busy) { page = SettingsPage.Route } }
                                        item { SettingsSectionLabel("Приложение") }
                                        item { SettingsLink("Уведомления", SettingsIcon.Notifications, !busy) { page = SettingsPage.Notifications } }
                                        item { SettingsLink("Экраны", SettingsIcon.Screens, !busy) { page = SettingsPage.Screens } }
                                        item { SettingsLink("Оформление", SettingsIcon.Appearance, !busy) { page = SettingsPage.Appearance } }
                                        item { SettingsLink("Проверить обновления", SettingsIcon.Updates, !busy, checkUpdates) }
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
                                        item { AppPanel(shape = MaterialTheme.shapes.medium) {
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
                                            AppPanel(onClick = { appearance.onChange(mode) }, shape = MaterialTheme.shapes.medium) {
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
private fun SettingsLink(title: String, icon: SettingsIcon, enabled: Boolean, onClick: () -> Unit) {
    AppPanel(onClick = onClick, enabled = enabled, shape = MaterialTheme.shapes.medium) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(36.dp).background(icon.color.copy(alpha = if (enabled) 1f else 0.38f), CircleShape),
                contentAlignment = Alignment.Center) {
                Icon(icon.vector, null, Modifier.size(22.dp), tint = Color.White)
            }
            Text(title, Modifier.weight(1f))
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SettingsToggle(title: String, subtitle: String?, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    AppPanel(shape = MaterialTheme.shapes.medium) {
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
    Scaffold(modifier = Modifier.background(appScreenBackground()), containerColor = androidx.compose.ui.graphics.Color.Transparent, topBar = {
        TopAppBar(colors = appTopBarColors(), title = { Text("Маршрут") }, navigationIcon = {
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
            item { AppPanel(shape = MaterialTheme.shapes.medium) {
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
        Scaffold(Modifier.fillMaxSize().background(appScreenBackground()), containerColor = androidx.compose.ui.graphics.Color.Transparent, topBar = {
            TopAppBar(colors = appTopBarColors(), title = { Text("Обратная связь") }, navigationIcon = {
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
