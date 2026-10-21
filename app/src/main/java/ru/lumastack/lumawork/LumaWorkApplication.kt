package ru.lumastack.lumawork

import android.app.Application
import kotlinx.coroutines.*
import ru.lumastack.lumawork.data.ftp.FtpTransferJobService
import kotlinx.serialization.json.Json
import ru.lumastack.lumawork.data.auth.AuthRepository
import ru.lumastack.lumawork.data.auth.OkHttpAuthApi
import ru.lumastack.lumawork.data.auto.AutoRepository
import ru.lumastack.lumawork.data.auto.FileAutoCache
import ru.lumastack.lumawork.data.auto.OkHttpAutoApi
import ru.lumastack.lumawork.data.auto.VehicleImageRepository
import ru.lumastack.lumawork.data.backpack.BackpackImageRepository
import ru.lumastack.lumawork.data.backpack.BackpackPhotoRepository
import ru.lumastack.lumawork.data.backpack.BackpackRepository
import ru.lumastack.lumawork.data.backpack.FileBackpackCache
import ru.lumastack.lumawork.data.backpack.FileBackpackPhotoCatalogCache
import ru.lumastack.lumawork.data.coordination.CoordinationRepository
import ru.lumastack.lumawork.data.coordination.FileCoordinationCache
import ru.lumastack.lumawork.data.employees.EmployeeRepository
import ru.lumastack.lumawork.data.workschedule.WorkScheduleRepository
import ru.lumastack.lumawork.data.workschedule.FileWorkScheduleCache
import ru.lumastack.lumawork.data.fuel.FileFuelCache
import ru.lumastack.lumawork.data.fuel.FuelRepository
import ru.lumastack.lumawork.data.fuel.OkHttpFuelApi
import ru.lumastack.lumawork.data.employees.FileEmployeeCache
import ru.lumastack.lumawork.data.session.EncryptedSessionStore
import ru.lumastack.lumawork.data.session.SharedPreferencesEmailStore
import ru.lumastack.lumawork.data.route.OkHttpRouteApi
import ru.lumastack.lumawork.data.route.RouteRepository
import ru.lumastack.lumawork.data.route.SharedPreferencesRouteCache
import ru.lumastack.lumawork.data.simpleone.EncryptedSimpleOneSessionStore
import ru.lumastack.lumawork.data.simpleone.FileSimpleOneSupplementalCache
import ru.lumastack.lumawork.data.simpleone.OkHttpSimpleOneApi
import ru.lumastack.lumawork.data.simpleone.SharedPreferencesSimpleOneCache
import ru.lumastack.lumawork.data.simpleone.SimpleOneRepository
import ru.lumastack.lumawork.data.timereport.FileTimeReportCache
import ru.lumastack.lumawork.data.timereport.TimeReportRepository
import ru.lumastack.lumawork.data.wiki.FileWikiCache
import ru.lumastack.lumawork.data.wiki.OkHttpWikiApi
import ru.lumastack.lumawork.data.wiki.WikiRepository
import ru.lumastack.lumawork.data.ftp.FileFtpCache
import ru.lumastack.lumawork.data.ftp.FtpTransfers
import ru.lumastack.lumawork.data.ftp.FileFtpTransferStore
import ru.lumastack.lumawork.data.ftp.AndroidFtpTransferGateway
import ru.lumastack.lumawork.data.ftp.FtpRepository
import ru.lumastack.lumawork.data.ftp.OkHttpFtpApi
import ru.lumastack.lumawork.data.salary.FileSalaryCache
import ru.lumastack.lumawork.data.salary.OkHttpSalaryApi
import ru.lumastack.lumawork.data.salary.SalaryRepository
import ru.lumastack.lumawork.data.feedback.FeedbackRepository
import ru.lumastack.lumawork.data.feedback.FileFeedbackCache
import ru.lumastack.lumawork.data.feedback.OkHttpFeedbackApi
import ru.lumastack.lumawork.data.admin.AdminRepository
import ru.lumastack.lumawork.data.admin.FileAdminCache
import ru.lumastack.lumawork.data.admin.OkHttpAdminApi

class LumaWorkApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try { if (container.ftpTransfers.hasPendingFolderCopies()) FtpTransferJobService.schedule(this@LumaWorkApplication) }
            catch (_: Exception) { /* The FTP screen reports cache errors when opened. */ }
        }
    }
}

class AppContainer(application: Application) {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    val authRepository = AuthRepository(
        api = OkHttpAuthApi(BuildConfig.API_ORIGIN),
        sessionStore = EncryptedSessionStore(application, json),
        emailStore = SharedPreferencesEmailStore(application),
    )

    val autoRepository = AutoRepository(
        api = OkHttpAutoApi(BuildConfig.API_ORIGIN, json),
        cache = FileAutoCache(application, json),
    )
    val vehicleImageRepository = VehicleImageRepository(application)

    val routeRepository = RouteRepository(
        api = OkHttpRouteApi(BuildConfig.API_ORIGIN, json = json),
        cache = SharedPreferencesRouteCache(application, json),
    )

    val fuelRepository = FuelRepository(
        api = OkHttpFuelApi(BuildConfig.API_ORIGIN, json),
        cache = FileFuelCache(application, json),
    )

    val wikiRepository = WikiRepository(
        api = OkHttpWikiApi(BuildConfig.WIKI_API_ORIGIN, BuildConfig.WIKI_API_TOKEN, json),
        cache = FileWikiCache(application, json),
    )

    internal val ftpTransferGateway = AndroidFtpTransferGateway(application, BuildConfig.API_ORIGIN)
    val ftpTransfers = FtpTransfers(FileFtpTransferStore(application, json), ftpTransferGateway)
    val ftpRepository = FtpRepository(
        api = OkHttpFtpApi(BuildConfig.API_ORIGIN, json),
        cache = FileFtpCache(application, json),
        transfers = ftpTransfers,
    )

    val salaryRepository = SalaryRepository(
        api = OkHttpSalaryApi(BuildConfig.API_ORIGIN, json),
        cache = FileSalaryCache(application, json),
    )

    val feedbackRepository = FeedbackRepository(
        api = OkHttpFeedbackApi(BuildConfig.API_ORIGIN, json),
        cache = FileFeedbackCache(application, json),
    )

    val adminRepository = AdminRepository(
        api = OkHttpAdminApi(BuildConfig.API_ORIGIN, json),
        cache = FileAdminCache(application, json),
    )

    private val simpleOneApi = OkHttpSimpleOneApi(BuildConfig.SIMPLEONE_API_ORIGIN, json = json)
    private val simpleOneSessionStore = EncryptedSimpleOneSessionStore(application, json)

    val simpleOneRepository = SimpleOneRepository(
        api = simpleOneApi,
        sessionStore = simpleOneSessionStore,
        cache = SharedPreferencesSimpleOneCache(application, json),
        supplementalCache = FileSimpleOneSupplementalCache(application, json),
    )

    val coordinationGroupClosedRepository = ru.lumastack.lumawork.data.coordination.CoordinationGroupClosedRequestsRepository(
        simpleOneApi, simpleOneSessionStore,
        ru.lumastack.lumawork.data.coordination.FileCoordinationGroupClosedRequestsCache(application, json),
        CoroutineScope(SupervisorJob() + Dispatchers.IO),
    )
    val coordinationRepository = CoordinationRepository(
        api = simpleOneApi,
        sessionStore = simpleOneSessionStore,
        cache = FileCoordinationCache(application, json),
        groupClosedRepository = coordinationGroupClosedRepository,
    )

    val timeReportRepository = TimeReportRepository(
        api = simpleOneApi,
        sessionStore = simpleOneSessionStore,
        cache = FileTimeReportCache(application, json),
    )

    val backpackRepository = BackpackRepository(
        api = simpleOneApi,
        sessionStore = simpleOneSessionStore,
        cache = FileBackpackCache(application, json),
    )

    val backpackPhotoRepository = BackpackPhotoRepository(
        origin = BuildConfig.API_ORIGIN,
        cache = FileBackpackPhotoCatalogCache(application, json),
        equipmentCache = FileBackpackPhotoCatalogCache(application, json, "equipment-photo-catalog.json"),
        json = json,
    )

    val backpackImageRepository = BackpackImageRepository(application)

    val workScheduleRepository = WorkScheduleRepository(
        api = simpleOneApi,
        sessionStore = simpleOneSessionStore,
        cache = FileWorkScheduleCache(application, json),
    )

    val employeeRepository = EmployeeRepository(
        api = simpleOneApi,
        sessionStore = simpleOneSessionStore,
        cache = FileEmployeeCache(application, json),
    )
}
