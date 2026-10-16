package ru.lumastack.lumawork.ui.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlin.math.roundToInt
import ru.lumastack.lumawork.data.feedback.FeedbackRepository
import ru.lumastack.lumawork.domain.model.AppSession
import ru.lumastack.lumawork.ui.adaptive.appContentWidthClass
import ru.lumastack.lumawork.ui.feedback.FeedbackRoute
import ru.lumastack.lumawork.ui.theme.AppAppearance
import ru.lumastack.lumawork.ui.theme.LocalAppAppearance

/** Existing iOS settings destinations; only implemented Android actions are exposed. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppSettingsSheet(session: AppSession, onDismiss: () -> Unit, onOpenProfile: () -> Unit, onLogout: () -> Unit) {
    var appearanceOpen by rememberSaveable { mutableStateOf(false) }
    var logoutRequested by rememberSaveable { mutableStateOf(false) }
    val appearance = LocalAppAppearance.current
    ModalBottomSheet(onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), modifier = Modifier.fillMaxHeight(.94f)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            if (appearanceOpen) IconButton(onClick = { appearanceOpen = false }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") }
            Text(if (appearanceOpen) "Оформление" else "Настройки", modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Закрыть настройки") }
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (appearanceOpen) {
                items(AppAppearance.entries.size) { index ->
                    val mode = AppAppearance.entries[index]
                    Surface(onClick = { appearance.onChange(mode) }, color = MaterialTheme.colorScheme.surface) {
                        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = appearance.mode == mode, onClick = { appearance.onChange(mode) })
                            Text(mode.title)
                        }
                    }
                }
            } else {
                item { SettingsSectionLabel("Профиль") }
                item { TextButton(onClick = onOpenProfile, modifier = Modifier.fillMaxWidth()) { Text("Профиль", modifier = Modifier.weight(1f)) } }
                item { SettingsSectionLabel("Приложение") }
                item { TextButton(onClick = { appearanceOpen = true }, modifier = Modifier.fillMaxWidth()) { Text("Оформление", modifier = Modifier.weight(1f)) } }
                item { SettingsSectionLabel("Аккаунт") }
                item {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Электронная почта", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(session.user.email)
                    }
                }
                item { TextButton(onClick = { logoutRequested = true }, modifier = Modifier.fillMaxWidth()) { Text("Выйти", color = MaterialTheme.colorScheme.error) } }
            }
        }
    }
    if (logoutRequested) AlertDialog(onDismissRequest = { logoutRequested = false }, title = { Text("Выйти из приложения?") },
        text = { Text("Для следующего входа понадобится код с почты.") },
        confirmButton = { TextButton(onClick = { logoutRequested = false; onLogout() }) { Text("Выйти") } },
        dismissButton = { TextButton(onClick = { logoutRequested = false }) { Text("Отмена") } })
}

@Composable
private fun SettingsSectionLabel(title: String) {
    Text(title, Modifier.padding(start = 12.dp, top = 20.dp), style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppFeedbackDialog(session: AppSession, offline: Boolean, repository: FeedbackRepository, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(Modifier.fillMaxSize(), topBar = {
            TopAppBar(title = { Text("Обратная связь") }, navigationIcon = {
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Закрыть обратную связь") }
            })
        }) { padding ->
            BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
                val widthClass = appContentWidthClass(maxWidth.value.roundToInt(), maxHeight.value.roundToInt(), LocalDensity.current.fontScale)
                FeedbackRoute(session, offline, repository, widthClass)
            }
        }
    }
}
