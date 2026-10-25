package ru.lumastack.lumawork.ui.gsm

import ru.lumastack.lumawork.ui.theme.AppContentHorizontalPadding
import android.app.TimePickerDialog
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import ru.lumastack.lumawork.BuildConfig
import ru.lumastack.lumawork.data.route.GsmDeliverySettings
import ru.lumastack.lumawork.data.route.OkHttpRouteApi
import ru.lumastack.lumawork.domain.model.AppSession
import ru.lumastack.lumawork.ui.theme.AppCard
import ru.lumastack.lumawork.ui.theme.AppTextField
import ru.lumastack.lumawork.ui.theme.appScreenBackground
import ru.lumastack.lumawork.ui.theme.appTopBarColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GsmDeliverySettingsScreen(session: AppSession, offline: Boolean, onBack: () -> Unit) {
    val api = remember { OkHttpRouteApi(BuildConfig.API_ORIGIN) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var draft by remember(session.user.id) { mutableStateOf(GsmDeliverySettings()) }
    var email by remember(session.user.id) { mutableStateOf(session.user.email) }
    var loaded by remember(session.user.id) { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var timePickerOpen by remember { mutableStateOf(false) }
    val enabled = loaded && !loading && !saving && !offline
    BackHandler { if (!saving) onBack() }
    LaunchedEffect(session.user.id, session.token, reload, offline) {
        loading = true
        error = null
        try {
            if (offline) error = "Для изменения настроек требуется подключение к интернету."
            else {
                draft = api.deliverySettings(session.token)
                email = draft.effectiveEmail
                loaded = true
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message ?: "Не удалось загрузить настройки отправки." }
        finally { loading = false }
    }
    Scaffold(modifier = Modifier.background(appScreenBackground()), containerColor = androidx.compose.ui.graphics.Color.Transparent, topBar = {
        TopAppBar(colors = appTopBarColors(), title = { Text("Отправка ГСМ отчета") }, navigationIcon = {
            IconButton(onClick = onBack, enabled = !saving) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") }
        }, actions = {
            TextButton(enabled = enabled, onClick = {
                saving = true
                error = null
                scope.launch {
                    try {
                        val recipient = email.trim().takeIf { it.isNotEmpty() && it != session.user.email }
                        draft = api.saveDeliverySettings(session.token, draft.copy(recipientEmail = recipient))
                        onBack()
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { error = failure.message ?: "Не удалось сохранить настройки отправки." }
                    finally { saving = false }
                }
            }) { Text("Сохранить") }
        })
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(horizontal = AppContentHorizontalPadding, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (loading || saving) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            item {
                AppCard {
                    ListItem(headlineContent = { Text("Автоматическая отправка") }, trailingContent = {
                        Switch(draft.autoEnabled, onCheckedChange = { draft = draft.copy(autoEnabled = it) }, enabled = enabled)
                    })
                    ListItem(headlineContent = { Text("Время отправки") }, trailingContent = { Text(draft.time) },
                        modifier = Modifier.clickable(enabled = enabled && draft.autoEnabled) { timePickerOpen = true })
                }
            }
            item { Text("Ежедневно по московскому времени (МСК), только если данные отчёта изменились. Приложение можно закрыть.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            item {
                AppTextField(email, { email = it }, "Электронная почта получателя", enabled = enabled,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
                TextButton(enabled = enabled, onClick = { email = session.user.email }) { Text("Использовать почту аккаунта") }
                Text("Адрес применяется к автоматической и ручной отправке ГСМ-отчётов. Пустое поле — почта аккаунта. Почта для входа не изменится.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            error?.let { message -> item {
                Text(message, color = MaterialTheme.colorScheme.error)
                if (!loaded) TextButton(enabled = !loading && !offline, onClick = { reload++ }) { Text("Повторить") }
            } }
        }
    }
    if (timePickerOpen) DisposableEffect(Unit) {
        val parts = draft.time.split(":").map(String::toInt)
        val dialog = TimePickerDialog(context, { _, hour, minute ->
            draft = draft.copy(time = java.lang.String.format(java.util.Locale.ROOT, "%02d:%02d", hour, minute))
            timePickerOpen = false
        }, parts[0], parts[1], true)
        dialog.setOnDismissListener { timePickerOpen = false }
        dialog.show()
        onDispose { dialog.dismiss() }
    }
}
