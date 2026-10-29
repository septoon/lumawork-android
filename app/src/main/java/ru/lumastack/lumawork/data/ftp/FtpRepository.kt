package ru.lumastack.lumawork.data.ftp

import ru.lumastack.lumawork.data.simpleone.SimpleOneAccess
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
data class FtpSnapshot(val directories: Map<String, List<FtpItem>> = emptyMap(), val updatedAtEpochMillis: Long? = null, val favoritePaths: List<String> = emptyList(), val revision: Long = 0)

sealed class FtpFailure(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Network(cause: IOException) : FtpFailure("Не удалось подключиться к FTP.", cause)
    data object Unauthorized : FtpFailure("Сессия приложения «Инженер» истекла.")
    class Http(val status: Int) : FtpFailure(when (status) { 401, 403 -> "Сессия приложения «Инженер» истекла. Войдите снова."; 404 -> "Файл или папка больше не находятся по этому пути. Обновите каталог."; else -> "Не удалось выполнить действие с файлом. Попробуйте ещё раз." })
    data object InvalidResponse : FtpFailure("FTP вернул некорректные данные.")
}

interface FtpApi {
    suspend fun files(token: String, path: String): FtpDirectoryResponse
}

class OkHttpFtpApi(
    origin: String,
    private val json: Json,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true).build(),
) : FtpApi {
    private val base = origin.trimEnd('/').toHttpUrl()

    override suspend fun files(token: String, path: String): FtpDirectoryResponse = withContext(Dispatchers.IO) {
        val url = base.newBuilder().addPathSegments("api/v2/ftp/files").addQueryParameter("path", path).build()
        execute(request(url.toString(), token)).use { response ->
            runCatching { json.decodeFromString<FtpDirectoryResponse>(response.body.string()) }
                .getOrElse { throw FtpFailure.InvalidResponse }
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
        if (!tmp.renameTo(file)) { tmp.delete(); throw IOException("Не удалось сохранить каталог FTP.") }
    }
    private fun file(id: String) = File(directory, "${id.sha256()}.json")
    private fun String.sha256() = MessageDigest.getInstance("SHA-256").digest(toByteArray()).joinToString("") { "%02x".format(it) }
}

class FtpRepository(
    private val api: FtpApi,
    private val cache: FtpCache,
    val transfers: FtpTransfers? = null,
    val access: SimpleOneAccess = SimpleOneAccess.DenyAll,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) {
    suspend fun read(ownerUserId: String): FtpSnapshot? = access.protect(ownerUserId) { cache.read(ownerUserId) }
    private val mutex = Mutex()
    suspend fun load(ownerUserId: String, token: String, path: String): FtpSnapshot = access.protect(ownerUserId) {
        val normalized = normalizeFtpPath(path)
        val response = api.files(token, normalized)
        mutex.withLock {
            access.require(ownerUserId)
            val previous = read(ownerUserId) ?: FtpSnapshot()
            previous.copy(directories = previous.directories + (normalized to response.items), updatedAtEpochMillis = nowEpochMillis(), revision = previous.revision + 1)
                .also { cache.save(ownerUserId, it) }
        }
    }
    suspend fun toggleFavorite(owner: String, path: String): FtpSnapshot = access.protect(owner) { mutex.withLock {
        val normalized = normalizeFtpPath(path)
        val previous = read(owner) ?: FtpSnapshot()
        if (normalized == "/") return@withLock previous
        val paths = if (normalized in previous.favoritePaths) previous.favoritePaths - normalized else previous.favoritePaths + normalized
        previous.copy(favoritePaths = paths.distinct().sortedWith(String.CASE_INSENSITIVE_ORDER), revision = previous.revision + 1).also { cache.save(owner, it) }
    } }

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
