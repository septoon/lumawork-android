package ru.lumastack.lumawork.data.auto

import java.io.IOException
import java.util.UUID
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
    suspend fun maintenance(token: String): List<MaintenanceRecord>
    suspend fun createMaintenance(token: String, draft: MaintenanceDraft): MaintenanceRecord
    suspend fun updateMaintenance(token: String, recordId: String, draft: MaintenanceDraft): MaintenanceRecord
}

sealed class AutoFailure(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Network(cause: IOException) : AutoFailure("Не удалось подключиться. Проверьте подключение к интернету.", cause)
    data object Unauthorized : AutoFailure("Сессия приложения «Инженер» истекла.")
    class Http(val status: Int) : AutoFailure("Не удалось выполнить действие в разделе «Авто». Попробуйте ещё раз.")
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
            else -> builder.get().build()
        }
    }

    private suspend inline fun <reified Value> execute(request: Request): Value {
        val call = client.newCall(request)
        call.timeout().timeout(30, TimeUnit.SECONDS)
        val response = try {
            call.awaitAuto()
        } catch (error: IOException) {
            throw AutoFailure.Network(error)
        }
        return response.use {
            if (it.code == 401 || it.code == 403) throw AutoFailure.Unauthorized
            if (!it.isSuccessful) throw AutoFailure.Http(it.code)
            val body = it.body.string()
            runCatching { json.decodeFromString<Value>(body) }.getOrElse { throw AutoFailure.InvalidResponse }
        }
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
