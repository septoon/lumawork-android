package ru.lumastack.lumawork.data.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

class UpdateRepository(private val context: Context) {
    @Volatile private var activeDownload: okhttp3.Call? = null
    fun cancelDownload() { activeDownload?.cancel() }
    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS).callTimeout(5, TimeUnit.MINUTES)
        .followRedirects(false).followSslRedirects(false).build()
    @Suppress("DEPRECATION")
    fun installed(): PackageInfo = context.packageManager.getPackageInfo(context.packageName, signatureFlags())
    @Suppress("DEPRECATION")
    fun code(info: PackageInfo): Long = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
    @Suppress("DEPRECATION")
    private fun signatureFlags() = PackageManager.GET_SIGNATURES or
        if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else 0
    @Suppress("DEPRECATION")
    private fun signatures(info: PackageInfo): Set<String> {
        // Some Android 10 Samsung builds omit archive.signingInfo even when
        // GET_SIGNING_CERTIFICATES is requested. Request both representations
        // and use the legacy certificates only when the modern set is absent.
        val modern = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else null
        return (modern?.takeIf { it.isNotEmpty() } ?: info.signatures)
            .orEmpty().map { digest(it.toByteArray()) }.toSet()
    }
    suspend fun check(): UpdateRelease = withContext(Dispatchers.IO) {
        client.newCall(Request.Builder().url(UpdateRelease.METADATA_URL).header("Cache-Control", "no-cache").build()).execute().use { response ->
            check(response.isSuccessful) { "Сервер обновлений недоступен (HTTP ${response.code})." }
            val body = requireNotNull(response.body)
            check(body.contentLength() <= 131072) { "Слишком большой ответ сервера." }
            val bytes = body.byteStream().use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (output.size() <= 131072) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            check(bytes.size <= 131072) { "Слишком большой ответ сервера." }
            UpdateRelease.parse(bytes.toString(Charsets.UTF_8))
        }
    }
    suspend fun download(release: UpdateRelease, progress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        check(release.isNewerThan(code(installed()))) { "Эта версия уже установлена." }
        check(release.minSdk <= Build.VERSION.SDK_INT) { "Обновление несовместимо с этой версией Android." }
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        check(dir.usableSpace > release.size * 2) { "Недостаточно свободного места для обновления." }
        val target = File(dir, "update.apk")
        val part = File(dir, "update.part")
        target.delete()
        try {
            val call = client.newCall(Request.Builder().url(release.downloadUrl).build())
            activeDownload = call
            currentCoroutineContext().ensureActive()
            call.execute().use { response ->
                check(response.isSuccessful) { "Ошибка загрузки APK (HTTP ${response.code})." }
                val body = requireNotNull(response.body)
                check(body.contentLength() == release.size) { "Размер APK не совпадает с релизом." }
                var received = 0L
                var lastProgress = -1
                val hash = MessageDigest.getInstance("SHA-256")
                body.byteStream().use { input -> part.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        received += count
                        check(received <= release.size) { "APK больше ожидаемого размера." }
                        output.write(buffer, 0, count); hash.update(buffer, 0, count)
                        val percent = (received * 100 / release.size).toInt()
                        if (percent != lastProgress) { lastProgress = percent; progress(received.toFloat() / release.size) }
                    }
                } }
                check(received == release.size) { "Загрузка APK не завершена." }
                check(hex(hash.digest()) == release.sha256) { "SHA-256 не совпадает. Повторите загрузку." }
            }
            verifyArchive(part, release)
            check(part.renameTo(target)) { "Не удалось сохранить APK." }
            target
        } finally { activeDownload = null; part.delete() }
    }
    @Suppress("DEPRECATION")
    fun verifyArchive(file: File, release: UpdateRelease) {
        check(file.isFile && file.length() == release.size && file.inputStream().use { input ->
            val hash = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(65536)
            while (true) { val count = input.read(buffer); if (count < 0) break; hash.update(buffer, 0, count) }
            hex(hash.digest())
        } == release.sha256) { "Файл обновления повреждён. Загрузите его снова." }
        val archive = context.packageManager.getPackageArchiveInfo(file.path, signatureFlags()) ?: error("Android не распознал APK.")
        check(archive.packageName == context.packageName) { "APK принадлежит другому приложению." }
        check(code(archive) == release.versionCode && archive.versionName == release.versionName) { "Версия APK не совпадает с метаданными." }
        check(code(archive) > code(installed())) { "Понижение версии запрещено." }
        check(archive.applicationInfo?.minSdkVersion == release.minSdk && release.minSdk <= Build.VERSION.SDK_INT) { "Несовместимая версия Android." }
        val currentSigners = signatures(installed())
        val archiveSigners = signatures(archive)
        check(currentSigners.isNotEmpty() && archiveSigners.isNotEmpty()) {
            "Android не смог прочитать сертификат APK. Повторите загрузку обновления."
        }
        check(currentSigners == archiveSigners && archiveSigners == setOf(release.signingCertificateSHA256)) {
            "Подпись APK отличается от установленного приложения. Не удаляйте текущую версию."
        }
    }
    private fun digest(bytes: ByteArray) = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
}
