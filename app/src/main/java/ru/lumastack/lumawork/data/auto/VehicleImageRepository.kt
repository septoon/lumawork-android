package ru.lumastack.lumawork.data.auto

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

class VehicleImageRepository(
    context: Context,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build(),
) {
    private val directory = File(context.cacheDir, "vehicle-images")
    private val memory = object : LruCache<String, Bitmap>(MEMORY_CACHE_KIB) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount / 1024
    }

    suspend fun load(url: String): Bitmap? {
        if (url.isBlank()) return null
        memory.get(url)?.let { return it }
        return withContext(Dispatchers.IO) {
            memory.get(url)?.let { return@withContext it }
            directory.mkdirs()
            val file = File(directory, url.sha256())
            if (!file.isFile) {
                val bytes = download(url) ?: return@withContext null
                file.writeBytes(bytes)
                trimDiskCache()
            }
            decodeSampled(file)?.also { memory.put(url, it) }
        }
    }

    private suspend fun download(url: String): ByteArray? {
        val call = runCatching { client.newCall(Request.Builder().url(url).get().build()) }.getOrNull() ?: return null
        val response = try {
            call.awaitVehicleImage()
        } catch (_: IOException) {
            return null
        }
        return response.use {
            if (!it.isSuccessful || it.body.contentLength() > MAX_IMAGE_BYTES) return null
            it.body.bytes().takeIf { bytes -> bytes.isNotEmpty() && bytes.size <= MAX_IMAGE_BYTES }
        }
    }

    private fun decodeSampled(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            file.delete()
            return null
        }
        var sample = 1
        while (bounds.outWidth / sample > MAX_BITMAP_SIDE * 2 || bounds.outHeight / sample > MAX_BITMAP_SIDE * 2) sample *= 2
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        })
    }

    private fun trimDiskCache() {
        val files = directory.listFiles()?.sortedByDescending(File::lastModified).orEmpty()
        var total = 0L
        files.forEach { file ->
            total += file.length()
            if (total > DISK_CACHE_BYTES) file.delete()
        }
    }

    private fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(toByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        private const val MEMORY_CACHE_KIB = 6 * 1024
        private const val DISK_CACHE_BYTES = 16L * 1024 * 1024
        private const val MAX_IMAGE_BYTES = 5 * 1024 * 1024
        private const val MAX_BITMAP_SIDE = 720
    }
}

private suspend fun Call.awaitVehicleImage(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            if (continuation.isActive) continuation.resume(response) else response.close()
        }
    })
}
