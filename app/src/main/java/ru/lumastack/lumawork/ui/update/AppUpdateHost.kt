package ru.lumastack.lumawork.ui.update

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.io.File

val LocalUpdateCheck = staticCompositionLocalOf<() -> Unit> { {} }

/** ACTION_INSTALL_PACKAGE opens the platform PackageInstaller; it always requires user confirmation. */
@Suppress("DEPRECATION")
@Composable
fun AppUpdateHost(viewModel: UpdateViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val installer = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        viewModel.message(when (result.resultCode) {
            Activity.RESULT_OK -> "Обновление установлено."
            Activity.RESULT_CANCELED -> "Установка отменена. Текущая версия сохранена."
            else -> "Android не установил обновление. Проверьте свободное место, совместимость и подпись APK."
        })
    }
    val launchInstall: (File) -> Unit = { file ->
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        installer.launch(Intent(Intent.ACTION_INSTALL_PACKAGE).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).putExtra(Intent.EXTRA_RETURN_RESULT, true))
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (viewModel.state.value.waitingPermission) {
            if (context.packageManager.canRequestPackageInstalls()) viewModel.verifyAndInstall(launchInstall)
            else viewModel.message("Установка не разрешена. Разрешите её для «Инженер» и повторите.")
        }
    }
    if (state.visible) AlertDialog(onDismissRequest = viewModel::dismiss,
        title = { Text(state.release?.let { "Обновление ${it.versionName}" } ?: "Обновление приложения") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(state.message)
            state.release?.changelog?.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
            state.progress?.let { progress -> LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth()); Text("${(progress * 100).toInt()} %") }
            if (state.busy && state.progress == null) CircularProgressIndicator()
        } },
        confirmButton = {
            if (state.release != null) TextButton(enabled = !state.busy, onClick = {
                if (state.file == null) viewModel.download()
                else if (!context.packageManager.canRequestPackageInstalls()) {
                    try { viewModel.permissionRequested(); permission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))) }
                    catch (_: Exception) { viewModel.message("Не удалось открыть разрешения установки. Откройте их в настройках Android.") }
                } else viewModel.verifyAndInstall(launchInstall)
            }) { Text(if (state.file == null) "Скачать" else "Установить") }
            else if (!state.busy) TextButton(onClick = { viewModel.check() }) { Text("Проверить снова") }
        },
        dismissButton = { TextButton(enabled = !state.busy || state.progress != null, onClick = { if (state.busy && state.progress != null) viewModel.cancelDownload() else viewModel.dismiss() }) { Text(if (state.busy && state.progress != null) "Отменить загрузку" else "Закрыть") } })
}
