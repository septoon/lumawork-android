package ru.lumastack.lumawork.data.fuel

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

interface FuelReceiptsApi {
    suspend fun fetch(token: String): List<FuelReceipt>
    suspend fun upload(token: String, month: String, receiptId: String, kind: String, input: FuelReceiptInput, onProgress: (Double) -> Unit): FuelReceipt
    suspend fun download(token: String, receiptId: String): ByteArray
    suspend fun delete(token: String, receiptId: String)
}

class FuelReceiptsFailure(message: String, val status: Int? = null) : Exception(message)

class OkHttpFuelReceiptsApi(
    baseOrigin: String, private val json: Json,
    private val client: OkHttpClient = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS).writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build(),
) : FuelReceiptsApi {
    private val origin = baseOrigin.trimEnd('/').toHttpUrl()
    override suspend fun fetch(token: String): List<FuelReceipt> {
        val receipts = execute<ReceiptsResponse>(request(token)).receipts.onEach { it.validate() }
        if (receipts.map { it.id }.distinct().size != receipts.size) throw invalidResponse()
        return receipts.sortedByDescending { it.createdAt }
    }
    override suspend fun upload(token: String, month: String, receiptId: String, kind: String, input: FuelReceiptInput, onProgress: (Double) -> Unit): FuelReceipt = withContext(Dispatchers.IO) {
        require(FUEL_RECEIPT_MONTH.matches(month) && FUEL_RECEIPT_ID.matches(receiptId) && kind in FUEL_RECEIPT_KINDS) { "Некорректные данные чека." }
        val total = (input.data.size + FuelReceiptInput.CHUNK_BYTES - 1) / FuelReceiptInput.CHUNK_BYTES
        require(total in 1..64)
        val uploadId = UUID.randomUUID().toString()
        var uploaded: FuelReceipt? = null
        repeat(total) { index ->
            currentCoroutineContext().ensureActive()
            val offset = index * FuelReceiptInput.CHUNK_BYTES
            val chunk = input.data.copyOfRange(offset, minOf(offset + FuelReceiptInput.CHUNK_BYTES, input.data.size))
            val payload = json.encodeToString(ChunkPayload.serializer(), ChunkPayload(uploadId, kind, input.fileName, input.mimeType, index, total, Base64.getEncoder().encodeToString(chunk)))
            val response = execute<ChunkResponse>(request(token, listOf(month, receiptId, "chunk"), "POST", payload))
            if (index == total - 1) {
                uploaded = response.receipt?.validate()?.takeIf {
                    response.complete && it.id == receiptId && it.month == month && it.kind == kind && it.sizeBytes == input.data.size && it.mimeType == input.mimeType
                } ?: throw invalidResponse()
            } else if (response.complete || response.received != index + 1 || response.totalChunks != total) throw invalidResponse()
            onProgress((index + 1).toDouble() / total)
        }
        uploaded ?: throw invalidResponse()
    }
    override suspend fun download(token: String, receiptId: String): ByteArray = withContext(Dispatchers.IO) {
        require(FUEL_RECEIPT_ID.matches(receiptId))
        response(request(token, listOf(receiptId))).use { result ->
            if (result.body.contentLength() > FuelReceiptInput.MAX_BYTES) throw invalidResponse()
            result.body.byteStream().use { readFuelReceiptBytes(it) }.also { if (it.isEmpty()) throw invalidResponse() }
        }
    }
    override suspend fun delete(token: String, receiptId: String) = withContext(Dispatchers.IO) {
        require(FUEL_RECEIPT_ID.matches(receiptId))
        response(request(token, listOf(receiptId), "DELETE")).use { if (it.code != 204) throw invalidResponse() }
    }
    private fun request(token: String, path: List<String> = emptyList(), method: String = "GET", payload: String? = null): Request {
        if (token.isBlank()) throw FuelReceiptsFailure("Сессия приложения «Инженер» истекла.")
        val url = origin.newBuilder().addPathSegments("api/v2/fuel/receipts").apply { path.forEach { addPathSegment(it) } }.build()
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
            val bytes = result.body.byteStream().use { readFuelReceiptBytes(it, 2 * 1024 * 1024) }
            try { json.decodeFromString<T>(bytes.toString(Charsets.UTF_8)) } catch (_: IllegalArgumentException) { throw invalidResponse() }
        }
    }
    private suspend fun response(request: Request): Response {
        val response = try { client.newCall(request).awaitReceipt() } catch (_: IOException) {
            throw FuelReceiptsFailure("Не удалось подключиться. Проверьте подключение к интернету.")
        }
        if (!response.isSuccessful) {
            val code = response.code
            response.close()
            throw FuelReceiptsFailure(when (code) {
                401, 403 -> "Сессия приложения «Инженер» истекла."
                413 -> "Чек не должен превышать 20 МБ."
                404 -> "Чек не найден на сервере. Обновите список."
                else -> "Не удалось выполнить действие с чеком. Попробуйте ещё раз."
            }, status = code)
        }
        return response
    }
    private fun invalidResponse() = FuelReceiptsFailure("Сервер не подтвердил действие с чеком.")
}
@Serializable private data class ReceiptsResponse(val receipts: List<FuelReceipt>)
@Serializable private data class ChunkResponse(val complete: Boolean, val received: Int? = null, val totalChunks: Int? = null, val receipt: FuelReceipt? = null)
@Serializable private data class ChunkPayload(val uploadId: String, val kind: String, val fileName: String, val mimeType: String, val chunkIndex: Int, val totalChunks: Int, val chunkBase64: String)
private suspend fun Call.awaitReceipt(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
        override fun onResponse(call: Call, response: Response) { if (continuation.isActive) continuation.resume(response) else response.close() }
    })
}
