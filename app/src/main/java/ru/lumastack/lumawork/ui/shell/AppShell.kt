package ru.lumastack.lumawork.ui.shell

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import ru.lumastack.lumawork.R
import ru.lumastack.lumawork.ui.theme.AppAppearance
import ru.lumastack.lumawork.ui.theme.LocalAppAppearance
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
import ru.lumastack.lumawork.data.auto.AutoRepository
import ru.lumastack.lumawork.data.auto.VehicleImageRepository
import ru.lumastack.lumawork.data.fuel.FuelRepository
import ru.lumastack.lumawork.data.wiki.WikiRepository
import ru.lumastack.lumawork.data.ftp.FtpRepository
import ru.lumastack.lumawork.data.salary.SalaryRepository
import ru.lumastack.lumawork.data.feedback.FeedbackRepository
import ru.lumastack.lumawork.data.admin.AdminRepository
import ru.lumastack.lumawork.domain.model.AppSession
import ru.lumastack.lumawork.ui.adaptive.AppNavigationLayout
import ru.lumastack.lumawork.ui.backpack.BackpackRoute
import ru.lumastack.lumawork.ui.adaptive.appNavigationLayout
import ru.lumastack.lumawork.ui.adaptive.appWidthClass
import ru.lumastack.lumawork.ui.adaptive.appContentWidthClass
import ru.lumastack.lumawork.ui.home.HomeScreen
import ru.lumastack.lumawork.ui.coordination.CoordinationRoute
import ru.lumastack.lumawork.ui.requests.RequestsActions
import ru.lumastack.lumawork.ui.requests.RequestsScreen
import ru.lumastack.lumawork.ui.simpleone.SimpleOneUiState
import ru.lumastack.lumawork.ui.simpleone.SimpleOneViewModel
import ru.lumastack.lumawork.ui.timereport.TimeReportRoute
import ru.lumastack.lumawork.ui.employees.EmployeeRoute
import ru.lumastack.lumawork.ui.auto.AutoRoute
import ru.lumastack.lumawork.ui.fuel.FuelRoute
import ru.lumastack.lumawork.ui.wiki.WikiRoute
import ru.lumastack.lumawork.ui.ftp.FtpRoute
import ru.lumastack.lumawork.ui.salary.SalaryRoute
import ru.lumastack.lumawork.ui.feedback.FeedbackRoute
import ru.lumastack.lumawork.ui.analytics.AnalyticsScreen
import ru.lumastack.lumawork.ui.admin.AdminRoute

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
    Wiki("Вики", "База знаний", "В"),
    Ftp("FTP", "Файлы Сервионики", "F"),
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
    routeRepository: RouteRepository,
    simpleOneRepository: SimpleOneRepository,
    coordinationRepository: CoordinationRepository,
    timeReportRepository: TimeReportRepository,
    backpackRepository: BackpackRepository,
    backpackPhotoRepository: BackpackPhotoRepository,
    backpackImageRepository: BackpackImageRepository,
    employeeRepository: EmployeeRepository,
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
    val simpleOneActions = remember(simpleOneViewModel) {
        RequestsActions(
            onUsernameChange = simpleOneViewModel::updateUsername,
            onPasswordChange = simpleOneViewModel::updatePassword,
            onSignIn = simpleOneViewModel::signIn,
            onRefresh = simpleOneViewModel::refresh,
            onSignOut = simpleOneViewModel::signOut,
            onSearchQueryChange = simpleOneViewModel::updateSearchQuery,
            onStatusFilterChange = simpleOneViewModel::updateStatusFilter,
            onSortOrderChange = simpleOneViewModel::updateSortOrder,
            onModeChange = simpleOneViewModel::updateMode,
            onOpenRequest = simpleOneViewModel::openRequest,
            onCloseRequest = simpleOneViewModel::closeRequest,
        )
    }

    val contentStateHolder = rememberSaveableStateHolder()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val navigationLayout = remember(maxWidth) {
            appNavigationLayout(maxWidth.value.roundToInt())
        }
        val destinations = remember(session.user.isAdminRole) {
            AppDestination.entries.filter { !it.adminOnly || session.user.isAdminRole }
        }
        var selectedName by rememberSaveable { mutableStateOf(AppDestination.Home.name) }
        val selected = destinations.firstOrNull { it.name == selectedName } ?: AppDestination.Home
        val onSelect: (AppDestination) -> Unit = { selectedName = it.name }
        BackHandler(enabled = selected != AppDestination.Home) { onSelect(AppDestination.Home) }

        if (navigationLayout == AppNavigationLayout.Drawer) {
            CompactShell(
                contentStateHolder = contentStateHolder,
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
                onLogout = onLogout,
            )
        } else {
            TabletShell(
                contentStateHolder = contentStateHolder,
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
                usesWideSidebar = navigationLayout == AppNavigationLayout.Sidebar,
                destinations = destinations,
                selected = selected,
                onSelect = onSelect,
                onLogout = onLogout,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CompactShell(
    contentStateHolder: SaveableStateHolder,
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
    onLogout: () -> Unit,
) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(modifier = Modifier.width(320.dp)) {
                NavigationMenu(
                    session = session,
                    isOffline = isOffline,
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
        Scaffold(
            topBar = {
                AppTopBar(
                    destination = selected,
                    onOpenNavigation = { scope.launch { drawerState.open() } },
                    onLogout = onLogout,
                )
            },
        ) { padding ->
            contentStateHolder.SaveableStateProvider(selected.name) {
                DestinationContent(
                    destination = selected,
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
                    modifier = Modifier.padding(padding),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TabletShell(
    contentStateHolder: SaveableStateHolder,
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
    usesWideSidebar: Boolean,
    destinations: List<AppDestination>,
    selected: AppDestination,
    onSelect: (AppDestination) -> Unit,
    onLogout: () -> Unit,
) {
    Row(Modifier.fillMaxSize()) {
        if (usesWideSidebar) {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 1.dp,
                modifier = Modifier
                    .width(272.dp)
                    .fillMaxHeight(),
            ) {
                NavigationMenu(
                    session = session,
                    isOffline = isOffline,
                    destinations = destinations,
                    selected = selected,
                    onSelect = onSelect,
                )
            }
        } else {
            CompactNavigationRail(
                session = session,
                destinations = destinations,
                selected = selected,
                onSelect = onSelect,
            )
        }

        Scaffold(
            topBar = {
                AppTopBar(
                    destination = selected,
                    onOpenNavigation = null,
                    onLogout = onLogout,
                )
            },
            modifier = Modifier.weight(1f),
        ) { padding ->
            contentStateHolder.SaveableStateProvider(selected.name) {
                DestinationContent(
                    destination = selected,
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
                    modifier = Modifier.padding(padding),
                )
            }
        }
    }
}

@Composable
private fun CompactNavigationRail(
    session: AppSession,
    destinations: List<AppDestination>,
    selected: AppDestination,
    onSelect: (AppDestination) -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = Modifier
            .width(88.dp)
            .fillMaxHeight(),
    ) {
        LazyColumn(
            modifier = Modifier.padding(horizontal = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            item(key = "rail-account") {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = CircleShape,
                    modifier = Modifier
                        .padding(vertical = 16.dp)
                        .size(42.dp),
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            text = session.user.profile?.shortDisplayName
                                ?.firstOrNull()
                                ?.uppercase()
                                ?: session.user.email.firstOrNull()?.uppercase() ?: "L",
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
            items(destinations, key = { it.name }) { destination ->
                NavigationRailItem(
                    selected = destination == selected,
                    onClick = { onSelect(destination) },
                    icon = { DestinationMark(destination) },
                    label = {
                        Text(
                            text = destination.title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    },
                    alwaysShowLabel = false,
                )
            }
            item(key = "rail-bottom-space") {
                Spacer(Modifier.size(12.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppTopBar(
    destination: AppDestination,
    onOpenNavigation: (() -> Unit)?,
    onLogout: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    var logoutRequested by remember { mutableStateOf(false) }
    val appearance = LocalAppAppearance.current
    if (logoutRequested) {
        AlertDialog(
            onDismissRequest = { logoutRequested = false },
            title = { Text("Выйти из приложения?") },
            text = { Text("Для следующего входа понадобится код с почты.") },
            confirmButton = { TextButton(onClick = { logoutRequested = false; onLogout() }) { Text("Выйти") } },
            dismissButton = { TextButton(onClick = { logoutRequested = false }) { Text("Отмена") } },
        )
    }
    TopAppBar(
        title = {
            Column {
                Text(
                    text = destination.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    text = destination.subtitle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        },
        navigationIcon = {
            if (onOpenNavigation != null) {
                IconButton(onClick = onOpenNavigation) {
                    Icon(Icons.Default.Menu, contentDescription = "Открыть меню")
                }
            }
        },
        actions = {
            IconButton(onClick = { menuExpanded = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = "Оформление и аккаунт")
            }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                Text("Оформление", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                AppAppearance.entries.forEach { mode ->
                    DropdownMenuItem(
                        text = { Text(mode.title) },
                        trailingIcon = { if (appearance.mode == mode) Icon(Icons.Default.Check, contentDescription = "Выбрано") },
                        onClick = { appearance.onChange(mode); menuExpanded = false },
                    )
                }
                HorizontalDivider()
                DropdownMenuItem(text = { Text("Выйти из аккаунта") }, onClick = {
                    menuExpanded = false
                    logoutRequested = true
                })
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
        ),
    )
}

@Composable
private fun NavigationMenu(
    session: AppSession,
    isOffline: Boolean,
    destinations: List<AppDestination>,
    selected: AppDestination,
    onSelect: (AppDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        item(key = "account") {
            AccountHeader(session, isOffline)
        }
        item(key = "divider") {
            HorizontalDivider(modifier = Modifier.padding(bottom = 8.dp))
        }
        items(destinations, key = { it.name }) { destination ->
            val isSelected = destination == selected
            Surface(
                onClick = { onSelect(destination) },
                color = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                    else androidx.compose.ui.graphics.Color.Transparent,
                contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().semantics { this.selected = isSelected },
            ) {
                Row(Modifier.heightIn(min = 46.dp).padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    DestinationMark(destination)
                    Text(destination.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium)
                }
            }
        }
        item(key = "bottom-space") {
            Spacer(Modifier.size(12.dp))
        }
    }
}

@Composable
private fun AccountHeader(session: AppSession, isOffline: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = CircleShape,
            modifier = Modifier.size(42.dp),
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = session.user.profile?.shortDisplayName
                        ?.firstOrNull()
                        ?.uppercase()
                        ?: session.user.email.firstOrNull()?.uppercase() ?: "L",
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = session.user.profile?.shortDisplayName ?: "Инженер",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = if (isOffline) "Локальный режим" else session.user.email,
                color = if (isOffline) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
            )
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
        AppDestination.Salary -> R.drawable.ic_nav_account_balance_wallet
        AppDestination.Requests -> R.drawable.ic_nav_assignment
        AppDestination.Coordination -> R.drawable.ic_nav_assignment_ind
        AppDestination.TimeReport -> R.drawable.ic_nav_schedule
        AppDestination.Analytics -> R.drawable.ic_nav_bar_chart
        AppDestination.Feedback -> R.drawable.ic_nav_feedback
        AppDestination.Admin -> R.drawable.ic_nav_admin_panel_settings
    }
    Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(24.dp))
}

@Composable
private fun DestinationContent(
    destination: AppDestination,
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
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val fontScale = LocalDensity.current.fontScale
        val contentWidthClass = remember(maxWidth, maxHeight, fontScale) {
            appContentWidthClass(maxWidth.value.roundToInt(), maxHeight.value.roundToInt(), fontScale)
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
            )
            AppDestination.Requests -> RequestsScreen(
                state = simpleOneState,
                widthClass = contentWidthClass,
                actions = simpleOneActions,
            )
            AppDestination.Coordination -> CoordinationRoute(
                ownerUserId = session.user.id,
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
            AppDestination.Wiki -> WikiRoute(
                ownerUserId = session.user.id,
                startOffline = isOffline,
                repository = wikiRepository,
                widthClass = contentWidthClass,
            )
            AppDestination.Ftp -> FtpRoute(
                session = session,
                startOffline = isOffline,
                repository = ftpRepository,
                widthClass = contentWidthClass,
            )
            AppDestination.Salary -> SalaryRoute(
                session = session,
                startOffline = isOffline,
                repository = salaryRepository,
                widthClass = contentWidthClass,
            )
            AppDestination.Analytics -> AnalyticsScreen(
                state = simpleOneState,
                widthClass = contentWidthClass,
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
