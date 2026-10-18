package ru.lumastack.lumawork.ui.profile

import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import java.io.File
import ru.lumastack.lumawork.data.auto.VehicleImageRepository
import ru.lumastack.lumawork.domain.model.AppUser

/** Shared avatar actions for Profile and Settings; image preparation stays in readAvatarJpeg. */
@Composable
internal fun ProfileAvatarEditor(user: AppUser, images: VehicleImageRepository, enabled: Boolean,
    busy: Boolean, onUpload: (suspend () -> ByteArray) -> Unit, onDelete: () -> Unit, onError: (String) -> Unit,
    size: Dp = 80.dp, showEmail: Boolean = true) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    var remove by remember { mutableStateOf(false) }
    var cameraPath by rememberSaveable(user.id) { mutableStateOf<String?>(null) }
    val upload by rememberUpdatedState(onUpload)
    val read: (Uri) -> Unit = { uri -> upload { readAvatarJpeg(context, uri) } }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { it?.let(read) }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(read) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val file = cameraPath?.let(::File)
        cameraPath = null
        if (success && file != null) upload {
            try { readAvatarJpeg(context, Uri.fromFile(file)) } finally { file.delete() }
        } else file?.delete()
    }
    fun launchCamera() {
        runCatching {
            val file = File.createTempFile("profile-avatar-", ".jpg", context.cacheDir)
            cameraPath = file.absolutePath
            camera.launch(FileProvider.getUriForFile(context, "${context.packageName}.files", file))
        }.onFailure {
            cameraPath?.let(::File)?.delete(); cameraPath = null; onError("Не удалось открыть камеру.")
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchCamera() else onError("Для съёмки фото разрешите доступ к камере.")
    }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(contentAlignment = Alignment.Center) {
            ProfileAvatar(user, images, size)
            if (busy) CircularProgressIndicator(Modifier.size(24.dp))
        }
        Box {
            TextButton(onClick = { menu = true }, enabled = enabled && !busy) { Text("Изменить фото") }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem(text = { Text("Фотогалерея") }, onClick = {
                    menu = false; gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                })
                DropdownMenuItem(text = { Text("Сделать снимок") },
                    enabled = context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY), onClick = {
                        menu = false
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) launchCamera()
                        else permission.launch(Manifest.permission.CAMERA)
                    })
                DropdownMenuItem(text = { Text("Выбрать файл") }, onClick = { menu = false; files.launch(arrayOf("image/*")) })
                if (!user.avatarUrl.isNullOrBlank()) DropdownMenuItem(text = { Text("Удалить фото", color = MaterialTheme.colorScheme.error) },
                    onClick = { menu = false; remove = true })
            }
        }
        if (showEmail) Text(user.email, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (remove) AlertDialog(onDismissRequest = { if (!busy) remove = false }, title = { Text("Удалить фото профиля?") },
        text = { Text("В профиле снова будет показан стандартный аватар.") },
        confirmButton = { TextButton(enabled = enabled && !busy, onClick = { remove = false; onDelete() }) { Text("Удалить") } },
        dismissButton = { TextButton(enabled = !busy, onClick = { remove = false }) { Text("Отмена") } })
}
