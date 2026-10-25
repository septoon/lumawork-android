package ru.lumastack.lumawork

import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import ru.lumastack.lumawork.data.notifications.AndroidRequestNotificationDelivery
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ru.lumastack.lumawork.ui.auth.AuthContent
import ru.lumastack.lumawork.ui.auth.AuthViewModel
import ru.lumastack.lumawork.ui.theme.LumaWorkTheme
import ru.lumastack.lumawork.ui.home.HomeSkeletonScreen
import ru.lumastack.lumawork.ui.update.AppUpdateHost
import ru.lumastack.lumawork.ui.update.LocalUpdateCheck
import ru.lumastack.lumawork.ui.update.UpdateViewModel

data class RequestNotificationNavigation(val requestId: String, val consume: () -> Unit)
val LocalRequestNotification = staticCompositionLocalOf<RequestNotificationNavigation?> { null }

class MainActivity : ComponentActivity() {
    private val pendingRequest = mutableStateOf<RequestNotificationNavigation?>(null)
    private fun receiveNotification(value: Intent?) {
        if (value?.action != AndroidRequestNotificationDelivery.ACTION_OPEN_REQUESTS) return
        val requestId = value.getStringExtra(AndroidRequestNotificationDelivery.EXTRA_REQUEST_ID)?.takeIf { it.isNotBlank() } ?: return
        pendingRequest.value = RequestNotificationNavigation(requestId) {
            pendingRequest.value = null
            intent?.action = null
        }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        receiveNotification(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (resources.configuration.smallestScreenWidthDp < 600) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        receiveNotification(intent)
        val app = application as LumaWorkApplication

        setContent {
            LumaWorkTheme {
                val startupContainer by produceState<AppContainer?>(null, app) {
                    // Let the lightweight loading screen reach the display before composing the shell.
                    withFrameNanos { }
                    withFrameNanos { }
                    value = withContext(Dispatchers.IO) { app.container }
                }
                val container = startupContainer
                if (container == null) {
                    HomeSkeletonScreen()
                    return@LumaWorkTheme
                }
                val viewModel: AuthViewModel = viewModel(factory = AuthViewModel.factory(container.authRepository))
                val state by viewModel.state.collectAsStateWithLifecycle()
                val updateViewModel: UpdateViewModel = viewModel()
                CompositionLocalProvider(LocalUpdateCheck provides { updateViewModel.check() }, LocalRequestNotification provides pendingRequest.value) {
                    AuthContent(
                        state = state,
                        onEmailChange = viewModel::onEmailChange,
                        onCodeChange = viewModel::onCodeChange,
                        onRequestCode = viewModel::requestCode,
                        onVerifyCode = viewModel::verifyCode,
                        onBackToEmail = viewModel::backToEmail,
                        onResetAccess = viewModel::resetAccessRevoked,
                        onLogout = viewModel::logout,
                        authRepository = container.authRepository,
                        routeRepository = container.routeRepository,
                        simpleOneRepository = container.simpleOneRepository,
                        coordinationRepository = container.coordinationRepository,
                        timeReportRepository = container.timeReportRepository,
                        backpackRepository = container.backpackRepository,
                        backpackPhotoRepository = container.backpackPhotoRepository,
                        backpackImageRepository = container.backpackImageRepository,
                        employeeRepository = container.employeeRepository,
                        workScheduleRepository = container.workScheduleRepository,
                        autoRepository = container.autoRepository,
                        vehicleImageRepository = container.vehicleImageRepository,
                        fuelRepository = container.fuelRepository,
                        wikiRepository = container.wikiRepository,
                        ftpRepository = container.ftpRepository,
                        salaryRepository = container.salaryRepository,
                        feedbackRepository = container.feedbackRepository,
                        adminRepository = container.adminRepository,
                    )
                }
                AppUpdateHost(updateViewModel)
            }
        }
    }
}
