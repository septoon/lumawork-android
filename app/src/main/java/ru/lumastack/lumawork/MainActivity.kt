package ru.lumastack.lumawork

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ru.lumastack.lumawork.ui.auth.AuthContent
import ru.lumastack.lumawork.ui.auth.AuthViewModel
import ru.lumastack.lumawork.ui.theme.LumaWorkTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as LumaWorkApplication).container

        setContent {
            LumaWorkTheme {
                val viewModel: AuthViewModel = viewModel(factory = AuthViewModel.factory(container.authRepository))
                val state by viewModel.state.collectAsStateWithLifecycle()
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
        }
    }
}
