package ru.lumastack.lumawork.ui.shell

import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import ru.lumastack.lumawork.data.auth.AuthApi
import ru.lumastack.lumawork.data.auth.AuthRepository
import ru.lumastack.lumawork.data.session.SessionStore
import ru.lumastack.lumawork.data.session.RememberedEmailStore
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import ru.lumastack.lumawork.data.route.RouteApi
import ru.lumastack.lumawork.data.coordination.CoordinationCache
import ru.lumastack.lumawork.data.coordination.CoordinationRepository
import ru.lumastack.lumawork.data.route.RouteCache
import ru.lumastack.lumawork.data.route.RouteRepository
import ru.lumastack.lumawork.data.simpleone.SimpleOneApi
import ru.lumastack.lumawork.data.simpleone.SimpleOneCache
import ru.lumastack.lumawork.data.simpleone.SimpleOneRepository
import ru.lumastack.lumawork.data.simpleone.SimpleOneSessionStore
import ru.lumastack.lumawork.data.simpleone.StoredSimpleOneSession
import ru.lumastack.lumawork.data.timereport.TimeReportCache
import ru.lumastack.lumawork.data.timereport.TimeReportRepository
import ru.lumastack.lumawork.data.backpack.BackpackCache
import ru.lumastack.lumawork.data.backpack.BackpackImageRepository
import ru.lumastack.lumawork.data.backpack.BackpackPhotoCatalogCache
import ru.lumastack.lumawork.data.backpack.BackpackPhotoRepository
import ru.lumastack.lumawork.data.backpack.BackpackRepository
import ru.lumastack.lumawork.data.employees.EmployeeCache
import ru.lumastack.lumawork.data.employees.EmployeeRepository
import ru.lumastack.lumawork.data.auto.AutoApi
import ru.lumastack.lumawork.data.auto.AutoCache
import ru.lumastack.lumawork.data.auto.AutoRepository
import ru.lumastack.lumawork.data.auto.VehicleImageRepository
import ru.lumastack.lumawork.data.fuel.FuelApi
import ru.lumastack.lumawork.data.fuel.FuelCache
import ru.lumastack.lumawork.data.fuel.FuelRepository
import ru.lumastack.lumawork.data.wiki.WikiApi
import ru.lumastack.lumawork.data.wiki.WikiCache
import ru.lumastack.lumawork.data.wiki.WikiRepository
import ru.lumastack.lumawork.data.ftp.FtpApi
import ru.lumastack.lumawork.data.ftp.FtpCache
import ru.lumastack.lumawork.data.ftp.FtpDirectoryResponse
import ru.lumastack.lumawork.data.ftp.FtpItem
import ru.lumastack.lumawork.data.ftp.FtpRepository
import ru.lumastack.lumawork.data.ftp.FtpSnapshot
import ru.lumastack.lumawork.data.salary.SalaryApi
import ru.lumastack.lumawork.data.salary.SalaryCache
import ru.lumastack.lumawork.data.salary.SalaryDraft as AndroidSalaryDraft
import ru.lumastack.lumawork.data.salary.SalaryEntry
import ru.lumastack.lumawork.data.salary.SalaryRepository
import ru.lumastack.lumawork.data.salary.SalarySnapshot
import ru.lumastack.lumawork.data.feedback.FeedbackApi
import ru.lumastack.lumawork.data.feedback.FeedbackCache
import ru.lumastack.lumawork.data.feedback.FeedbackDraft as AndroidFeedbackDraft
import ru.lumastack.lumawork.data.feedback.FeedbackImage
import ru.lumastack.lumawork.data.feedback.FeedbackImpact
import ru.lumastack.lumawork.data.feedback.FeedbackKind
import ru.lumastack.lumawork.data.feedback.FeedbackMessage
import ru.lumastack.lumawork.data.feedback.FeedbackPriority
import ru.lumastack.lumawork.data.feedback.FeedbackRepository
import ru.lumastack.lumawork.data.feedback.FeedbackSnapshot
import ru.lumastack.lumawork.data.feedback.FeedbackStatus
import ru.lumastack.lumawork.data.admin.AdminApi
import ru.lumastack.lumawork.data.admin.AdminCache
import ru.lumastack.lumawork.data.admin.AdminOverview
import ru.lumastack.lumawork.data.admin.AdminRepository
import ru.lumastack.lumawork.data.admin.AdminSnapshot
import ru.lumastack.lumawork.data.admin.AdminUser
import androidx.compose.ui.platform.LocalContext
import ru.lumastack.lumawork.domain.model.AppSession
import ru.lumastack.lumawork.domain.coordination.CoordinationRegion
import ru.lumastack.lumawork.domain.coordination.CoordinationSnapshot
import ru.lumastack.lumawork.domain.backpack.BackpackItem
import ru.lumastack.lumawork.domain.backpack.BackpackPhotoCatalog
import ru.lumastack.lumawork.domain.backpack.BackpackSnapshot
import ru.lumastack.lumawork.domain.model.AppUser
import ru.lumastack.lumawork.domain.model.UserProfile
import ru.lumastack.lumawork.domain.route.RouteDayRecord
import ru.lumastack.lumawork.domain.route.RouteSettings
import ru.lumastack.lumawork.domain.simpleone.SimpleOneRequest
import ru.lumastack.lumawork.domain.simpleone.SimpleOneSnapshot
import ru.lumastack.lumawork.domain.simpleone.SimpleOneUser
import ru.lumastack.lumawork.domain.timereport.TimeReportEntry
import ru.lumastack.lumawork.domain.timereport.TimeReportSnapshot
import ru.lumastack.lumawork.domain.employees.EmployeeSnapshot
import ru.lumastack.lumawork.domain.auto.AutoSnapshot
import ru.lumastack.lumawork.domain.auto.MaintenanceDraft
import ru.lumastack.lumawork.domain.auto.MaintenanceRecord
import ru.lumastack.lumawork.domain.auto.Vehicle
import ru.lumastack.lumawork.domain.auto.VehicleDraft
import ru.lumastack.lumawork.domain.fuel.FuelDraft
import ru.lumastack.lumawork.domain.fuel.FuelRecord
import ru.lumastack.lumawork.domain.fuel.FuelSnapshot
import ru.lumastack.lumawork.domain.wiki.WikiArticle
import ru.lumastack.lumawork.domain.wiki.WikiSearchResult
import ru.lumastack.lumawork.domain.wiki.WikiSearchSnapshot
import ru.lumastack.lumawork.ui.theme.LumaWorkTheme

@Preview(
    name = "Телефон · портрет",
    widthDp = 393,
    heightDp = 852,
    showBackground = true,
)
@Composable
private fun PhoneAppShellPreview() {
    PreviewShell()
}

@Preview(
    name = "Планшет · портрет",
    widthDp = 800,
    heightDp = 1280,
    showBackground = true,
)
@Composable
private fun TabletAppShellPreview() {
    PreviewShell()
}

@Composable
internal fun PreviewShell() {
    LumaWorkTheme {
        val context = LocalContext.current
        val session by previewAuthRepository.sessionChanges.collectAsState()
        AppShell(
            session = session ?: previewSession,
            authRepository = previewAuthRepository,
            isOffline = false,
            routeRepository = previewRouteRepository,
            simpleOneRepository = previewSimpleOneRepository,
            coordinationRepository = previewCoordinationRepository,
            timeReportRepository = previewTimeReportRepository,
            backpackRepository = previewBackpackRepository,
            backpackPhotoRepository = previewBackpackPhotoRepository,
            backpackImageRepository = rememberPreviewBackpackImageRepository(context),
            employeeRepository = previewEmployeeRepository,
            autoRepository = previewAutoRepository,
            vehicleImageRepository = rememberPreviewVehicleImageRepository(context),
            fuelRepository = previewFuelRepository,
            wikiRepository = previewWikiRepository,
            ftpRepository = previewFtpRepository,
            salaryRepository = previewSalaryRepository,
            feedbackRepository = previewFeedbackRepository,
            adminRepository = previewAdminRepository,
            onLogout = {},
        )
    }
}

@Composable
private fun rememberPreviewBackpackImageRepository(context: android.content.Context): BackpackImageRepository =
    androidx.compose.runtime.remember(context) { BackpackImageRepository(context) }

@Composable
private fun rememberPreviewVehicleImageRepository(context: android.content.Context): VehicleImageRepository =
    androidx.compose.runtime.remember(context) { VehicleImageRepository(context) }

private val previewSession = AppSession(
    token = "preview",
    user = AppUser(
        id = "preview-user",
        email = "engineer@lumastack.ru",
        role = "user",
        profile = UserProfile(
            firstName = "Алексей",
            lastName = "Инженеров",
            jobTitle = "Сервисный инженер",
            departmentTitle = "Техническая поддержка",
        ),
    ),
)

private val previewRouteRepository = RouteRepository(
    api = object : RouteApi {
        override suspend fun fetchDay(
            token: String,
            date: String,
            settings: RouteSettings,
            workType: ru.lumastack.lumawork.domain.route.RouteWorkType,
        ): RouteDayRecord? = null.also { previewRefresh("home") }

        override suspend fun sendDay(token: String, record: RouteDayRecord) = Unit
    },
    cache = object : RouteCache {
        override suspend fun read(userId: String, date: String, workType: ru.lumastack.lumawork.domain.route.RouteWorkType): RouteDayRecord? = null
        override suspend fun save(userId: String, record: RouteDayRecord) = Unit
    },
)

private val previewSimpleOneRepository = SimpleOneRepository(
    api = object : SimpleOneApi {
        override suspend fun login(username: String, password: String): String = "preview-auth"
        override suspend fun currentUser(authKey: String): SimpleOneUser = error("unused")
        override suspend fun activeRequests(userId: String, authKey: String): List<SimpleOneRequest> = emptyList()
        override suspend fun closedRequests(userId: String, authKey: String): List<SimpleOneRequest> = emptyList()
        override suspend fun exportClosedRequests(userId: String, authKey: String): ByteArray = error("unused")
        override suspend fun timeReportEntries(authKey: String): List<TimeReportEntry> = emptyList()
        override suspend fun exportTimeReport(userId: String, authKey: String): ByteArray = error("unused")
        override suspend fun backpackItems(authKey: String): List<BackpackItem> = emptyList()
        override suspend fun requestDetails(request: SimpleOneRequest, authKey: String): SimpleOneRequest = request
        override suspend fun coordinationRequests(
            region: CoordinationRegion,
            authKey: String,
        ): List<SimpleOneRequest> = emptyList()
        override suspend fun returnEquipmentRequests(
            userId: String,
            authKey: String,
        ): List<SimpleOneRequest> = emptyList()
    },
    sessionStore = object : SimpleOneSessionStore {
        override fun read(ownerUserId: String): StoredSimpleOneSession? = null
        override fun save(session: StoredSimpleOneSession) = Unit
        override fun clear(ownerUserId: String) = Unit
        override fun readUsername(ownerUserId: String): String = ""
        override fun saveUsername(ownerUserId: String, username: String) = Unit
    },
    cache = object : SimpleOneCache {
        override suspend fun read(ownerUserId: String): SimpleOneSnapshot? = null
        override suspend fun save(ownerUserId: String, snapshot: SimpleOneSnapshot) = Unit
    },
)

private val previewCoordinationRepository = CoordinationRepository(
    api = object : SimpleOneApi {
        override suspend fun login(username: String, password: String): String = "preview-auth"
        override suspend fun currentUser(authKey: String): SimpleOneUser = error("unused")
        override suspend fun activeRequests(userId: String, authKey: String): List<SimpleOneRequest> = emptyList()
        override suspend fun closedRequests(userId: String, authKey: String): List<SimpleOneRequest> = emptyList()
        override suspend fun exportClosedRequests(userId: String, authKey: String): ByteArray = error("unused")
        override suspend fun timeReportEntries(authKey: String): List<TimeReportEntry> = emptyList()
        override suspend fun exportTimeReport(userId: String, authKey: String): ByteArray = error("unused")
        override suspend fun backpackItems(authKey: String): List<BackpackItem> = emptyList()
        override suspend fun requestDetails(request: SimpleOneRequest, authKey: String): SimpleOneRequest = request
        override suspend fun coordinationRequests(
            region: CoordinationRegion,
            authKey: String,
        ): List<SimpleOneRequest> = emptyList()
        override suspend fun returnEquipmentRequests(
            userId: String,
            authKey: String,
        ): List<SimpleOneRequest> = emptyList()
    },
    sessionStore = object : SimpleOneSessionStore {
        override fun read(ownerUserId: String): StoredSimpleOneSession? = null
        override fun save(session: StoredSimpleOneSession) = Unit
        override fun clear(ownerUserId: String) = Unit
        override fun readUsername(ownerUserId: String): String = ""
        override fun saveUsername(ownerUserId: String, username: String) = Unit
    },
    cache = object : CoordinationCache {
        override suspend fun read(ownerUserId: String): CoordinationSnapshot? = null
        override suspend fun save(ownerUserId: String, snapshot: CoordinationSnapshot) = Unit
    },
)

private val previewTimeReportRepository = TimeReportRepository(
    api = object : SimpleOneApi {
        override suspend fun login(username: String, password: String): String = "preview-auth"
        override suspend fun currentUser(authKey: String): SimpleOneUser = error("unused")
        override suspend fun activeRequests(userId: String, authKey: String): List<SimpleOneRequest> = emptyList()
        override suspend fun closedRequests(userId: String, authKey: String): List<SimpleOneRequest> = emptyList()
        override suspend fun exportClosedRequests(userId: String, authKey: String): ByteArray = error("unused")
        override suspend fun timeReportEntries(authKey: String): List<TimeReportEntry> = emptyList()
        override suspend fun exportTimeReport(userId: String, authKey: String): ByteArray = error("unused")
        override suspend fun backpackItems(authKey: String): List<BackpackItem> = emptyList()
        override suspend fun requestDetails(request: SimpleOneRequest, authKey: String): SimpleOneRequest = request
        override suspend fun coordinationRequests(
            region: CoordinationRegion,
            authKey: String,
        ): List<SimpleOneRequest> = emptyList()
        override suspend fun returnEquipmentRequests(
            userId: String,
            authKey: String,
        ): List<SimpleOneRequest> = emptyList()
    },
    sessionStore = object : SimpleOneSessionStore {
        override fun read(ownerUserId: String): StoredSimpleOneSession? = null
        override fun save(session: StoredSimpleOneSession) = Unit
        override fun clear(ownerUserId: String) = Unit
        override fun readUsername(ownerUserId: String): String = ""
        override fun saveUsername(ownerUserId: String, username: String) = Unit
    },
    cache = object : TimeReportCache {
        override suspend fun read(ownerUserId: String): TimeReportSnapshot? = null
        override suspend fun save(ownerUserId: String, snapshot: TimeReportSnapshot) = Unit
        override suspend fun delete(ownerUserId: String) = Unit
    },
)

private val previewBackpackRepository = BackpackRepository(
    api = object : SimpleOneApi {
        override suspend fun login(username: String, password: String): String = "preview-auth"
        override suspend fun currentUser(authKey: String): SimpleOneUser = error("unused")
        override suspend fun activeRequests(userId: String, authKey: String): List<SimpleOneRequest> = emptyList()
        override suspend fun closedRequests(userId: String, authKey: String): List<SimpleOneRequest> = emptyList()
        override suspend fun exportClosedRequests(userId: String, authKey: String): ByteArray = error("unused")
        override suspend fun timeReportEntries(authKey: String): List<TimeReportEntry> = emptyList()
        override suspend fun exportTimeReport(userId: String, authKey: String): ByteArray = error("unused")
        override suspend fun backpackItems(authKey: String): List<BackpackItem> = emptyList()
        override suspend fun requestDetails(request: SimpleOneRequest, authKey: String): SimpleOneRequest = request
        override suspend fun coordinationRequests(
            region: CoordinationRegion,
            authKey: String,
        ): List<SimpleOneRequest> = emptyList()
        override suspend fun returnEquipmentRequests(
            userId: String,
            authKey: String,
        ): List<SimpleOneRequest> = emptyList()
    },
    sessionStore = object : SimpleOneSessionStore {
        override fun read(ownerUserId: String): StoredSimpleOneSession? = null
        override fun save(session: StoredSimpleOneSession) = Unit
        override fun clear(ownerUserId: String) = Unit
        override fun readUsername(ownerUserId: String): String = ""
        override fun saveUsername(ownerUserId: String, username: String) = Unit
    },
    cache = object : BackpackCache {
        override suspend fun read(ownerUserId: String): BackpackSnapshot? = null
        override suspend fun save(ownerUserId: String, snapshot: BackpackSnapshot) = Unit
    },
)

private val previewBackpackPhotoRepository = BackpackPhotoRepository(
    origin = "https://api.work.lumastack.ru",
    cache = object : BackpackPhotoCatalogCache {
        override suspend fun read(): BackpackPhotoCatalog? = BackpackPhotoCatalog()
        override suspend fun save(catalog: BackpackPhotoCatalog) = Unit
    },
)

private val previewEmployeeRepository = EmployeeRepository(
    api = object : SimpleOneApi {
        override suspend fun login(username: String, password: String): String = "preview-auth"
        override suspend fun currentUser(authKey: String): SimpleOneUser = error("unused")
        override suspend fun activeRequests(userId: String, authKey: String): List<SimpleOneRequest> = emptyList()
        override suspend fun closedRequests(userId: String, authKey: String): List<SimpleOneRequest> = emptyList()
        override suspend fun exportClosedRequests(userId: String, authKey: String): ByteArray = error("unused")
        override suspend fun timeReportEntries(authKey: String): List<TimeReportEntry> = emptyList()
        override suspend fun exportTimeReport(userId: String, authKey: String): ByteArray = error("unused")
        override suspend fun backpackItems(authKey: String): List<BackpackItem> = emptyList()
        override suspend fun requestDetails(request: SimpleOneRequest, authKey: String): SimpleOneRequest = request
        override suspend fun coordinationRequests(region: CoordinationRegion, authKey: String) = emptyList<SimpleOneRequest>()
        override suspend fun returnEquipmentRequests(userId: String, authKey: String) = emptyList<SimpleOneRequest>()
    },
    sessionStore = object : SimpleOneSessionStore {
        override fun read(ownerUserId: String): StoredSimpleOneSession? = null
        override fun save(session: StoredSimpleOneSession) = Unit
        override fun clear(ownerUserId: String) = Unit
        override fun readUsername(ownerUserId: String): String = ""
        override fun saveUsername(ownerUserId: String, username: String) = Unit
    },
    cache = object : EmployeeCache {
        override suspend fun read(ownerUserId: String): EmployeeSnapshot? = null
        override suspend fun save(ownerUserId: String, snapshot: EmployeeSnapshot) = Unit
    },
)

private val previewAutoRepository = AutoRepository(
    api = object : AutoApi {
        override suspend fun vehicles(token: String): List<Vehicle> = emptyList<Vehicle>().also { previewRefresh("auto") }
        override suspend fun createVehicle(token: String, draft: VehicleDraft): Vehicle = error("unused")
        override suspend fun updateVehicle(token: String, vehicleId: String, draft: VehicleDraft): Vehicle = error("unused")
        override suspend fun maintenance(token: String): List<MaintenanceRecord> = emptyList()
        override suspend fun createMaintenance(token: String, draft: MaintenanceDraft): MaintenanceRecord = error("unused")
        override suspend fun updateMaintenance(token: String, recordId: String, draft: MaintenanceDraft): MaintenanceRecord = error("unused")
    },
    cache = object : AutoCache {
        override suspend fun read(ownerUserId: String): AutoSnapshot? = null
        override suspend fun save(ownerUserId: String, snapshot: AutoSnapshot) = Unit
    },
)

private val previewFuelRepository = FuelRepository(
    api = object : FuelApi {
        override suspend fun records(token: String): List<FuelRecord> = emptyList<FuelRecord>().also { previewRefresh("fuel") }
        override suspend fun create(token: String, draft: FuelDraft): FuelRecord = error("unused")
        override suspend fun update(token: String, recordId: String, draft: FuelDraft): FuelRecord = error("unused")
        override suspend fun delete(token: String, recordId: String) = Unit
    },
    cache = object : FuelCache {
        override suspend fun read(ownerUserId: String): FuelSnapshot? = null
        override suspend fun save(ownerUserId: String, snapshot: FuelSnapshot) = Unit
    },
)

private val previewWikiRepository = WikiRepository(
    api = object : WikiApi {
        override suspend fun search(query: String, limit: Int): List<WikiSearchResult> = emptyList<WikiSearchResult>().also { previewRefresh("wiki") }
        override suspend fun article(id: String, maxChars: Int): WikiArticle = error("unused")
        override suspend fun isPdfAvailable(id: String): Boolean = false
        override fun pdfUrl(id: String): String = "https://wiki.lumastack.ru/pdf/$id"
    },
    cache = object : WikiCache {
        override suspend fun readSearch(ownerUserId: String): WikiSearchSnapshot? = null
        override suspend fun saveSearch(ownerUserId: String, snapshot: WikiSearchSnapshot) = Unit
        override suspend fun readArticle(articleId: String): WikiArticle? = null
        override suspend fun saveArticle(article: WikiArticle) = Unit
    },
)

private val previewFtpRepository = FtpRepository(
    api = object : FtpApi {
        override suspend fun files(token: String, path: String) = FtpDirectoryResponse(path).also { previewRefresh("ftp") }
        override suspend fun download(token: String, item: FtpItem): java.io.File = error("unused")
    },
    cache = object : FtpCache {
        override suspend fun read(ownerUserId: String): FtpSnapshot? = null
        override suspend fun save(ownerUserId: String, snapshot: FtpSnapshot) = Unit
    },
)

private val previewSalaryRepository = SalaryRepository(
    api = object : SalaryApi {
        override suspend fun records(token: String): List<SalaryEntry> = emptyList<SalaryEntry>().also { previewRefresh("salary") }
        override suspend fun create(token: String, draft: AndroidSalaryDraft): SalaryEntry = error("unused")
        override suspend fun update(token: String, id: String, draft: AndroidSalaryDraft): SalaryEntry = error("unused")
        override suspend fun delete(token: String, id: String) = Unit
    },
    cache = object : SalaryCache {
        override suspend fun read(owner: String): SalarySnapshot? = null
        override suspend fun save(owner: String, snapshot: SalarySnapshot) = Unit
    },
)

private val previewFeedbackRepository = FeedbackRepository(
    api = object : FeedbackApi {
        override suspend fun messages(token: String, admin: Boolean): List<FeedbackMessage> = emptyList<FeedbackMessage>().also { previewRefresh("feedback") }
        override suspend fun submit(token: String, draft: AndroidFeedbackDraft, images: List<FeedbackImage>): FeedbackMessage = error("unused")
        override suspend fun add(token: String, id: String, text: String): FeedbackMessage = error("unused")
        override suspend fun attachment(token: String, reportId: String, attachmentId: String, admin: Boolean): ByteArray = error("unused")
        override suspend fun updateAdmin(token: String, id: String, status: FeedbackStatus, priority: FeedbackPriority, note: String): FeedbackMessage = error("unused")
        override suspend fun retryEmail(token: String, id: String) = Unit
    },
    cache = object : FeedbackCache {
        override suspend fun read(owner: String, admin: Boolean): FeedbackSnapshot? = null
        override suspend fun save(owner: String, snapshot: FeedbackSnapshot, admin: Boolean) = Unit
    },
)

private val previewAdminRepository = AdminRepository(
    api = object : AdminApi {
        override suspend fun overview(token: String): AdminOverview = AdminOverview()
        override suspend fun users(token: String): List<AdminUser> = emptyList()
        override suspend fun block(token: String, id: String, blockedUntil: String?): AdminUser = error("unused")
        override suspend fun access(token: String, id: String, role: String, permissions: List<String>): AdminUser = error("unused")
        override suspend fun delete(token: String, id: String, email: String) = Unit
    },
    cache = object : AdminCache {
        override suspend fun read(owner: String): AdminSnapshot? = null
        override suspend fun save(owner: String, snapshot: AdminSnapshot) = Unit
    },
)

private val previewAuthRepository by lazy {
    var current = previewSession
    AuthRepository(
        api = object : AuthApi {
            override suspend fun requestCode(email: String) = Unit
            override suspend fun verifyCode(email: String, code: String) = current
            override suspend fun currentUser(token: String) = current.user
            override suspend fun logout(token: String) = Unit
            override suspend fun updateProfile(token: String, profile: UserProfile): AppUser {
                current = current.copy(user = current.user.copy(profile = profile))
                return current.user
            }
            override suspend fun uploadAvatar(token: String, bytes: ByteArray) = current.user
            override suspend fun deleteAvatar(token: String) = current.user.copy(avatarUrl = null)
        },
        sessionStore = object : SessionStore {
            override fun read() = current
            override fun save(session: AppSession) { current = session }
            override fun clear() = Unit
        },
        emailStore = object : RememberedEmailStore {
            override fun read() = current.user.email
            override fun save(email: String) = Unit
        },
    )
}

/** Fixture-only callback marker for gesture checks; never logs account data. */
private fun previewRefresh(section: String) {
    android.util.Log.d("LumaWorkPreview", "refresh:$section")
}
