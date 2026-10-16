package ru.lumastack.lumawork.data.auto

import java.io.IOException
import java.util.UUID
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import ru.lumastack.lumawork.domain.auto.VehicleDocument
import ru.lumastack.lumawork.domain.auto.VehicleDocumentInput
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import ru.lumastack.lumawork.domain.auto.MaintenanceDraft
import ru.lumastack.lumawork.domain.auto.MaintenancePart
import ru.lumastack.lumawork.domain.auto.MaintenanceRecord
import ru.lumastack.lumawork.domain.auto.Vehicle
import ru.lumastack.lumawork.domain.auto.VehicleDraft

interface AutoApi {
    suspend fun vehicles(token: String): List<Vehicle>
    suspend fun createVehicle(token: String, draft: VehicleDraft): Vehicle
    suspend fun updateVehicle(token: String, vehicleId: String, draft: VehicleDraft): Vehicle
    suspend fun uploadDocument(token: String, vehicleId: String, input: VehicleDocumentInput, onProgress: (Double) -> Unit): VehicleDocument
    suspend fun downloadDocument(token: String, vehicleId: String, documentId: String): ByteArray
    suspend fun deleteDocument(token: String, vehicleId: String, documentId: String)
    suspend fun maintenance(token: String): List<MaintenanceRecord>
    suspend fun createMaintenance(token: String, draft: MaintenanceDraft): MaintenanceRecord
    suspend fun updateMaintenance(token: String, recordId: String, draft: MaintenanceDraft): MaintenanceRecord
}

sealed class AutoFailure(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Network(cause: IOException) : AutoFailure("Не удалось подключиться. Проверьте подключение к интернету.", cause)
    data object Unauthorized : AutoFailure("Сессия приложения «Инженер» истекла.")
    class Http(val status: Int) : AutoFailure("Не удалось выполнить действие в разделе «Авто». Попробуйте ещё раз.")
    class Document(message: String) : AutoFailure(message)
    data object InvalidResponse : AutoFailure("Не удалось загрузить данные раздела «Авто».")
}

class OkHttpAutoApi(
    baseOrigin: String,
    private val json: Json,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build(),
) : AutoApi {
    private val origin = baseOrigin.trimEnd('/')

    override suspend fun vehicles(token: String): List<Vehicle> =
        execute<VehicleListResponse>(request("/api/v2/vehicles", token)).vehicles

    override suspend fun createVehicle(token: String, draft: VehicleDraft): Vehicle =
        execute<VehicleResponse>(request("/api/v2/vehicles", token, "POST", draft.payload())).vehicle

    override suspend fun updateVehicle(token: String, vehicleId: String, draft: VehicleDraft): Vehicle =
        execute<VehicleResponse>(request("/api/v2/vehicles/$vehicleId", token, "PUT", draft.payload())).vehicle

    override suspend fun uploadDocument(
        token: String,
        vehicleId: String,
        input: VehicleDocumentInput,
        onProgress: (Double) -> Unit,
    ): VehicleDocument = withContext(Dispatchers.IO) {
        val uploadId = UUID.randomUUID().toString()
        val totalChunks = (input.data.size + VehicleDocumentInput.CHUNK_BYTES - 1) / VehicleDocumentInput.CHUNK_BYTES
        var document: VehicleDocument? = null
        repeat(totalChunks) { index ->
            val offset = index * VehicleDocumentInput.CHUNK_BYTES
            val bytes = input.data.copyOfRange(offset, minOf(offset + VehicleDocumentInput.CHUNK_BYTES, input.data.size))
            val payload = json.encodeToString(DocumentChunkPayload.serializer(), DocumentChunkPayload(
                uploadId = uploadId,
                documentId = input.id,
                kind = input.kind.wireValue,
                fileName = input.fileName,
                mimeType = input.mimeType,
                chunkIndex = index,
                totalChunks = totalChunks,
                chunkBase64 = Base64.getEncoder().encodeToString(bytes),
            ))
            val response = execute<DocumentChunkResponse>(request(documentPath(vehicleId) + "/chunk", token, "POST", payload))
            if (index == totalChunks - 1) {
                if (!response.complete || response.document?.id != input.id) throw AutoFailure.InvalidResponse
                document = response.document
            }
            onProgress((index + 1).toDouble() / totalChunks)
        }
        document ?: throw AutoFailure.InvalidResponse
    }

    override suspend fun downloadDocument(token: String, vehicleId: String, documentId: String): ByteArray =
        withContext(Dispatchers.IO) {
            executeResponse(request(documentPath(vehicleId, documentId), token)).use { response ->
                val body = response.body
                if (body.contentLength() > VehicleDocumentInput.MAX_BYTES) throw AutoFailure.InvalidResponse
                body.byteStream().use { stream ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = stream.read(buffer)
                        if (count == -1) break
                        if (output.size() + count > VehicleDocumentInput.MAX_BYTES) throw AutoFailure.InvalidResponse
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray().also { if (it.isEmpty()) throw AutoFailure.InvalidResponse }
                }
            }
        }

    override suspend fun deleteDocument(token: String, vehicleId: String, documentId: String) {
        withContext(Dispatchers.IO) {
            executeResponse(request(documentPath(vehicleId, documentId), token, "DELETE")).close()
        }
    }

    private fun documentPath(vehicleId: String, documentId: String? = null): String {
        val url = origin.toHttpUrl().newBuilder().addPathSegments("api/v2/vehicles")
            .addPathSegment(vehicleId).addPathSegment("documents")
        if (documentId != null) url.addPathSegment(documentId)
        return url.build().encodedPath
    }

    override suspend fun maintenance(token: String): List<MaintenanceRecord> =
        execute<MaintenanceListResponse>(request("/api/v2/maintenance", token)).records
            .sortedByDescending(MaintenanceRecord::date)

    override suspend fun createMaintenance(token: String, draft: MaintenanceDraft): MaintenanceRecord =
        execute<MaintenanceRecord>(
            request("/api/v2/maintenance", token, "POST", draft.payload(UUID.randomUUID().toString())),
        )

    override suspend fun updateMaintenance(
        token: String,
        recordId: String,
        draft: MaintenanceDraft,
    ): MaintenanceRecord = execute(
        request("/api/v2/maintenance/$recordId", token, "PUT", draft.payload(recordId)),
    )

    private fun request(path: String, token: String, method: String = "GET", payload: String? = null): Request {
        val builder = Request.Builder()
            .url("$origin$path")
            .header("Accept", "application/json")
            .header("Authorization", "Bearer $token")
            .header("Cache-Control", "no-cache")
        return when (method) {
            "POST" -> builder.post(requireNotNull(payload).toRequestBody(JSON_MEDIA_TYPE)).build()
            "PUT" -> builder.put(requireNotNull(payload).toRequestBody(JSON_MEDIA_TYPE)).build()
            "DELETE" -> builder.delete().build()
            else -> builder.get().build()
        }
    }

    private suspend inline fun <reified Value> execute(request: Request): Value = withContext(Dispatchers.IO) {
        executeResponse(request).use { response ->
            val body = response.body.string()
            runCatching { json.decodeFromString<Value>(body) }.getOrElse { throw AutoFailure.InvalidResponse }
        }
    }

    private suspend fun executeResponse(request: Request): Response {
        val call = client.newCall(request)
        call.timeout().timeout(30, TimeUnit.SECONDS)
        val response = try {
            call.awaitAuto()
        } catch (error: IOException) {
            throw AutoFailure.Network(error)
        }
        if (response.code == 401 || response.code == 403) {
            response.close()
            throw AutoFailure.Unauthorized
        }
        if (!response.isSuccessful) {
            val status = response.code
            val message = response.use {
                if ("documents" in request.url.pathSegments) runCatching {
                    json.decodeFromString<DocumentErrorResponse>(it.body.string()).message
                        ?.trim()?.takeIf { value -> value.isNotBlank() && value.length <= 300 }
                }.getOrNull() else null
            }
            if (message != null) throw AutoFailure.Document(message)
            throw AutoFailure.Http(status)
        }
        return response
    }

    private fun VehicleDraft.payload(): String = json.encodeToString(
        VehiclePayload.serializer(),
        VehiclePayload(
            licensePlate = licensePlate.trim().ifBlank { null },
            vin = vin.trim().ifBlank { null },
            sts = sts.trim().ifBlank { null },
            pts = pts.trim().ifBlank { null },
            make = make.trim().ifBlank { null },
            model = model.trim().ifBlank { null },
            generation = generation.trim().ifBlank { null },
            year = year.trim().toIntOrNull(),
            bodyType = bodyType.trim().ifBlank { null },
            colorName = colorName.trim().ifBlank { null },
            currentMileageKm = currentMileageKm.trim().toIntOrNull(),
            engineVolumeCm3 = engineVolumeCm3.trim().toIntOrNull(),
            enginePowerHp = enginePowerHp.trim().toIntOrNull(),
            customName = customName.trim().ifBlank { null },
            isPrimary = isPrimary,
        ),
    )

    private fun MaintenanceDraft.payload(id: String): String {
        val parsedParts = parts.mapNotNull { part ->
            val name = part.name.trim()
            val cost = part.cost.trim().replace(',', '.').toDoubleOrNull()
            if (name.isEmpty() || cost == null || cost < 0) null else MaintenancePart(name, cost)
        }
        val work = workCost.trim().replace(',', '.').toDoubleOrNull()
        return json.encodeToString(
            MaintenancePayload.serializer(),
            MaintenancePayload(
                id = id,
                vehicleId = vehicleId,
                date = date.trim(),
                procedure = procedure.trim(),
                mileage = mileage.trim().toIntOrNull() ?: 0,
                parts = parsedParts,
                partsCost = parsedParts.sumOf(MaintenancePart::cost).takeIf { parsedParts.isNotEmpty() },
                workCost = work,
            ),
        )
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

@Serializable
private data class DocumentErrorResponse(val message: String? = null)

@Serializable
private data class DocumentChunkPayload(
    val uploadId: String,
    val documentId: String,
    val kind: String,
    val fileName: String,
    val mimeType: String,
    val chunkIndex: Int,
    val totalChunks: Int,
    val chunkBase64: String,
)

@Serializable
private data class DocumentChunkResponse(val complete: Boolean, val document: VehicleDocument? = null)

@Serializable
private data class VehicleListResponse(val vehicles: List<Vehicle> = emptyList())

@Serializable
private data class VehicleResponse(val vehicle: Vehicle)

@Serializable
private data class MaintenanceListResponse(val records: List<MaintenanceRecord> = emptyList())

@Serializable
private data class VehiclePayload(
    val licensePlate: String? = null,
    val vin: String? = null,
    val sts: String? = null,
    val pts: String? = null,
    val make: String? = null,
    val model: String? = null,
    val generation: String? = null,
    val year: Int? = null,
    val bodyType: String? = null,
    val colorName: String? = null,
    val currentMileageKm: Int? = null,
    val engineVolumeCm3: Int? = null,
    val enginePowerHp: Int? = null,
    val customName: String? = null,
    val isPrimary: Boolean = false,
)

@Serializable
private data class MaintenancePayload(
    val id: String,
    val vehicleId: String?,
    val date: String,
    val procedure: String,
    val mileage: Int,
    val parts: List<MaintenancePart>,
    val partsCost: Double?,
    val workCost: Double?,
)

private suspend fun Call.awaitAuto(): Response = suspendCancellableCoroutine { continuation ->
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
