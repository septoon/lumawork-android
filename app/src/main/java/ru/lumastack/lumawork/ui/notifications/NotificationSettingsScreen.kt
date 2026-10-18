package ru.lumastack.lumawork.ui.notifications

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import ru.lumastack.lumawork.data.notifications.NotificationPreference
import ru.lumastack.lumawork.data.notifications.RequestNotificationsRuntime
import ru.lumastack.lumawork.domain.model.AppSession

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationSettingsScreen(session: AppSession, offline: Boolean, onBack: () -> Unit) {
    val context = LocalContext.current
    val runtime = remember(context) { RequestNotificationsRuntime.get(context) }
    val state by runtime.repository.settings(session.user.id).collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var notificationsEnabled by remember { mutableStateOf(runtime.delivery.isEnabled()) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        notificationsEnabled = runtime.delivery.isEnabled()
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, runtime) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) notificationsEnabled = runtime.delivery.isEnabled()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(session.user.id, session.token, offline) {
        runtime.repository.loadLocal(session)
        if (!offline) runtime.repository.synchronize(session)
    }
    LaunchedEffect(state.error, state.notice) {
        (state.error ?: state.notice)?.let { snackbar.showSnackbar(it) }
    }
    BackHandler(onBack = onBack)
    fun change(key: NotificationPreference, enabled: Boolean) {
        runtime.scope.launch(start = CoroutineStart.UNDISPATCHED) { runtime.repository.setPreference(session, key, enabled, offline) }
        if (enabled && !key.isEmail && !notificationsEnabled && Build.VERSION.SDK_INT >= 33) {
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(title = { Text("Уведомления") }, navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад") }
            }, windowInsets = WindowInsets(0, 0, 0, 0))
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            item {
                Column(Modifier.widthIn(max = 680.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("На устройстве", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = 8.dp))
                    Surface(shape = MaterialTheme.shapes.large) {
                        Column {
                            NotificationToggle("Новая активная заявка", state.preferences.localNewRequests, state.ready) { change(NotificationPreference.LocalNewRequests, it) }
                            NotificationToggle("Изменение исполнителя", state.preferences.localAssigneeChanges, state.ready) { change(NotificationPreference.LocalAssigneeChanges, it) }
                            if (!notificationsEnabled) {
                                TextButton(onClick = runtime.delivery::openSettings, modifier = Modifier.padding(horizontal = 8.dp)) {
                                    Text("Открыть системные настройки")
                                }
                            }
                        }
                    }
                }
            }
            item {
                Column(Modifier.widthIn(max = 680.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("По электронной почте", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = 8.dp))
                    Surface(shape = MaterialTheme.shapes.large) {
                        Column {
                            ListItem(headlineContent = { Text("Получатель") }, supportingContent = { Text(session.user.email) })
                            NotificationToggle("Новая активная заявка", state.preferences.emailNewRequests, state.ready && !offline) { change(NotificationPreference.EmailNewRequests, it) }
                            NotificationToggle("Изменение исполнителя", state.preferences.emailAssigneeChanges, state.ready && !offline) { change(NotificationPreference.EmailAssigneeChanges, it) }
                        }
                    }
                    Text("Письма отправляет сервер приложения «Инженер». SMTP-пароль не хранится в приложении.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 8.dp))
                    if (offline) Text("Для изменения настроек электронной почты требуется подключение к интернету.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 8.dp))
                }
            }
            if (!state.ready) item { CircularProgressIndicator() }
        }
    }
}

@Composable
private fun NotificationToggle(title: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
        .padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, modifier = Modifier.weight(1f), color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}
