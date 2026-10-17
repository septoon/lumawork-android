package ru.lumastack.lumawork.data.fuel

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import ru.lumastack.lumawork.BuildConfig
import ru.lumastack.lumawork.domain.fuel.*

interface FuelImportApi {
    suspend fun preview(token: String, files: List<FuelImportUpload>): List<FuelImportPreviewItem>
    suspend fun commit(token: String, files: List<FuelImportUpload>, replacementIds: Set<String>, corrections: List<FuelImportCorrection>): FuelImportCommitResponse
}

sealed class FuelImportFailure(message: String, cause: Throwable? = null) : Exception(message, cause) {
    data object Unauthorized : FuelImportFailure("Сессия приложения «Инженер» истекла.")
    data object InvalidResponse : FuelImportFailure("Не удалось разобрать ответ сервера об импорте.")
    class Network(cause: IOException) : FuelImportFailure("Не удалось импортировать отчёты. Проверьте подключение к интернету.", cause)
    class Http(val status: Int) : FuelImportFailure("Не удалось импортировать отчёты. Попробуйте ещё раз.")
}

class OkHttpFuelImportApi(
    baseOrigin: String = BuildConfig.API_ORIGIN,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false },
    private val client: OkHttpClient = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS).writeTimeout(60, TimeUnit.SECONDS).retryOnConnectionFailure(false).build(),
) : FuelImportApi {
    private val origin = baseOrigin.trimEnd('/')

    override suspend fun preview(token: String, files: List<FuelImportUpload>): List<FuelImportPreviewItem> = withContext(Dispatchers.IO) {
        execute<FuelImportPreviewResponse>(token, "/api/v2/fuel/imports/preview", json.encodeToString(FuelImportPreviewRequest(files))).items
    }

    override suspend fun commit(token: String, files: List<FuelImportUpload>, replacementIds: Set<String>, corrections: List<FuelImportCorrection>): FuelImportCommitResponse = withContext(Dispatchers.IO) {
        execute<FuelImportCommitResponse>(token, "/api/v2/fuel/imports/commit", json.encodeToString(FuelImportCommitRequest(
            files, replacementIds.toList(),
            // Error previews have an empty hash. Sending one correction for these rejects the entire batch.
            corrections.filter { Regex("[a-f0-9]{64}").matches(it.fileHash) }.map { it.payload() },
        )))
    }

    private suspend inline fun <reified Value> execute(token: String, path: String, body: String): Value {
        if (token.isBlank()) throw FuelImportFailure.Unauthorized
        val request = Request.Builder().url("$origin$path").header("Accept", "application/json")
            .header("Authorization", "Bearer $token").header("Cache-Control", "no-cache")
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        val response = try { client.newCall(request).awaitFuelImport() } catch (error: IOException) { throw FuelImportFailure.Network(error) }
        response.use {
            if (it.code == 401 || it.code == 403) throw FuelImportFailure.Unauthorized
            if (!it.isSuccessful) throw FuelImportFailure.Http(it.code)
            return try { json.decodeFromString<Value>(it.body.string()) } catch (_: Exception) { throw FuelImportFailure.InvalidResponse }
        }
    }
}

@Serializable private data class FuelImportPreviewRequest(val files: List<FuelImportUpload>)
@Serializable private data class FuelImportPreviewResponse(val items: List<FuelImportPreviewItem>)
@Serializable private data class FuelImportCommitRequest(val files: List<FuelImportUpload>, val replaceImportIds: List<String>, val corrections: List<FuelImportCorrectionPayload>)

private suspend fun Call.awaitFuelImport(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
        override fun onResponse(call: Call, response: Response) { if (continuation.isActive) continuation.resume(response) else response.close() }
    })
}
