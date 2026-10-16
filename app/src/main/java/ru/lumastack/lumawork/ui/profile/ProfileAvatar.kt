package ru.lumastack.lumawork.ui.profile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.lumastack.lumawork.data.auto.VehicleImageRepository
import ru.lumastack.lumawork.domain.model.AppUser

@Composable
fun ProfileAvatar(user: AppUser, images: VehicleImageRepository, size: Dp = 36.dp) {
    val bitmap by produceState<Bitmap?>(null, user.id, user.avatarUrl) {
        value = null
        val url = user.avatarUrl?.takeIf(String::isNotBlank) ?: return@produceState
        value = try { images.load(url) } catch (error: CancellationException) { throw error } catch (_: Exception) { null }
    }
    Surface(modifier = Modifier.size(size), shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
        Box(contentAlignment = Alignment.Center) {
            val loaded = bitmap
            if (loaded != null) Image(loaded.asImageBitmap(), contentDescription = null,
                modifier = Modifier.size(size).clip(CircleShape), contentScale = ContentScale.Crop)
            else Icon(Icons.Default.Person, contentDescription = null,
                modifier = Modifier.size(size * .62f), tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}

/** Bounded read, sampled decode and EXIF orientation, matching iOS's 1024px JPEG upload. */
internal suspend fun readAvatarJpeg(context: Context, uri: Uri): ByteArray = withContext(Dispatchers.IO) {
    val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
        ByteArrayOutputStream().use { output ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= MAX_AVATAR_INPUT_BYTES) { "Изображение больше 10 МБ." }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    }
        ?: error("Не удалось открыть изображение.")
    require(bytes.size <= MAX_AVATAR_INPUT_BYTES) { "Изображение больше 10 МБ." }
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    require(options.outWidth > 0 && options.outHeight > 0) { "Выберите файл изображения." }
    var sample = 1
    while (maxOf(options.outWidth, options.outHeight) / sample > 2048) sample *= 2
    val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        ?: error("Не удалось прочитать изображение.")
    var working = decoded
    try {
        val orientation = runCatching { ExifInterface(bytes.inputStream()).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val matrix = Matrix().apply {
            when (orientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(270f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(270f)
            }
            val scale = (1024f / maxOf(decoded.width, decoded.height)).coerceAtMost(1f)
            postScale(scale, scale)
        }
        working = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        ByteArrayOutputStream().use { output ->
            check(working.compress(Bitmap.CompressFormat.JPEG, 86, output)) { "Не удалось подготовить изображение." }
            output.toByteArray()
        }
    } finally {
        if (working !== decoded) working.recycle()
        decoded.recycle()
    }
}

private const val MAX_AVATAR_INPUT_BYTES = 10 * 1024 * 1024
