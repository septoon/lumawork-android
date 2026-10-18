package ru.lumastack.lumawork.data.salary

import java.io.IOException
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

interface SalaryDocumentsApi {
    suspend fun fetch(token: String): List<SalaryDocument>
    suspend fun upload(token: String, month: String, input: SalaryDocumentInput, onProgress: (Double) -> Unit): SalaryDocument
    suspend fun download(token: String, month: String): ByteArray
    suspend fun delete(token: String, month: String)
}

class SalaryDocumentsFailure(message: String) : Exception(message)

class OkHttpSalaryDocumentsApi(
    baseOrigin: String,
    private val json: Json,
    private val client: OkHttpClient = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS).writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build(),
) : SalaryDocumentsApi {
    private val origin = baseOrigin.trimEnd('/').toHttpUrl()

    override suspend fun fetch(token: String): List<SalaryDocument> {
        val documents = execute<SalaryDocumentsResponse>(request(token)).documents
        documents.forEach { it.validate() }
        if (documents.map { it.month }.distinct().size != documents.size) throw invalidResponse()
        return documents.sortedByDescending { it.month }
    }

    override suspend fun upload(token: String, month: String, input: SalaryDocumentInput, onProgress: (Double) -> Unit): SalaryDocument = withContext(Dispatchers.IO) {
        val total = (input.data.size + SalaryDocumentInput.CHUNK_BYTES - 1) / SalaryDocumentInput.CHUNK_BYTES
        val uploadId = UUID.randomUUID().toString()
        var uploaded: SalaryDocument? = null
        repeat(total) { index ->
            currentCoroutineContext().ensureActive()
            val offset = index * SalaryDocumentInput.CHUNK_BYTES
            val chunk = input.data.copyOfRange(offset, minOf(offset + SalaryDocumentInput.CHUNK_BYTES, input.data.size))
            val payload = json.encodeToString(SalaryChunkPayload.serializer(), SalaryChunkPayload(uploadId, input.fileName, input.mimeType, index, total, Base64.getEncoder().encodeToString(chunk)))
            val response = execute<SalaryChunkResponse>(request(token, month, "POST", payload))
            if (index == total - 1) {
                uploaded = response.document?.validate()?.takeIf { it.month == month && it.sizeBytes == input.data.size && it.mimeType == input.mimeType }
                    ?: throw invalidResponse()
            }
            onProgress((index + 1).toDouble() / total)
        }
        uploaded ?: throw invalidResponse()
    }

    override suspend fun download(token: String, month: String): ByteArray = withContext(Dispatchers.IO) {
        response(request(token, month)).use { result ->
            if (result.body.contentLength() > SalaryDocumentInput.MAX_BYTES) throw invalidResponse()
            result.body.byteStream().use(::readSalaryDocumentBytes).also { if (it.isEmpty()) throw invalidResponse() }
        }
    }

    override suspend fun delete(token: String, month: String) = withContext(Dispatchers.IO) {
        response(request(token, month, "DELETE")).close()
    }

    private fun request(token: String, month: String? = null, method: String = "GET", payload: String? = null): Request {
        if (token.isBlank()) throw SalaryDocumentsFailure("Сессия приложения «Инженер» истекла.")
        if (month != null) require(SALARY_DOCUMENT_MONTH.matches(month)) { "Некорректный месяц начисления." }
        val url = origin.newBuilder().addPathSegments("api/v2/salary/documents").apply {
            if (month != null) addPathSegment(month)
            if (method == "POST") addPathSegment("chunk")
        }.build()
        val builder = Request.Builder().url(url).header("Authorization", "Bearer $token").header("Cache-Control", "no-cache")
        return when (method) {
            "POST" -> builder.post(requireNotNull(payload).toRequestBody("application/json; charset=utf-8".toMediaType())).build()
            "DELETE" -> builder.delete().build()
            else -> builder.get().build()
        }
    }

    private suspend inline fun <reified T> execute(request: Request): T = withContext(Dispatchers.IO) {
        response(request).use { result ->
            if (result.body.contentLength() > 2 * 1024 * 1024) throw invalidResponse()
            val bytes = result.body.byteStream().use { stream ->
                val buffer = java.io.ByteArrayOutputStream()
                val part = ByteArray(8192)
                while (true) {
                    val count = stream.read(part)
                    if (count < 0) break
                    if (buffer.size() + count > 2 * 1024 * 1024) throw invalidResponse()
                    buffer.write(part, 0, count)
                }
                buffer.toByteArray()
            }
            try { json.decodeFromString<T>(bytes.toString(Charsets.UTF_8)) } catch (_: IllegalArgumentException) { throw invalidResponse() }
        }
    }

    private suspend fun response(request: Request): Response {
        val response = try { client.newCall(request).awaitSalaryDocument() } catch (_: IOException) {
            throw SalaryDocumentsFailure("Не удалось подключиться. Проверьте подключение к интернету.")
        }
        if (!response.isSuccessful) {
            val code = response.code
            response.close()
            throw SalaryDocumentsFailure(when (code) {
                401, 403 -> "Сессия приложения «Инженер» истекла."
                413 -> "Расчётный листок превышает допустимый размер."
                404 -> "Расчётный листок не найден на сервере. Обновите список."
                else -> "Не удалось выполнить действие с расчётным листком. Попробуйте ещё раз."
            })
        }
        return response
    }

    private fun invalidResponse() = SalaryDocumentsFailure("Сервер не подтвердил действие с расчётным листком.")
}

@Serializable private data class SalaryDocumentsResponse(val documents: List<SalaryDocument>)
@Serializable private data class SalaryChunkResponse(val document: SalaryDocument? = null)
@Serializable private data class SalaryChunkPayload(val uploadId: String, val fileName: String, val mimeType: String, val chunkIndex: Int, val totalChunks: Int, val chunkBase64: String)

private suspend fun Call.awaitSalaryDocument(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
        override fun onResponse(call: Call, response: Response) { if (continuation.isActive) continuation.resume(response) else response.close() }
    })
}
