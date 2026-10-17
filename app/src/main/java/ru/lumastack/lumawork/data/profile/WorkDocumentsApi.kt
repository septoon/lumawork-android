package ru.lumastack.lumawork.data.profile

import java.io.ByteArrayOutputStream
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
import ru.lumastack.lumawork.domain.profile.*

interface WorkDocumentsApi {
    suspend fun fetch(token: String): List<WorkDocument>
    suspend fun upload(token: String, input: WorkDocumentInput, documentId: String, category: WorkDocumentCategory, title: String, onProgress: (Double) -> Unit): WorkDocument
    suspend fun update(token: String, document: WorkDocument): WorkDocument
    suspend fun download(token: String, documentId: String): ByteArray
    suspend fun delete(token: String, documentId: String)
}

sealed class WorkDocumentsFailure(message: String) : Exception(message) {
    data object Unauthorized : WorkDocumentsFailure("Сессия приложения «Инженер» истекла.")
    data object Network : WorkDocumentsFailure("Не удалось подключиться. Проверьте подключение к интернету.")
    data object Offline : WorkDocumentsFailure("Для работы с документами требуется подключение к интернету.")
    data object InvalidResponse : WorkDocumentsFailure("Сервер не подтвердил действие с документом.")
    class Http(val status: Int) : WorkDocumentsFailure("Не удалось выполнить действие с документом. Попробуйте ещё раз.")
    class Document(message: String) : WorkDocumentsFailure(message)
}

class OkHttpWorkDocumentsApi(
    baseOrigin: String,
    private val json: Json,
    private val client: OkHttpClient = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS).writeTimeout(30, TimeUnit.SECONDS).build(),
) : WorkDocumentsApi {
    private val origin = baseOrigin.trimEnd('/').toHttpUrl()
    override suspend fun fetch(token: String): List<WorkDocument> =
        execute<DocumentsResponse>(request(token)).documents.filter { it.id.isNotBlank() }

    override suspend fun upload(token: String, input: WorkDocumentInput, documentId: String, category: WorkDocumentCategory, title: String, onProgress: (Double) -> Unit): WorkDocument = withContext(Dispatchers.IO) {
        val cleanTitle = validatedWorkDocumentTitle(title)
        val uploadId = UUID.randomUUID().toString()
        val total = (input.data.size + WorkDocumentInput.CHUNK_BYTES - 1) / WorkDocumentInput.CHUNK_BYTES
        var completed: WorkDocument? = null
        repeat(total) { index ->
            currentCoroutineContext().ensureActive()
            val offset = index * WorkDocumentInput.CHUNK_BYTES
            val chunk = input.data.copyOfRange(offset, minOf(offset + WorkDocumentInput.CHUNK_BYTES, input.data.size))
            val payload = json.encodeToString(ChunkPayload.serializer(), ChunkPayload(uploadId, documentId, category.wireValue, cleanTitle,
                input.fileName, input.mimeType, index, total, Base64.getEncoder().encodeToString(chunk)))
            val response = execute<ChunkResponse>(request(token, "chunk", "POST", payload))
            if (index == total - 1) {
                if (!response.complete || response.document?.id != documentId) throw WorkDocumentsFailure.InvalidResponse
                completed = response.document
            }
            onProgress((index + 1).toDouble() / total)
        }
        completed ?: throw WorkDocumentsFailure.InvalidResponse
    }

    override suspend fun update(token: String, document: WorkDocument): WorkDocument {
        val payload = json.encodeToString(MetadataPayload.serializer(), MetadataPayload(document.category.wireValue, validatedWorkDocumentTitle(document.title)))
        val updated = execute<DocumentResponse>(request(token, document.id, "PATCH", payload)).document
        if (updated.id != document.id) throw WorkDocumentsFailure.InvalidResponse
        return updated
    }

    override suspend fun download(token: String, documentId: String): ByteArray = withContext(Dispatchers.IO) {
        response(request(token, documentId)).use { response ->
            if (response.body.contentLength() > WorkDocumentInput.MAX_BYTES) throw WorkDocumentsFailure.InvalidResponse
            response.body.byteStream().use { stream ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = stream.read(buffer)
                    if (count == -1) break
                    if (output.size() + count > WorkDocumentInput.MAX_BYTES) throw WorkDocumentsFailure.InvalidResponse
                    output.write(buffer, 0, count)
                }
                output.toByteArray().also { if (it.isEmpty()) throw WorkDocumentsFailure.InvalidResponse }
            }
        }
    }

    override suspend fun delete(token: String, documentId: String) {
        withContext(Dispatchers.IO) { response(request(token, documentId, "DELETE")).close() }
    }

    private fun request(token: String, segment: String? = null, method: String = "GET", payload: String? = null): Request {
        if (token.isBlank()) throw WorkDocumentsFailure.Unauthorized
        val url = origin.newBuilder().addPathSegments("api/v2/work-documents").apply { if (segment != null) addPathSegment(segment) }.build()
        val request = Request.Builder().url(url).header("Authorization", "Bearer $token")
            .header("Cache-Control", "no-cache").header("Accept", "application/json")
        return when (method) {
            "POST", "PATCH" -> request.method(method, requireNotNull(payload).toRequestBody("application/json; charset=utf-8".toMediaType())).build()
            "DELETE" -> request.delete().build()
            else -> request.get().build()
        }
    }

    private suspend inline fun <reified T> execute(request: Request): T = withContext(Dispatchers.IO) {
        response(request).use { response ->
            val text = response.body.string()
            try { json.decodeFromString<T>(text) } catch (_: IllegalArgumentException) { throw WorkDocumentsFailure.InvalidResponse }
        }
    }

    private suspend fun response(request: Request): Response {
        val response = try { client.newCall(request).awaitDocument() } catch (_: IOException) { throw WorkDocumentsFailure.Network }
        if (response.code == 401 || response.code == 403) { response.close(); throw WorkDocumentsFailure.Unauthorized }
        if (!response.isSuccessful) {
            val status = response.code
            val message = response.use {
                // Read a bounded error and never display backend diagnostic/authentication contents.
                val source = it.body.source()
                source.request(4097)
                val text = source.buffer.clone().readUtf8(minOf(source.buffer.size, 4096))
                runCatching { json.decodeFromString<ErrorResponse>(text).message?.trim() }.getOrNull()
                    ?.takeIf { value -> value.isNotBlank() && value.length <= 300 && !value.contains(request.header("Authorization").orEmpty().removePrefix("Bearer ")) }
            }
            if (message != null) throw WorkDocumentsFailure.Document(message)
            throw WorkDocumentsFailure.Http(status)
        }
        return response
    }
}

@Serializable private data class DocumentsResponse(val documents: List<WorkDocument> = emptyList())
@Serializable private data class DocumentResponse(val document: WorkDocument)
@Serializable private data class ChunkResponse(val complete: Boolean = false, val document: WorkDocument? = null)
@Serializable private data class MetadataPayload(val category: String, val title: String)
@Serializable private data class ErrorResponse(val message: String? = null)
@Serializable private data class ChunkPayload(val uploadId: String, val documentId: String, val category: String, val title: String,
    val fileName: String, val mimeType: String, val chunkIndex: Int, val totalChunks: Int, val chunkBase64: String)

private suspend fun Call.awaitDocument(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
        override fun onResponse(call: Call, response: Response) { if (continuation.isActive) continuation.resume(response) else response.close() }
    })
}
