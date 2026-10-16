package ru.lumastack.lumawork.ui.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import ru.lumastack.lumawork.ui.theme.AppButton as Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import ru.lumastack.lumawork.ui.theme.AppOutlinedButton as OutlinedButton
import ru.lumastack.lumawork.ui.theme.AppTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ru.lumastack.lumawork.ui.shell.AppShell
import ru.lumastack.lumawork.data.coordination.CoordinationRepository
import ru.lumastack.lumawork.data.backpack.BackpackImageRepository
import ru.lumastack.lumawork.data.backpack.BackpackPhotoRepository
import ru.lumastack.lumawork.data.backpack.BackpackRepository
import ru.lumastack.lumawork.data.route.RouteRepository
import ru.lumastack.lumawork.data.employees.EmployeeRepository
import ru.lumastack.lumawork.data.auto.AutoRepository
import ru.lumastack.lumawork.data.auto.VehicleImageRepository
import ru.lumastack.lumawork.data.fuel.FuelRepository
import ru.lumastack.lumawork.data.wiki.WikiRepository
import ru.lumastack.lumawork.data.ftp.FtpRepository
import ru.lumastack.lumawork.data.salary.SalaryRepository
import ru.lumastack.lumawork.data.feedback.FeedbackRepository
import ru.lumastack.lumawork.data.admin.AdminRepository
import ru.lumastack.lumawork.data.simpleone.SimpleOneRepository
import ru.lumastack.lumawork.data.timereport.TimeReportRepository
import ru.lumastack.lumawork.ui.theme.LumaBackgroundBottomDark
import ru.lumastack.lumawork.ui.theme.LumaBackgroundBottomLight
import ru.lumastack.lumawork.ui.theme.LumaBackgroundTopDark
import ru.lumastack.lumawork.ui.theme.LumaBackgroundTopLight

@Composable
fun AuthContent(
    state: AuthUiState,
    onEmailChange: (String) -> Unit,
    onCodeChange: (String) -> Unit,
    onRequestCode: () -> Unit,
    onVerifyCode: () -> Unit,
    onBackToEmail: () -> Unit,
    onResetAccess: () -> Unit,
    onLogout: () -> Unit,
    authRepository: ru.lumastack.lumawork.data.auth.AuthRepository,
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
) {
    if (state.phase == AuthPhase.Authenticated && state.session != null) {
        AppShell(
            session = state.session,
            isOffline = state.isOffline,
            authRepository = authRepository,
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
            onLogout = onLogout,
        )
        return
    }

    val isDark = MaterialTheme.colorScheme.background == LumaBackgroundTopDark
    val background = remember(isDark) {
        Brush.verticalGradient(
            if (isDark) {
                listOf(LumaBackgroundTopDark, LumaBackgroundBottomDark)
            } else {
                listOf(LumaBackgroundTopLight, LumaBackgroundBottomLight)
            },
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 480.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(68.dp)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(34.dp),
                )
            }
            Spacer(Modifier.height(18.dp))
            Text(
                text = if (state.phase == AuthPhase.AccessRevoked) "Доступ отозван" else "Вход в «Инженер»",
                style = MaterialTheme.typography.headlineLarge,
                textAlign = TextAlign.Center,
            )
            if (state.phase == AuthPhase.Code || state.phase == AuthPhase.AccessRevoked) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = if (state.phase == AuthPhase.Code) {
                        "Введите код, отправленный на почту."
                    } else {
                        "Учётная запись больше не имеет доступа к приложению."
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(24.dp))

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    when (state.phase) {
                        AuthPhase.AccessRevoked -> AccessRevokedContent(onResetAccess)
                        AuthPhase.Code -> CodeContent(
                            state = state,
                            onCodeChange = onCodeChange,
                            onVerifyCode = onVerifyCode,
                            onBackToEmail = onBackToEmail,
                        )
                        else -> EmailContent(
                            state = state,
                            onEmailChange = onEmailChange,
                            onRequestCode = onRequestCode,
                        )
                    }

                    state.notice?.let { StatusText(it, isError = false) }
                    state.errorMessage?.let { StatusText(it, isError = true) }
                }
            }
        }
    }
}

@Composable
private fun EmailContent(
    state: AuthUiState,
    onEmailChange: (String) -> Unit,
    onRequestCode: () -> Unit,
) {
    AppTextField(
        value = state.email,
        onValueChange = onEmailChange,
        label = "Почта",
        singleLine = true,
        enabled = !state.isLoading,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Email,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { onRequestCode() }),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    )
    PrimaryActionButton("Получить код", state.isLoading, onRequestCode)
}

@Composable
private fun CodeContent(
    state: AuthUiState,
    onCodeChange: (String) -> Unit,
    onVerifyCode: () -> Unit,
    onBackToEmail: () -> Unit,
) {
    Text(
        text = state.email,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
    OneTimeCodeInput(
        code = state.code,
        onCodeChange = onCodeChange,
        enabled = !state.isLoading,
        onDone = onVerifyCode,
    )
    PrimaryActionButton("Войти", state.isLoading, onVerifyCode)
    OutlinedButton(
        onClick = onBackToEmail,
        enabled = !state.isLoading,
        shape = RoundedCornerShape(16.dp),
        contentPadding = PaddingValues(vertical = 14.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Изменить почту")
    }
}

@Composable
private fun OneTimeCodeInput(
    code: String,
    onCodeChange: (String) -> Unit,
    enabled: Boolean,
    onDone: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val focusRequester = remember { FocusRequester() }
    val shape = RoundedCornerShape(16.dp)

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        BasicTextField(
            value = code,
            onValueChange = onCodeChange,
            enabled = enabled,
            singleLine = true,
            interactionSource = interaction,
            textStyle = MaterialTheme.typography.titleLarge.copy(color = Color.Transparent),
            cursorBrush = SolidColor(Color.Transparent),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.NumberPassword,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { onDone() }),
            modifier = Modifier
                .widthIn(max = 340.dp)
                .fillMaxWidth()
                .focusRequester(focusRequester)
                .semantics {
                    contentDescription = "Код из письма"
                    contentType = ContentType.SmsOtpCode
                },
            decorationBox = { innerTextField ->
                Box {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        repeat(6) { index ->
                            val active = focused && index == code.length.coerceAtMost(5)
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 54.dp)
                                    .background(MaterialTheme.colorScheme.surfaceContainerLow, shape)
                                    .border(
                                        1.dp,
                                        if (active) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.outlineVariant,
                                        shape,
                                    )
                                    .padding(vertical = 10.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = code.getOrNull(index)?.toString().orEmpty(),
                                    style = MaterialTheme.typography.titleLarge,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface.copy(
                                        alpha = if (enabled) 1f else .45f,
                                    ),
                                    modifier = Modifier.clearAndSetSemantics { },
                                )
                            }
                        }
                    }
                    Box(Modifier.matchParentSize(), contentAlignment = Alignment.CenterStart) {
                        innerTextField()
                    }
                }
            },
        )
    }
}

@Composable
private fun AccessRevokedContent(onResetAccess: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    Text(
        text = "Для восстановления обратитесь в поддержку contact@lumastack.ru.",
        style = MaterialTheme.typography.bodyLarge,
    )
    Button(
        onClick = { uriHandler.openUri("mailto:contact@lumastack.ru") },
        shape = RoundedCornerShape(16.dp),
        contentPadding = PaddingValues(vertical = 14.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Написать в поддержку")
    }
    OutlinedButton(
        onClick = onResetAccess,
        shape = RoundedCornerShape(16.dp),
        contentPadding = PaddingValues(vertical = 14.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Войти с другой почтой")
    }
}

@Composable
private fun PrimaryActionButton(label: String, loading: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = !loading,
        shape = RoundedCornerShape(16.dp),
        contentPadding = PaddingValues(vertical = 14.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
            Text(label, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun StatusText(message: String, isError: Boolean) {
    Text(
        text = message,
        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.bodyMedium,
    )
}
