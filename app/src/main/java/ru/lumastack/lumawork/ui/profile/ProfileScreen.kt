package ru.lumastack.lumawork.ui.profile

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.io.File
import ru.lumastack.lumawork.data.auth.AuthRepository
import ru.lumastack.lumawork.data.auto.AutoRepository
import ru.lumastack.lumawork.data.auto.VehicleImageRepository
import ru.lumastack.lumawork.domain.model.AppSession
import ru.lumastack.lumawork.ui.auto.VehicleEditorFields
import ru.lumastack.lumawork.ui.theme.AppTextField

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileRoute(
    session: AppSession,
    offline: Boolean,
    auth: AuthRepository,
    auto: AutoRepository,
    images: VehicleImageRepository,
    onDismiss: () -> Unit,
    startInEditor: Boolean = false,
) {
    val vm: ProfileViewModel = viewModel(key = "profile-${session.user.id}", factory = ProfileViewModel.factory(auth, auto))
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val preferences = remember { context.getSharedPreferences("profile-preferences", android.content.Context.MODE_PRIVATE) }
    var showVehicle by rememberSaveable { mutableStateOf(preferences.getBoolean("show-vehicle", true)) }
    var editing by rememberSaveable { mutableStateOf(startInEditor) }
    var avatarMenu by remember { mutableStateOf(false) }
    var removeAvatar by remember { mutableStateOf(false) }
    var cameraPath by rememberSaveable { mutableStateOf<String?>(null) }
    val close = {
        if (!state.busy) {
            if (editing && !startInEditor) { editing = false; vm.close(); vm.open(session, offline) }
            else { vm.close(); onDismiss() }
        }
    }
    val importAvatar: (Uri) -> Unit = { uri -> vm.uploadAvatar { readAvatarJpeg(context, uri) } }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(importAvatar) }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(importAvatar) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val file = cameraPath?.let(::File)
        cameraPath = null
        if (success && file != null) vm.uploadAvatar {
            try { readAvatarJpeg(context, Uri.fromFile(file)) } finally { file.delete() }
        } else file?.delete()
    }
    LaunchedEffect(Unit) { vm.open(session, offline) }
    LaunchedEffect(state.saved) {
        if (state.saved) {
            preferences.edit().putBoolean("show-vehicle", showVehicle).apply()
            vm.close()
            onDismiss()
        }
    }

    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            modifier = Modifier.fillMaxSize().imePadding(),
            topBar = {
                TopAppBar(title = { Text("Профиль") }, navigationIcon = {
                    IconButton(onClick = close, enabled = !state.busy) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Закрыть профиль") }
                }, actions = {
                    if (state.busy) CircularProgressIndicator(Modifier.padding(12.dp).size(24.dp), strokeWidth = 2.dp)
                    else if (editing) TextButton(onClick = vm::save, enabled = profileValidationError(state.draft) == null) { Text("Сохранить") }
                })
            },
        ) { padding ->
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                if (!editing) ProfileOverview(session.user, state, images, showVehicle)
                else LazyColumn(Modifier.widthIn(max = 760.dp).fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    item(key = "avatar") {
                        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                            ProfileAvatar(session.user, images, 80.dp)
                            Box {
                                TextButton(onClick = { avatarMenu = true }, enabled = !state.busy) { Text("Изменить фото") }
                                DropdownMenu(avatarMenu, { avatarMenu = false }) {
                                    DropdownMenuItem(text = { Text("Фотогалерея") }, onClick = {
                                        avatarMenu = false
                                        gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                    })
                                    DropdownMenuItem(text = { Text("Сделать фото") }, onClick = {
                                        avatarMenu = false
                                        runCatching {
                                            val file = File.createTempFile("profile-avatar-", ".jpg", context.cacheDir)
                                            cameraPath = file.absolutePath
                                            camera.launch(FileProvider.getUriForFile(context, "${context.packageName}.files", file))
                                        }.onFailure {
                                            cameraPath?.let(::File)?.delete()
                                            cameraPath = null
                                            vm.showError("Не удалось открыть камеру.")
                                        }
                                    })
                                    DropdownMenuItem(text = { Text("Выбрать файл") }, onClick = { avatarMenu = false; files.launch(arrayOf("image/*")) })
                                    if (!session.user.avatarUrl.isNullOrBlank()) DropdownMenuItem(text = { Text("Удалить фото", color = MaterialTheme.colorScheme.error) }, onClick = { avatarMenu = false; removeAvatar = true })
                                }
                            }
                            Text(session.user.email, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    state.error?.let { message -> item(key = "error") { Text(message, color = MaterialTheme.colorScheme.error) } }
                    item(key = "about-title") { ProfileHeading("О вас", "Все поля необязательные") }
                    item(key = "about") {
                        ProfileCard {
                            ProfileField("Фамилия", state.draft.lastName, state.busy) { vm.edit(state.draft.copy(lastName = it)) }
                            ProfileField("Имя", state.draft.firstName, state.busy) { vm.edit(state.draft.copy(firstName = it)) }
                            ProfileField("Отчество", state.draft.middleName, state.busy) { vm.edit(state.draft.copy(middleName = it)) }
                            ProfileField("Должность", state.draft.jobTitle, state.busy) { vm.edit(state.draft.copy(jobTitle = it)) }
                            ProfileField("Город", state.draft.city, state.busy) { vm.edit(state.draft.copy(city = it)) }
                        }
                    }
                    item(key = "contacts-title") { ProfileHeading("Контакты", "Для связи и автозаполнения") }
                    item(key = "contacts") {
                        ProfileCard {
                            ProfileField("Телефон", state.draft.personalPhone, state.busy, KeyboardType.Phone) { vm.edit(state.draft.copy(personalPhone = it)) }
                            ProfileField("Почта", state.draft.workEmail, state.busy, KeyboardType.Email) { vm.edit(state.draft.copy(workEmail = it)) }
                            profileValidationError(state.draft)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        }
                    }
                    item(key = "vehicle-title") { ProfileHeading("Основной автомобиль") }
                    item(key = "vehicle") {
                        ProfileCard {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Column(Modifier.weight(1f)) {
                                    Text("Показывать в профиле")
                                    Text("Скрытие не удаляет сохраненные данные", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Switch(showVehicle, { showVehicle = it }, enabled = !state.busy)
                            }
                            state.vehicleError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                            if (showVehicle && state.vehicleReady) VehicleEditorFields(state.vehicle, vm::editVehicle, showPrimaryToggle = false, enabled = !state.busy)
                            else if (showVehicle && state.vehicleError == null) CircularProgressIndicator(Modifier.size(24.dp))
                        }
                    }

                }
            }
        }
        if (removeAvatar) AlertDialog(onDismissRequest = { removeAvatar = false }, title = { Text("Удалить фото профиля?") },
            confirmButton = { TextButton(onClick = { removeAvatar = false; vm.deleteAvatar() }) { Text("Удалить") } },
            dismissButton = { TextButton(onClick = { removeAvatar = false }) { Text("Отмена") } })

    }
}

@Composable
private fun ProfileCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
private fun ProfileHeading(title: String, caption: String? = null) {
    Column {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        caption?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun ProfileField(label: String, value: String?, busy: Boolean, keyboard: KeyboardType = KeyboardType.Text, change: (String) -> Unit) {
    AppTextField(value.orEmpty(), change, label, enabled = !busy, singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = keyboard))
}
