package ru.lumastack.lumawork.data.ftp

import android.content.Context
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

@Serializable
enum class FtpItemType {
    @SerialName("directory") Directory,
    @SerialName("file") File,
}

@Serializable
data class FtpItem(
    val name: String,
    val path: String,
    val type: FtpItemType,
    val size: Long? = null,
    val sizeText: String? = null,
    val modifiedAt: String? = null,
) {
    val isDirectory: Boolean get() = type == FtpItemType.Directory
}

@Serializable
data class FtpDirectoryResponse(val path: String, val items: List<FtpItem> = emptyList())

@Serializable
data class FtpSnapshot(val directories: Map<String, List<FtpItem>> = emptyMap(), val updatedAtEpochMillis: Long? = null)

sealed class FtpFailure(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Network(cause: IOException) : FtpFailure("Не удалось подключиться к FTP.", cause)
    data object Unauthorized : FtpFailure("Сессия приложения «Инженер» истекла.")
    class Http(status: Int) : FtpFailure("Не удалось выполнить действие с файлом. Попробуйте ещё раз.")
    data object InvalidResponse : FtpFailure("FTP вернул некорректные данные.")
}

interface FtpApi {
    suspend fun files(token: String, path: String): FtpDirectoryResponse
    suspend fun download(token: String, item: FtpItem): File
}

class OkHttpFtpApi(
    origin: String,
    private val json: Json,
    context: Context,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(15, TimeUnit.MINUTES)
        .retryOnConnectionFailure(true).build(),
) : FtpApi {
    private val base = origin.trimEnd('/').toHttpUrl()
    private val downloads = File(context.cacheDir, "ftp-downloads")

    override suspend fun files(token: String, path: String): FtpDirectoryResponse {
        val url = base.newBuilder().addPathSegments("api/v2/ftp/files").addQueryParameter("path", path).build()
        return execute(request(url.toString(), token)).use { response ->
            runCatching { json.decodeFromString<FtpDirectoryResponse>(response.body.string()) }
                .getOrElse { throw FtpFailure.InvalidResponse }
        }
    }

    override suspend fun download(token: String, item: FtpItem): File = withContext(Dispatchers.IO) {
        val endpoint = if (item.isDirectory) "api/v2/ftp/download-folder" else "api/v2/ftp/download"
        val url = base.newBuilder().addPathSegments(endpoint).addQueryParameter("path", item.path).build()
        val response = execute(request(url.toString(), token))
        response.use {
            downloads.mkdirs()
            val suffix = if (item.isDirectory && !item.name.endsWith(".zip", true)) ".zip" else ""
            val name = (item.name.ifBlank { "ftp-download" } + suffix).replace(Regex("[/\\\\:*?\"<>|]"), "_")
            val destination = File(downloads, name)
            destination.outputStream().use { output -> it.body.byteStream().use { input -> input.copyTo(output) } }
            destination
        }
    }

    private fun request(url: String, token: String) = Request.Builder().url(url)
        .header("Authorization", "Bearer $token").header("Cache-Control", "no-cache").build()

    private suspend fun execute(request: Request): Response {
        val response = try { client.newCall(request).awaitFtp() } catch (error: IOException) { throw FtpFailure.Network(error) }
        if (response.code == 401 || response.code == 403) { response.close(); throw FtpFailure.Unauthorized }
        if (!response.isSuccessful) { val code = response.code; response.close(); throw FtpFailure.Http(code) }
        return response
    }
}

interface FtpCache {
    suspend fun read(ownerUserId: String): FtpSnapshot?
    suspend fun save(ownerUserId: String, snapshot: FtpSnapshot)
}

class FileFtpCache(context: Context, private val json: Json) : FtpCache {
    private val directory = File(context.noBackupFilesDir, "ftp")
    override suspend fun read(ownerUserId: String): FtpSnapshot? = withContext(Dispatchers.IO) {
        val file = file(ownerUserId)
        if (!file.isFile) return@withContext null
        runCatching { json.decodeFromString<FtpSnapshot>(file.readText()) }.getOrNull()
    }
    override suspend fun save(ownerUserId: String, snapshot: FtpSnapshot) = withContext(Dispatchers.IO) {
        val file = file(ownerUserId); file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp"); tmp.writeText(json.encodeToString(snapshot))
        if (!tmp.renameTo(file)) { file.writeText(tmp.readText()); tmp.delete() }
    }
    private fun file(id: String) = File(directory, "${id.sha256()}.json")
    private fun String.sha256() = MessageDigest.getInstance("SHA-256").digest(toByteArray()).joinToString("") { "%02x".format(it) }
}

class FtpRepository(
    private val api: FtpApi,
    private val cache: FtpCache,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) {
    suspend fun read(ownerUserId: String): FtpSnapshot? = cache.read(ownerUserId)
    suspend fun load(ownerUserId: String, token: String, path: String): FtpSnapshot {
        val previous = read(ownerUserId) ?: FtpSnapshot()
        val response = api.files(token, path)
        return previous.copy(
            directories = previous.directories + (path to response.items.sortedWith(compareByDescending<FtpItem> { it.isDirectory }.thenBy { it.name.lowercase() })),
            updatedAtEpochMillis = nowEpochMillis(),
        ).also { cache.save(ownerUserId, it) }
    }
    suspend fun download(token: String, item: FtpItem): File = api.download(token, item)
}

private suspend fun Call.awaitFtp(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
        override fun onResponse(call: Call, response: Response) {
            if (continuation.isActive) continuation.resume(response) else response.close()
        }
    })
}
