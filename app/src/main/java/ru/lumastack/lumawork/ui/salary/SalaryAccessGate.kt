package ru.lumastack.lumawork.ui.salary

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import androidx.activity.compose.BackHandler
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.lumastack.lumawork.BuildConfig
import ru.lumastack.lumawork.data.auth.OkHttpAuthApi
import ru.lumastack.lumawork.data.salary.SalaryPasscodeStore
import ru.lumastack.lumawork.domain.model.AppSession
import ru.lumastack.lumawork.ui.theme.appScreenBackground
import ru.lumastack.lumawork.ui.theme.AppButton
import ru.lumastack.lumawork.ui.theme.appTopBarColors
import ru.lumastack.lumawork.ui.shell.AppHeaderActions

val LocalSalaryBiometricEnabled = androidx.compose.runtime.staticCompositionLocalOf { true }

@Composable
fun SalaryAccessGate(
    session: AppSession,
    startOffline: Boolean,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val store = remember(context) { SalaryPasscodeStore(context) }
    val api = remember { OkHttpAuthApi(BuildConfig.API_ORIGIN) }
    var state by remember(session.user.id) { mutableStateOf(SalaryAccessState(store.read())) }
    var hasUnlockedOnce by remember(session.user.id) { mutableStateOf(false) }
    val presentationAccess = remember(session.user.id) { SalaryPresentationAccess() }
    var foreground by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    var automaticBiometricAttempted by remember(session.user.id) { mutableStateOf(false) }
    var activeBiometric by remember { mutableStateOf<CancellationSignal?>(null) }
    var recoveryJob by remember { mutableStateOf<Job?>(null) }
    var generation by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    val email = session.user.email.trim().lowercase()
    val currentCancel by rememberUpdatedState(onCancel)
    val biometricEnabled = LocalSalaryBiometricEnabled.current
    val biometricAvailable = biometricEnabled && remember(context, foreground) { salaryBiometricAvailable(context) }

    fun cancelPending() {
        presentationAccess.lock()
        generation += 1
        recoveryJob?.cancel()
        recoveryJob = null
        val signal = activeBiometric
        activeBiometric = null
        signal?.cancel()
    }

    fun leave() {
        cancelPending()
        state = state.relock()
        currentCancel()
    }

    fun authenticate() {
        if (Build.VERSION.SDK_INT < 28 || !foreground || !biometricAvailable || activeBiometric != null ||
            state.savedPin == null || state.phase != SalaryAccessPhase.EnterPin || state.isBusy || state.isUnlocked) return
        val activity = context.salaryActivity() ?: return
        val attempt = generation
        val signal = CancellationSignal()
        activeBiometric = signal
        showSalaryBiometric(activity, signal, onFinished = { authenticated, message ->
            // Ignore callbacks from a cancelled prompt or after the app left the foreground.
            if (activeBiometric === signal && generation == attempt) {
                activeBiometric = null
                if (foreground && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                    state = if (authenticated) state.biometricUnlocked()
                        else if (message != null) state.failed(message) else state
                }
            }
        })
    }

    fun requestRecovery() {
        if (startOffline || state.isBusy || activeBiometric != null) return
        if (email.isBlank()) {
            state = state.failed("Не удалось определить почту пользователя. Войдите в приложение заново.")
            return
        }
        val next = state.requestRecovery()
        if (!next.isBusy) return
        state = next
        val attempt = generation
        recoveryJob = scope.launch {
            try {
                withContext(Dispatchers.IO) { api.requestCode(email) }
                if (generation == attempt && foreground) state = state.recoverySent(email)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (generation == attempt && foreground) state = state.failed(error.message ?: "Не удалось отправить разовый код.")
            }
        }
    }

    fun verifyRecovery() {
        if (startOffline || state.isBusy) return
        state = state.verifyRecovery()
        if (!state.isBusy) return
        val code = state.recoveryCode
        val attempt = generation
        recoveryJob = scope.launch {
            try {
                val verified = withContext(Dispatchers.IO) { api.verifyCode(email, code) }
                if (generation == attempt && foreground) {
                    state = state.recoveryVerified(salaryRecoveryMatchesUser(session.user.id, email, verified.user.id, verified.user.email))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (generation == attempt && foreground) state = state.failed(error.message ?: "Не удалось подтвердить разовый код.")
            }
        }
    }

    DisposableEffect(lifecycle, session.user.id) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> foreground = true
                Lifecycle.Event.ON_STOP -> {
                    foreground = false
                    cancelPending()
                    state = state.relock()
                    automaticBiometricAttempted = false
                }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            cancelPending()
        }
    }

    LaunchedEffect(foreground, state.isUnlocked, state.phase, biometricAvailable) {
        if (foreground && !state.isUnlocked && state.phase == SalaryAccessPhase.EnterPin && !automaticBiometricAttempted) {
            automaticBiometricAttempted = true
            authenticate()
        }
    }

    // Keep registered activity-result launchers alive under the lock while a document
    // picker or external preview is open. A native opaque dialog covers every salary modal.
    if (state.isUnlocked && foreground) SideEffect { hasUnlockedOnce = true; presentationAccess.unlock() }
    if (hasUnlockedOnce || (state.isUnlocked && foreground)) CompositionLocalProvider(LocalSalaryPresentationAccess provides presentationAccess) { content() }
    if (state.isUnlocked && foreground) return

    BackHandler {
        if (state.phase == SalaryAccessPhase.RecoveryCode) {
            cancelPending()
            state = state.returnToPin()
        } else leave()
    }
    ru.lumastack.lumawork.ui.shell.AppHeaderBackHandler(
        enabled = !hasUnlockedOnce && state.phase == SalaryAccessPhase.RecoveryCode,
    ) { cancelPending(); state = state.returnToPin() }
    if (!hasUnlockedOnce) AppHeaderActions {
        SalaryPinIconButton(onClick = ::leave) {
            Icon(Icons.Default.Close, contentDescription = "Закрыть ввод PIN")
        }
    }

    val lockContent: @Composable () -> Unit = { SalaryAccessScreen(
        state = state,
        email = email,
        offline = startOffline,
        biometricAvailable = biometricAvailable && state.savedPin != null,
        biometricBusy = activeBiometric != null,
        showHeader = hasUnlockedOnce,
        onCancel = ::leave,
        onBack = { cancelPending(); state = state.returnToPin() },
        onDigit = { digit ->
            val next = state.digit(digit)
            state = next.pinToSave?.let { pin ->
                try {
                    store.save(pin)
                    next.pinSaved()
                } catch (error: Exception) {
                    next.failed(error.message ?: "Не удалось сохранить PIN. Попробуйте ещё раз.")
                }
            } ?: next
        },
        onDelete = { state = state.deleteDigit() },
        onRestartSetup = { state = state.restartSetup() },
        onBiometric = ::authenticate,
        onRequestRecovery = ::requestRecovery,
        onRecoveryCode = { state = state.recoveryInput(it) },
        onVerifyRecovery = ::verifyRecovery,
        modifier = if (hasUnlockedOnce) Modifier else modifier,
    ) }
    if (hasUnlockedOnce) Dialog(onDismissRequest = {
        if (state.phase == SalaryAccessPhase.RecoveryCode) { cancelPending(); state = state.returnToPin() } else leave()
    }, properties = DialogProperties(
        usePlatformDefaultWidth = false, decorFitsSystemWindows = false, dismissOnBackPress = true, dismissOnClickOutside = false,
    )) { lockContent() } else lockContent()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SalaryAccessScreen(
    state: SalaryAccessState,
    email: String,
    offline: Boolean,
    biometricAvailable: Boolean,
    biometricBusy: Boolean,
    showHeader: Boolean,
    onCancel: () -> Unit,
    onBack: () -> Unit,
    onDigit: (Int) -> Unit,
    onDelete: () -> Unit,
    onRestartSetup: () -> Unit,
    onBiometric: () -> Unit,
    onRequestRecovery: () -> Unit,
    onRecoveryCode: (String) -> Unit,
    onVerifyRecovery: () -> Unit,
    modifier: Modifier,
) {
    val title = when (state.phase) {
        SalaryAccessPhase.EnterPin -> null
        SalaryAccessPhase.CreatePin -> if (state.savedPin == null) "Придумайте PIN" else "Придумайте новый PIN"
        SalaryAccessPhase.ConfirmPin -> "Повторите PIN"
        SalaryAccessPhase.RecoveryCode -> "Восстановление PIN"
    }
    Column(
        modifier.fillMaxSize().background(appScreenBackground()).safeDrawingPadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (showHeader) CenterAlignedTopAppBar(
            title = { Text("Зарплата") },
            navigationIcon = {
                if (state.phase == SalaryAccessPhase.RecoveryCode) SalaryPinIconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад к вводу PIN")
                }
            },
            actions = {
                SalaryPinIconButton(onClick = onCancel) {
                    Icon(Icons.Default.Close, contentDescription = "Закрыть ввод PIN")
                }
            },
            colors = appTopBarColors(),
            windowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
        )
        Column(
            Modifier.weight(1f).widthIn(max = 420.dp).fillMaxWidth()
                .verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
        ) {
            if (title != null) {
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            }
            if (state.phase == SalaryAccessPhase.RecoveryCode) {
                Text("Введите 6 цифр из письма", style = MaterialTheme.typography.titleMedium)
                Text(email, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                OutlinedTextField(
                    value = state.recoveryCode,
                    onValueChange = onRecoveryCode,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Разовый код") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true,
                    enabled = !state.isBusy,
                    isError = state.error != null,
                )
                AppButton(onClick = onVerifyRecovery, enabled = !state.isBusy && state.recoveryCode.length == 6 && !offline) { Text("Подтвердить код") }
                SalaryPinTextButton(onClick = onRequestRecovery, enabled = !state.isBusy && !offline) { Text("Отправить код повторно") }
            } else {
                Row(
                    Modifier.padding(vertical = 12.dp).semantics { contentDescription = "Введено ${state.input.length} из 4 цифр PIN" },
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    repeat(4) { index ->
                        Surface(
                            modifier = Modifier.size(12.dp), shape = CircleShape,
                            color = when {
                                state.error != null -> MaterialTheme.colorScheme.error
                                index < state.input.length -> MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.outlineVariant
                            },
                        ) {}
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(listOf(1, 2, 3), listOf(4, 5, 6), listOf(7, 8, 9)).forEach { digits ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            digits.forEach { digit -> SalaryPinDigit(digit, !state.isBusy && !biometricBusy, onDigit) }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        val creating = state.phase != SalaryAccessPhase.EnterPin
                        SalaryPinTextButton(
                            onClick = if (creating) onRestartSetup else onRequestRecovery,
                            modifier = Modifier.weight(1f),
                            enabled = !state.isBusy && !biometricBusy && (creating || !offline),
                        ) { Text(if (creating) "Сбросить ввод" else "Забыли PIN?", textAlign = TextAlign.Center) }
                        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { SalaryPinDigit(0, !state.isBusy && !biometricBusy, onDigit) }
                        val showBiometric = state.phase == SalaryAccessPhase.EnterPin && state.input.isEmpty() && biometricAvailable
                        SalaryPinTextButton(onClick = if (showBiometric) onBiometric else onDelete, modifier = Modifier.weight(1f), enabled = !state.isBusy && !biometricBusy) {
                            Text(if (showBiometric) "Биометрия" else "Удалить", textAlign = TextAlign.Center)
                        }
                    }
                }
            }
            if (offline && state.savedPin != null) Text("Восстановление PIN доступно при подключении к интернету.", style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
            state.notice?.let { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center) }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center) }
            if (state.isBusy || biometricBusy) CircularProgressIndicator(Modifier.size(24.dp))
        }
    }
}

@Composable
private fun SalaryPinDigit(digit: Int, enabled: Boolean, onDigit: (Int) -> Unit) {
    SalaryPinTextButton(onClick = { onDigit(digit) }, enabled = enabled, modifier = Modifier.size(68.dp)) {
        Text(digit.toString(), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SalaryPinTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    TextButton(onClick = onClick, enabled = enabled, interactionSource = interaction,
        modifier = modifier.then(salaryPinPressAnimation(interaction)), content = content)
}

@Composable
private fun SalaryPinIconButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    IconButton(onClick = onClick, interactionSource = interaction,
        modifier = salaryPinPressAnimation(interaction), content = content)
}

@Composable
private fun salaryPinPressAnimation(interaction: MutableInteractionSource): Modifier {
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .92f else 1f,
        animationSpec = spring(dampingRatio = .7f, stiffness = 700f), label = "PIN button press")
    return Modifier.graphicsLayer { scaleX = scale; scaleY = scale }
}

private tailrec fun Context.salaryActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.salaryActivity()
    else -> null
}

internal fun salaryBiometricAvailable(context: Context): Boolean = when {
    Build.VERSION.SDK_INT >= 29 -> context.getSystemService(BiometricManager::class.java)?.canAuthenticate() == BiometricManager.BIOMETRIC_SUCCESS
    Build.VERSION.SDK_INT == 28 -> {
        val manager = context.packageManager
        context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true &&
            (manager.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT) || manager.hasSystemFeature(PackageManager.FEATURE_FACE) || manager.hasSystemFeature(PackageManager.FEATURE_IRIS))
    }
    else -> false
}

@RequiresApi(28)
private fun showSalaryBiometric(activity: Activity, cancellation: CancellationSignal, onFinished: (Boolean, String?) -> Unit) {
    val executor = ContextCompat.getMainExecutor(activity)
    try {
        val prompt = BiometricPrompt.Builder(activity)
            .setTitle("Сведения о зарплате")
            .setSubtitle("Подтвердите вход с помощью биометрии")
            .setNegativeButton("Ввести PIN", executor) { _, _ -> onFinished(false, null) }
            .build()
        prompt.authenticate(cancellation, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { onFinished(true, null) }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                val cancelled = errorCode in setOf(BiometricPrompt.BIOMETRIC_ERROR_CANCELED, BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED)
                onFinished(false, if (cancelled) null else "Не удалось подтвердить биометрию. Введите PIN.")
            }
            // A failed scan leaves the native prompt open for its own retry flow.
        })
    } catch (_: Exception) {
        onFinished(false, "Биометрия недоступна. Введите PIN.")
    }
}

internal val LocalSalaryPresentationAccess = staticCompositionLocalOf<SalaryPresentationAccess?> { null }
