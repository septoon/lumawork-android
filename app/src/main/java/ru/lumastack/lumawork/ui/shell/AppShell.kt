package ru.lumastack.lumawork.ui.shell

import androidx.activity.compose.BackHandler
import ru.lumastack.lumawork.ui.profile.ProfileAvatar
import ru.lumastack.lumawork.ui.profile.ProfileRoute
import androidx.compose.foundation.background
import androidx.compose.material.icons.filled.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import ru.lumastack.lumawork.ui.workschedule.WorkScheduleRoute
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import ru.lumastack.lumawork.data.settings.AppSettingsPreferences
import ru.lumastack.lumawork.ui.salary.LocalSalaryBiometricEnabled
import ru.lumastack.lumawork.ui.notifications.RequestNotificationsHost
import ru.lumastack.lumawork.LocalRequestNotification
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import ru.lumastack.lumawork.R
import ru.lumastack.lumawork.ui.theme.AppSegmentedControl
import ru.lumastack.lumawork.domain.simpleone.RequestsMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import ru.lumastack.lumawork.data.route.RouteRepository
import ru.lumastack.lumawork.data.coordination.CoordinationRepository
import ru.lumastack.lumawork.data.backpack.BackpackImageRepository
import ru.lumastack.lumawork.data.backpack.BackpackPhotoRepository
import ru.lumastack.lumawork.data.backpack.BackpackRepository
import ru.lumastack.lumawork.data.simpleone.SimpleOneRepository
import ru.lumastack.lumawork.data.timereport.TimeReportRepository
import ru.lumastack.lumawork.data.employees.EmployeeRepository
import ru.lumastack.lumawork.data.workschedule.WorkScheduleRepository
import ru.lumastack.lumawork.data.auto.AutoRepository
import ru.lumastack.lumawork.data.auto.VehicleImageRepository
import ru.lumastack.lumawork.data.fuel.FuelRepository
import ru.lumastack.lumawork.data.wiki.WikiRepository
import ru.lumastack.lumawork.data.ftp.FtpRepository
import ru.lumastack.lumawork.data.salary.SalaryRepository
import ru.lumastack.lumawork.data.feedback.FeedbackRepository
import ru.lumastack.lumawork.data.admin.AdminRepository
import ru.lumastack.lumawork.domain.model.AppSession
import ru.lumastack.lumawork.ui.backpack.BackpackRoute
import ru.lumastack.lumawork.ui.adaptive.appContentWidthClass
import ru.lumastack.lumawork.ui.home.HomeScreen
import ru.lumastack.lumawork.ui.home.RouteViewModel
import ru.lumastack.lumawork.domain.route.RouteWorkType
import ru.lumastack.lumawork.ui.coordination.CoordinationRoute
import ru.lumastack.lumawork.ui.requests.RequestsActions
import ru.lumastack.lumawork.ui.requests.RequestsRoute
import ru.lumastack.lumawork.ui.simpleone.SimpleOneUiState
import ru.lumastack.lumawork.ui.simpleone.SimpleOneViewModel
import ru.lumastack.lumawork.ui.timereport.TimeReportRoute
import ru.lumastack.lumawork.ui.employees.EmployeeRoute
import ru.lumastack.lumawork.ui.auto.AutoRoute
import ru.lumastack.lumawork.ui.fuel.FuelRoute
import ru.lumastack.lumawork.ui.assistant.AssistantWorkspaceRoute
import ru.lumastack.lumawork.ui.ftp.FtpRoute
import ru.lumastack.lumawork.ui.tools.ToolsRoute
import ru.lumastack.lumawork.ui.salary.SalaryRoute
import ru.lumastack.lumawork.ui.feedback.FeedbackRoute
import ru.lumastack.lumawork.ui.analytics.AnalyticsScreen
import ru.lumastack.lumawork.ui.admin.AdminRoute
import ru.lumastack.lumawork.ui.theme.appScreenBackground

enum class AppDestination(
    val title: String,
    val subtitle: String,
    val mark: String,
    val adminOnly: Boolean = false,
) {
    Home("Главная", "Маршрут инженера", "Г"),
    Backpack("Мой рюкзак", "Оборудование ЗИП", "Р"),
    Employees("Сотрудники", "Команда и контакты", "С"),
    Maintenance("Авто", "Автомобили и обслуживание", "А"),
    Fuel("Топливо", "Лимиты и заправки", "Т"),
    Wiki("Помощник", "Помощник и база знаний", "П"),
    Ftp("FTP", "Файлы Сервионики", "F"),
    Tools("Инструменты", "USB-диагностика терминалов", "И"),
    Salary("Зарплата", "Выплаты и документы", "₽"),
    Requests("Заявки", "SimpleOne и архив", "З"),
    Coordination("Координация", "Инженеры и активные заявки", "К"),
    TimeReport("Трудозатраты", "Работа и дорога", "Ч"),
    Analytics("Аналитика", "Сводка заявок", "Д"),
    Feedback("Обратная связь", "Предложения и проблемы", "О"),
    Admin("Админка", "Контроль приложения и сервера", "У", adminOnly = true),
}

@Composable
fun AppShell(
    session: AppSession,
    isOffline: Boolean,
    authRepository: ru.lumastack.lumawork.data.auth.AuthRepository,
    routeRepository: RouteRepository,
    simpleOneRepository: SimpleOneRepository,
    coordinationRepository: CoordinationRepository,
    timeReportRepository: TimeReportRepository,
    backpackRepository: BackpackRepository,
    backpackPhotoRepository: BackpackPhotoRepository,
    backpackImageRepository: BackpackImageRepository,
    employeeRepository: EmployeeRepository,
    workScheduleRepository: WorkScheduleRepository,
    autoRepository: AutoRepository,
    vehicleImageRepository: VehicleImageRepository,
    fuelRepository: FuelRepository,
    wikiRepository: WikiRepository,
    ftpRepository: FtpRepository,
    salaryRepository: SalaryRepository,
    feedbackRepository: FeedbackRepository,
    adminRepository: AdminRepository,
    onLogout: () -> Unit,
) {
    val simpleOneViewModel: SimpleOneViewModel = viewModel(
        key = "simpleone-${session.user.id}-$isOffline",
        factory = SimpleOneViewModel.factory(simpleOneRepository, session.user.id, isOffline),
    )
    val simpleOneState by simpleOneViewModel.state.collectAsStateWithLifecycle()
    val protectedRevision by ftpRepository.access.changes.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(protectedRevision) {
        if (simpleOneState.isAuthorized && !ftpRepository.access.isAuthorized(session.user.id)) simpleOneViewModel.signOut()
    }
    val groupClosed = coordinationRepository.groupClosedRepository
    val groupClosedRegion = ru.lumastack.lumawork.domain.coordination.coordinationRegionForProfileCity(session.user.profile?.city)
    androidx.compose.runtime.LaunchedEffect(session.user.id, simpleOneState.snapshot.currentUser?.sysId, simpleOneState.isAuthorized, simpleOneState.isInitializing, isOffline, groupClosedRegion) {
        if (!simpleOneState.isInitializing && groupClosed != null) {
            if (!simpleOneState.isAuthorized || simpleOneState.snapshot.currentUser?.sysId == null || groupClosedRegion == null) groupClosed.deactivate()
            else if (groupClosed.state.value.didLoadCache) groupClosed.activate(session.user.id,
                simpleOneState.snapshot.currentUser!!.sysId, true, groupClosedRegion, isOffline)
        }
    }
    androidx.compose.runtime.LaunchedEffect(simpleOneState.isAuthorized, simpleOneState.isInitializing) {
        if (!simpleOneState.isInitializing && !simpleOneState.isAuthorized) {
            ftpRepository.transfers?.revokeUnauthorized()
        }
    }
    val shellLifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    androidx.compose.runtime.LaunchedEffect(groupClosed, shellLifecycle, isOffline, simpleOneState.isAuthorized, groupClosedRegion) {
        shellLifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
            if (!isOffline && simpleOneState.isAuthorized && groupClosed?.state?.value?.didLoadCache == true) groupClosed.refresh()
            kotlinx.coroutines.awaitCancellation()
        }
    }

    val simpleOneActions = remember(simpleOneViewModel) {
        RequestsActions(
            onUsernameChange = simpleOneViewModel::updateUsername,
            onPasswordChange = simpleOneViewModel::updatePassword,
            onSignIn = simpleOneViewModel::signIn,
            onRefresh = simpleOneViewModel::refresh,
            onRefreshActive = simpleOneViewModel::refreshActiveRequests,
            onRefreshAnalytics = simpleOneViewModel::refreshAnalytics,
            onSignOut = simpleOneViewModel::signOut,
            onSearchQueryChange = simpleOneViewModel::updateSearchQuery,
            onStatusFilterChange = simpleOneViewModel::updateStatusFilter,
            onSortOrderChange = simpleOneViewModel::updateSortOrder,
            onModeChange = simpleOneViewModel::updateMode,
            onOpenRequest = simpleOneViewModel::openRequest,
            onCloseRequest = simpleOneViewModel::closeRequest,
            onInitialLoad = simpleOneViewModel::initialLoad,
            onDismissConnection = simpleOneViewModel::dismissConnection,
            onRefreshClosed = simpleOneViewModel::refreshClosedRequests,
            onImportArchive = simpleOneViewModel::importArchive,
            onExportArchive = simpleOneViewModel::exportArchive,
            onCleanupArchive = simpleOneViewModel::cleanupArchive,
            browserAuthKey = simpleOneViewModel::browserAuthKey,
            onResolveCurrentUser = simpleOneViewModel::ensureCurrentUser,
            onSignInIdentityOnly = simpleOneViewModel::signInIdentityOnly,
        )
    }

    var profileOpen by rememberSaveable(session.user.id) { mutableStateOf(false) }
    var profileEditing by rememberSaveable(session.user.id) { mutableStateOf(false) }
    var settingsOpen by rememberSaveable(session.user.id) { mutableStateOf(false) }
    var feedbackOpen by rememberSaveable(session.user.id) { mutableStateOf(false) }
    val openProfile = { profileEditing = false; profileOpen = true }
    val contentStateHolder = rememberSaveableStateHolder()
    val assistantModels = remember(session.user.id, session.token, isOffline, simpleOneState.isAuthorized, simpleOneState.snapshot.sessionFingerprint, protectedRevision) {
        object : androidx.lifecycle.ViewModelStoreOwner { override val viewModelStore = androidx.lifecycle.ViewModelStore() }
    }
    DisposableEffect(assistantModels) { onDispose { assistantModels.viewModelStore.clear() } }
    val context = LocalContext.current
    val preferences = remember(context) { AppSettingsPreferences(context) }
    DisposableEffect(preferences) { onDispose { preferences.close() } }
    val settings by preferences.state.collectAsStateWithLifecycle()
    val availableDestinations = remember(session.user.isAdminRole) {
        AppDestination.entries.filter { (!it.adminOnly || session.user.isAdminRole) && it != AppDestination.Feedback }
    }
    val availableIds = availableDestinations.map { it.name }
    val visibleIds = settings.navigation.visible(availableIds)
    val destinations = availableDestinations.filter { it.name in visibleIds }
    var selectedName by rememberSaveable(session.user.id) { mutableStateOf(destinations.first().name) }
    // Visibility filters the menu; internal actions can still open an available hidden screen.
    val selected = availableDestinations.firstOrNull { it.name == selectedName } ?: destinations.first()
    androidx.compose.runtime.LaunchedEffect(selected, shellLifecycle, isOffline, simpleOneViewModel) {
        if (selected == AppDestination.Requests && !isOffline) {
            shellLifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                simpleOneViewModel.runAutomaticClosedSync()
            }
        }
    }
    val navigationConfiguration = availableIds.joinToString(",") + ";" + visibleIds.joinToString(",")
    var appliedNavigationConfiguration by rememberSaveable(session.user.id) { mutableStateOf(navigationConfiguration) }
    androidx.compose.runtime.LaunchedEffect(navigationConfiguration) {
        // A restored internal route may be hidden in the menu. Normalize it only after an actual settings/role change.
        val allowed = if (appliedNavigationConfiguration == navigationConfiguration) availableIds else visibleIds
        if (selectedName !in allowed) selectedName = destinations.first().name
        appliedNavigationConfiguration = navigationConfiguration
    }
    val onSelect: (AppDestination) -> Unit = { selectedName = it.name }
    val home = destinations.firstOrNull { it == AppDestination.Home } ?: destinations.first()
    BackHandler(enabled = selected != home) { onSelect(home) }
    ru.lumastack.lumawork.ui.gsm.GsmProfileOnboarding(session, isOffline, autoRepository, fuelRepository, authRepository,
        onProfileSaved = { routeRepository.gsmProfileSaved(session.user.id) })
    RequestNotificationsHost(session, isOffline, simpleOneState.snapshot, simpleOneState.isAuthorized)
    val notification = LocalRequestNotification.current
    androidx.compose.runtime.LaunchedEffect(notification?.requestId, session.user.id, simpleOneState.isAuthorized, isOffline) {
        if (notification == null || !simpleOneState.isAuthorized) return@LaunchedEffect
        onSelect(AppDestination.Requests)
        simpleOneViewModel.updateMode(RequestsMode.Active)
        val cached = simpleOneViewModel.state.value.activeRequests.firstOrNull { it.id == notification.requestId }
        val request = cached ?: if (!isOffline) {
            simpleOneViewModel.refreshActiveRequests()
            kotlinx.coroutines.withTimeoutOrNull(15_000) {
                simpleOneViewModel.state.map { state -> state.activeRequests.firstOrNull { it.id == notification.requestId } }.filterNotNull().first()
            }
        } else null
        request?.let(simpleOneViewModel::openRequest)
        notification.consume()
    }

    CompositionLocalProvider(LocalSalaryBiometricEnabled provides settings.salaryBiometricEnabled) {
        AppDrawerShell(
            contentStateHolder = contentStateHolder,
            assistantModels = assistantModels,
            session = session,
            isOffline = isOffline,
            routeRepository = routeRepository,
            simpleOneRepository = simpleOneRepository,
            coordinationRepository = coordinationRepository,
            timeReportRepository = timeReportRepository,
            backpackRepository = backpackRepository,
            backpackPhotoRepository = backpackPhotoRepository,
            backpackImageRepository = backpackImageRepository,
            employeeRepository = employeeRepository,
            workScheduleRepository = workScheduleRepository,
            autoRepository = autoRepository,
            vehicleImageRepository = vehicleImageRepository,
            fuelRepository = fuelRepository,
            wikiRepository = wikiRepository,
            ftpRepository = ftpRepository,
            salaryRepository = salaryRepository,
            feedbackRepository = feedbackRepository,
            adminRepository = adminRepository,
            simpleOneState = simpleOneState,
            simpleOneActions = simpleOneActions,
            destinations = destinations,
            selected = selected,
            onSelect = onSelect,
            onOpenProfile = openProfile,
            onOpenSettings = { settingsOpen = true },
            onOpenFeedback = { feedbackOpen = true },
        )
        if (settingsOpen) AppSettingsSheet(
            session = session, offline = isOffline, auth = authRepository, images = vehicleImageRepository,
            auto = autoRepository, fuel = fuelRepository, preferences = preferences, settings = settings, destinations = availableDestinations,
            onDismiss = { settingsOpen = false },
            onOpenProfile = { profileEditing = true; profileOpen = true },
            onLogout = { settingsOpen = false; onLogout() },
            onGsmProfileSaved = { routeRepository.gsmProfileSaved(session.user.id) },
        )
        if (profileOpen) ProfileRoute(
            session = session,
            offline = isOffline,
            auth = authRepository,
            auto = autoRepository,
            images = vehicleImageRepository,
            simpleOne = simpleOneRepository,
            liveArchive = simpleOneState.closedArchive,
            isArchiveLoading = simpleOneState.isClosedLoading,
            startInEditor = profileEditing,
            onDismiss = { profileOpen = false },
        )
        if (feedbackOpen) AppFeedbackDialog(
            session = session,
            offline = isOffline,
            repository = feedbackRepository,
            onDismiss = { feedbackOpen = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppDrawerShell(
    contentStateHolder: SaveableStateHolder,
    assistantModels: androidx.lifecycle.ViewModelStoreOwner,
    session: AppSession,
    isOffline: Boolean,
    routeRepository: RouteRepository,
    simpleOneRepository: SimpleOneRepository,
    coordinationRepository: CoordinationRepository,
    timeReportRepository: TimeReportRepository,
    backpackRepository: BackpackRepository,
    backpackPhotoRepository: BackpackPhotoRepository,
    backpackImageRepository: BackpackImageRepository,
    employeeRepository: EmployeeRepository,
    workScheduleRepository: WorkScheduleRepository,
    autoRepository: AutoRepository,
    vehicleImageRepository: VehicleImageRepository,
    fuelRepository: FuelRepository,
    wikiRepository: WikiRepository,
    ftpRepository: FtpRepository,
    salaryRepository: SalaryRepository,
    feedbackRepository: FeedbackRepository,
    adminRepository: AdminRepository,
    simpleOneState: SimpleOneUiState,
    simpleOneActions: RequestsActions,
    destinations: List<AppDestination>,
    selected: AppDestination,
    onSelect: (AppDestination) -> Unit,
    onOpenProfile: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenFeedback: () -> Unit,
) {
    var assistantSection by rememberSaveable(session.user.id) { mutableStateOf("chat") }
    var workScheduleOpen by rememberSaveable(session.user.id) { mutableStateOf(false) }
    var requestedFtpPath by remember(session.user.id) { mutableStateOf<String?>(null) }
    androidx.compose.runtime.LaunchedEffect(selected, simpleOneState.isAuthorized) {
        if (selected != AppDestination.Employees || !simpleOneState.isAuthorized) workScheduleOpen = false
    }
    val groupProgress = coordinationRepository.groupClosedRepository?.state?.collectAsStateWithLifecycle()?.value
    val header = remember(selected) { AppHeaderState() }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val drawerConfiguration = androidx.compose.ui.platform.LocalConfiguration.current
    val squareDrawer = appContentWidthClass(drawerConfiguration.screenWidthDp, drawerConfiguration.screenHeightDp,
        androidx.compose.ui.platform.LocalDensity.current.fontScale) != ru.lumastack.lumawork.ui.adaptive.AppWidthClass.Compact
    val scope = rememberCoroutineScope()

    ModalNavigationDrawer(
        drawerState = drawerState,
        scrimColor = ru.lumastack.lumawork.ui.theme.AppSurfaces.scrim,
        drawerContent = {
            ModalDrawerSheet(modifier = Modifier.width(320.dp),
                drawerShape = if (squareDrawer) androidx.compose.ui.graphics.RectangleShape else androidx.compose.material3.DrawerDefaults.shape,
                drawerContainerColor = if (ru.lumastack.lumawork.ui.theme.LocalAppDarkTheme.current)
                    androidx.compose.ui.graphics.Color.Black else androidx.compose.ui.graphics.Color.White,
                drawerTonalElevation = 0.dp) {
                NavigationMenu(
                    groupArchiveLoading = groupProgress?.isLoading == true && groupProgress.ownerUserId == session.user.id && groupProgress.simpleOneUserId == simpleOneState.snapshot.currentUser?.sysId,
                    onOpenSettings = { scope.launch { drawerState.close(); onOpenSettings() } },
                    onOpenFeedback = { scope.launch { drawerState.close(); onOpenFeedback() } },
                    destinations = destinations,
                    selected = selected,
                    onSelect = { destination ->
                        onSelect(destination)
                        scope.launch { drawerState.close() }
                    },
                    modifier = Modifier.fillMaxHeight(),
                )
            }
        },
    ) {
        CompositionLocalProvider(LocalAppHeader provides header) {
            Scaffold(
                modifier = Modifier.background(header.content?.background ?: appScreenBackground()),
                containerColor = androidx.compose.ui.graphics.Color.Transparent,
                topBar = {
                    AppTopBar(
                        header = header,
                        destination = selected,
                        session = session,
                        images = vehicleImageRepository,
                        onOpenProfile = onOpenProfile,
                        onOpenNavigation = { if (workScheduleOpen) workScheduleOpen = false else scope.launch { drawerState.open() } },
                        workScheduleOpen = workScheduleOpen,
                        onOpenWorkSchedule = { workScheduleOpen = true },
                        requestsState = simpleOneState,
                        requestsActions = simpleOneActions,
                        routeRepository = routeRepository,
                        isOffline = isOffline,
                    )
                },
            ) { padding ->
                contentStateHolder.SaveableStateProvider(selected.name) {
                    DestinationContent(
                        destination = selected,
                        assistantModels = assistantModels,
                        session = session,
                        isOffline = isOffline,
                        routeRepository = routeRepository,
                        simpleOneRepository = simpleOneRepository,
                        coordinationRepository = coordinationRepository,
                        timeReportRepository = timeReportRepository,
                        backpackRepository = backpackRepository,
                        backpackPhotoRepository = backpackPhotoRepository,
                        backpackImageRepository = backpackImageRepository,
                        employeeRepository = employeeRepository,
                        workScheduleRepository = workScheduleRepository,
                        autoRepository = autoRepository,
                        vehicleImageRepository = vehicleImageRepository,
                        fuelRepository = fuelRepository,
                        wikiRepository = wikiRepository,
                        ftpRepository = ftpRepository,
                        salaryRepository = salaryRepository,
                        feedbackRepository = feedbackRepository,
                        adminRepository = adminRepository,
                        simpleOneState = simpleOneState,
                        simpleOneActions = simpleOneActions,
                        onSelect = onSelect,
                        workScheduleOpen = workScheduleOpen,
                        onCloseWorkSchedule = { workScheduleOpen = false },
                        assistantSection = assistantSection,
                        onAssistantSection = { assistantSection = it },
                        requestedFtpPath = requestedFtpPath,
                        onRequestedFtpPath = { requestedFtpPath = it },
                        modifier = Modifier.padding(padding).consumeWindowInsets(padding),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppTopBar(
    header: AppHeaderState,
    destination: AppDestination,
    session: AppSession,
    images: VehicleImageRepository,
    onOpenProfile: () -> Unit,
    onOpenNavigation: () -> Unit,
    workScheduleOpen: Boolean,
    onOpenWorkSchedule: () -> Unit,
    requestsState: SimpleOneUiState,
    requestsActions: RequestsActions,
    routeRepository: RouteRepository,
    isOffline: Boolean,
) {
    val content = header.content
    val back = if (workScheduleOpen) onOpenNavigation else header.onBack
    CenterAlignedTopAppBar(
        title = {
            when {
                workScheduleOpen -> Text("График работы", maxLines = 1, overflow = TextOverflow.Ellipsis)
                destination == AppDestination.Home -> {
                    val routeModel: RouteViewModel = viewModel(key = "route-${session.user.id}-${session.token.hashCode()}-$isOffline",
                        factory = RouteViewModel.factory(routeRepository, session, isOffline))
                    val routeState by routeModel.state.collectAsStateWithLifecycle()
                    AppSegmentedControl(titles = RouteWorkType.entries.map { it.title },
                        selectedIndex = RouteWorkType.entries.indexOf(routeState.record.workType),
                        onSelect = { routeModel.selectWorkType(RouteWorkType.entries[it]) })
                }
                destination == AppDestination.Requests && requestsState.initialArchiveReady &&
                    (requestsState.isAuthorized || requestsState.isOffline) && requestsState.selectedRequest == null -> {
                    AppSegmentedControl(
                        titles = RequestsMode.entries.map { it.title },
                        selectedIndex = RequestsMode.entries.indexOf(requestsState.mode),
                        onSelect = { requestsActions.onModeChange(RequestsMode.entries[it]) },
                    )
                }
                content?.title != null -> content.title.invoke()
                else -> Text(destination.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        navigationIcon = {
            Box(Modifier.width(48.dp), contentAlignment = Alignment.Center) {
                if (back != null) AppBackButton(back) else AppMenuButton(onOpenNavigation)
            }
        },
        actions = {
            // Equal side slots keep the title at the screen centre even without a trailing action.
            Row(Modifier.width(48.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                when {
                    destination == AppDestination.Employees && requestsState.isAuthorized && !workScheduleOpen ->
                        IconButton(onClick = onOpenWorkSchedule) { Icon(painterResource(R.drawable.ic_nav_schedule), "График работы") }
                    destination == AppDestination.Home ->
                        IconButton(onClick = onOpenProfile, modifier = Modifier.semantics { contentDescription = "Открыть профиль" }) {
                            ProfileAvatar(session.user, images)
                        }
                    content != null -> content.actions(this)
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = androidx.compose.ui.graphics.Color.Transparent,
            scrolledContainerColor = androidx.compose.ui.graphics.Color.Transparent,
            navigationIconContentColor = MaterialTheme.colorScheme.primary,
            actionIconContentColor = MaterialTheme.colorScheme.primary),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NavigationMenu(
    destinations: List<AppDestination>,
    selected: AppDestination,
    onSelect: (AppDestination) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenFeedback: () -> Unit,
    modifier: Modifier = Modifier,
    groupArchiveLoading: Boolean = false,
) {
    val dark = ru.lumastack.lumawork.ui.theme.LocalAppDarkTheme.current
    val ink = if (dark) androidx.compose.ui.graphics.Color.White else androidx.compose.ui.graphics.Color(.08f, .10f, .11f)
    Column(modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp)) {
        Box(Modifier.fillMaxWidth().height(64.dp), contentAlignment = Alignment.CenterStart) {
            Text("Инженер", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Normal,
                color = ink)
        }
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 44.dp) {
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                items(destinations, key = { it.name }) { destination ->
                    val isSelected = destination == selected
                    Surface(
                        onClick = { onSelect(destination) },
                        color = if (isSelected) ink.copy(alpha = if (dark) .13f else .08f) else androidx.compose.ui.graphics.Color.Transparent,
                        contentColor = ink,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth().semantics { this.selected = isSelected },
                    ) {
                        Row(Modifier.heightIn(min = 44.dp).padding(horizontal = 10.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            DestinationMark(destination)
                            Text(destination.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 18.sp),
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal)
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onOpenSettings,
                modifier = Modifier.size(48.dp).background(MaterialTheme.colorScheme.surfaceContainerLow, CircleShape)) {
                Icon(Icons.Default.Settings, contentDescription = "Настройки", modifier = Modifier.size(24.dp))
            }
            if (groupArchiveLoading) Row(Modifier.weight(1f).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                androidx.compose.material3.CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Text("Загрузка заявок группы", style = MaterialTheme.typography.bodySmall,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = onOpenFeedback,
                modifier = Modifier.size(48.dp).background(MaterialTheme.colorScheme.surfaceContainerLow, CircleShape)) {
                Icon(painterResource(R.drawable.ic_sidebar_ladybug), contentDescription = "Обратная связь", modifier = Modifier.size(24.dp))
            }
        }
    }
}

@Composable
private fun DestinationMark(destination: AppDestination) {
    val icon = when (destination) {
        AppDestination.Home -> R.drawable.ic_nav_home
        AppDestination.Backpack -> R.drawable.ic_nav_backpack
        AppDestination.Employees -> R.drawable.ic_nav_groups
        AppDestination.Maintenance -> R.drawable.ic_nav_directions_car
        AppDestination.Fuel -> R.drawable.ic_nav_local_gas_station
        AppDestination.Wiki -> R.drawable.ic_nav_menu_book
        AppDestination.Ftp -> R.drawable.ic_nav_folder_open
        AppDestination.Tools -> R.drawable.ic_nav_tools
        AppDestination.Salary -> R.drawable.ic_nav_account_balance_wallet
        AppDestination.Requests -> R.drawable.ic_nav_assignment
        AppDestination.Coordination -> R.drawable.ic_nav_assignment_ind
        AppDestination.TimeReport -> R.drawable.ic_nav_schedule
        AppDestination.Analytics -> R.drawable.ic_nav_bar_chart
        AppDestination.Feedback -> R.drawable.ic_nav_feedback
        AppDestination.Admin -> R.drawable.ic_nav_admin_panel_settings
    }
    Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(20.dp))
}

@Composable
private fun DestinationContent(
    destination: AppDestination,
    assistantModels: androidx.lifecycle.ViewModelStoreOwner,
    session: AppSession,
    isOffline: Boolean,
    routeRepository: RouteRepository,
    simpleOneRepository: SimpleOneRepository,
    coordinationRepository: CoordinationRepository,
    timeReportRepository: TimeReportRepository,
    backpackRepository: BackpackRepository,
    backpackPhotoRepository: BackpackPhotoRepository,
    backpackImageRepository: BackpackImageRepository,
    employeeRepository: EmployeeRepository,
    workScheduleRepository: WorkScheduleRepository,
    autoRepository: AutoRepository,
    vehicleImageRepository: VehicleImageRepository,
    fuelRepository: FuelRepository,
    wikiRepository: WikiRepository,
    ftpRepository: FtpRepository,
    salaryRepository: SalaryRepository,
    feedbackRepository: FeedbackRepository,
    adminRepository: AdminRepository,
    simpleOneState: SimpleOneUiState,
    simpleOneActions: RequestsActions,
    onSelect: (AppDestination) -> Unit,
    workScheduleOpen: Boolean,
    onCloseWorkSchedule: () -> Unit,
    assistantSection: String,
    onAssistantSection: (String) -> Unit,
    requestedFtpPath: String?,
    onRequestedFtpPath: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val fontScale = LocalDensity.current.fontScale
        val contentWidthClass = remember(maxWidth, maxHeight, fontScale) {
            appContentWidthClass(maxWidth.value.roundToInt(), maxHeight.value.roundToInt(), fontScale)
        }
        if (destination == AppDestination.Employees && workScheduleOpen) {
            WorkScheduleRoute(repository = workScheduleRepository, ownerUserId = session.user.id,
                simpleOneUserId = simpleOneState.snapshot.currentUser?.sysId.orEmpty(), isAuthorized = simpleOneState.isAuthorized,
                startOffline = isOffline, profileCity = session.user.profile?.city, onBack = onCloseWorkSchedule,
                authorizationRevision = simpleOneState.snapshot.updatedAtEpochMillis)
            return@BoxWithConstraints
        }
        when (destination) {
            AppDestination.Backpack -> BackpackRoute(
                ownerUserId = session.user.id,
                startOffline = isOffline,
                simpleOneState = simpleOneState,
                repository = backpackRepository,
                photoRepository = backpackPhotoRepository,
                imageRepository = backpackImageRepository,
                widthClass = contentWidthClass,
            )
            AppDestination.Home -> HomeScreen(
                session = session,
                isOffline = isOffline,
                routeRepository = routeRepository,
                simpleOneState = simpleOneState,
                widthClass = contentWidthClass,
                onOpenDestination = onSelect,
                onRefreshRequests = simpleOneActions.onRefreshActive,
                fuelRepository = fuelRepository,
                simpleOneRepository = simpleOneRepository,
                requestsActions = simpleOneActions,
            )
            AppDestination.Requests -> RequestsRoute(
                session = session,
                repository = simpleOneRepository,
                state = simpleOneState,
                widthClass = contentWidthClass,
                actions = simpleOneActions,
            )
            AppDestination.Coordination -> CoordinationRoute(
                ownerUserId = session.user.id,
                session = session,
                requestsActions = simpleOneActions,
                startOffline = isOffline,
                simpleOneState = simpleOneState,
                coordinationRepository = coordinationRepository,
                simpleOneRepository = simpleOneRepository,
                widthClass = contentWidthClass,
            )
            AppDestination.TimeReport -> TimeReportRoute(
                ownerUserId = session.user.id,
                startOffline = isOffline,
                simpleOneState = simpleOneState,
                repository = timeReportRepository,
                requestsActions = simpleOneActions,
                widthClass = contentWidthClass,
            )
            AppDestination.Employees -> EmployeeRoute(
                ownerUserId = session.user.id,
                startOffline = isOffline,
                simpleOneState = simpleOneState,
                repository = employeeRepository,
                widthClass = contentWidthClass,
            )
            AppDestination.Maintenance -> AutoRoute(
                session = session,
                startOffline = isOffline,
                repository = autoRepository,
                imageRepository = vehicleImageRepository,
                widthClass = contentWidthClass,
            )
            AppDestination.Fuel -> FuelRoute(
                session = session,
                startOffline = isOffline,
                repository = fuelRepository,
                routeRepository = routeRepository,
                widthClass = contentWidthClass,
            )
            AppDestination.Wiki -> SimpleOneProtectedContent(simpleOneState, ftpRepository.access.isAuthorized(session.user.id)) { AssistantWorkspaceRoute(
                session = session, offline = isOffline, wiki = wikiRepository, models = assistantModels,
                simpleOne = simpleOneRepository, backpack = backpackRepository,
                widthClass = contentWidthClass, section = assistantSection, onSection = onAssistantSection,
            ) }
            AppDestination.Tools -> ToolsRoute(
                owner = session.user.id,
                token = session.token,
                repository = ftpRepository,
                requests = simpleOneState,
                widthClass = contentWidthClass,
                onFtp = { onSelect(AppDestination.Ftp) },
                onWiki = { onAssistantSection("wiki"); onSelect(AppDestination.Wiki) },
            )
            AppDestination.Ftp -> SimpleOneProtectedContent(simpleOneState, ftpRepository.access.isAuthorized(session.user.id)) { FtpRoute(
                session = session,
                startOffline = isOffline,
                repository = ftpRepository,
                widthClass = contentWidthClass,
                models = assistantModels,
                initialPath = requestedFtpPath,
                onPathConsumed = { onRequestedFtpPath(null) },
            ) }
            AppDestination.Salary -> SalaryRoute(
                session = session,
                startOffline = isOffline,
                repository = salaryRepository,
                widthClass = contentWidthClass,
                onCancel = { onSelect(AppDestination.Home) },
            )
            AppDestination.Analytics -> AnalyticsScreen(
                state = simpleOneState,
                widthClass = contentWidthClass,
                onRefresh = simpleOneActions.onRefreshAnalytics,
            )
            AppDestination.Feedback -> FeedbackRoute(
                session = session,
                startOffline = isOffline,
                repository = feedbackRepository,
                widthClass = contentWidthClass,
            )
            AppDestination.Admin -> AdminRoute(
                session = session,
                startOffline = isOffline,
                repository = adminRepository,
                feedbackRepository = feedbackRepository,
                widthClass = contentWidthClass,
            )
        }
    }
}

@Composable
private fun SimpleOneProtectedContent(state: SimpleOneUiState, authorized: Boolean, content: @Composable () -> Unit) {
    if (!state.isInitializing && state.isAuthorized && authorized) content()
    else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("Доступ запрещен", style = MaterialTheme.typography.titleMedium)
    }
}
