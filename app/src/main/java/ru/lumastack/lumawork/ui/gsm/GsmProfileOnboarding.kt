package ru.lumastack.lumawork.ui.gsm

import android.content.Context
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import ru.lumastack.lumawork.BuildConfig
import ru.lumastack.lumawork.data.auto.AutoRepository
import ru.lumastack.lumawork.data.fuel.FuelRepository
import ru.lumastack.lumawork.data.gsm.*
import ru.lumastack.lumawork.domain.model.AppSession

/** Prompts only after a successful account-scoped server read, once per account on this device. */
@Composable
fun GsmProfileOnboarding(session: AppSession, offline: Boolean, auto: AutoRepository, fuel: FuelRepository, onProfileSaved: () -> Unit = {}) {
    val context = LocalContext.current.applicationContext
    val dispatcher = LocalOnBackPressedDispatcherOwner.current!!
    val preferences = remember(context) { context.getSharedPreferences("gsm-onboarding", Context.MODE_PRIVATE) }
    var prompt by rememberSaveable(session.user.id) { mutableStateOf(false) }
    var form by rememberSaveable(session.user.id) { mutableStateOf(false) }
    var importOnOpen by rememberSaveable(session.user.id) { mutableStateOf(false) }
    LaunchedEffect(session.user.id, session.token, offline) {
        if (offline || preferences.getBoolean(session.user.id, false) || form) return@LaunchedEffect
        try {
            val result = OkHttpGsmProfileApi(BuildConfig.API_ORIGIN, Json { ignoreUnknownKeys = true }).fetch(session.token)
            prompt = result.profile.isEmptyForOnboarding
            if (BuildConfig.DEBUG) android.util.Log.d("GsmOnboarding", "Server profile loaded; empty=$prompt")
            if (!prompt) preferences.edit().putBoolean(session.user.id, true).apply()
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            if (BuildConfig.DEBUG) android.util.Log.w("GsmOnboarding", "Profile check failed: ${e.javaClass.simpleName}")
            // No prompt based on a failed request or empty cache. Retry next online entry.
        }
    }
    fun acknowledge() { prompt = false; preferences.edit().putBoolean(session.user.id, true).apply() }
    if (prompt) AlertDialog(
        onDismissRequest = { acknowledge() },
        title = { Text("Заполнить ГСМ профиль") },
        text = { Text("Загрузите XLSX отчёт за ${YearMonth.now(ZoneId.of("Europe/Simferopol")).minusMonths(1).format(DateTimeFormatter.ofPattern("LLLL yyyy", Locale.forLanguageTag("ru")))}. Проверьте данные перед сохранением. Можно отказаться и заполнить поля вручную.") },
        confirmButton = { TextButton(onClick = { acknowledge(); importOnOpen = true; form = true }) { Text("Загрузить XLSX") } },
        dismissButton = { TextButton(onClick = { acknowledge(); importOnOpen = false; form = true }) { Text("Заполнить вручную") } },
    )
    if (form) Dialog(onDismissRequest = { dispatcher.onBackPressedDispatcher.onBackPressed() },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        CompositionLocalProvider(LocalOnBackPressedDispatcherOwner provides dispatcher) {
            Surface(Modifier.widthIn(max = 760.dp).fillMaxSize()) {
                GsmProfileSettingsScreen(session, offline, auto, onBack = { form = false }, importOnOpen = importOnOpen,
                    onSaved = { profile -> onProfileSaved(); fuel.setFuelTypes(session.user.id, profile.fuelTypes.ifEmpty { listOf(profile.fuelType) }) })
            }
        }
    }
}
