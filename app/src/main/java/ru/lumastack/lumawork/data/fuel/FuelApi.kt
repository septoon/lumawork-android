package ru.lumastack.lumawork.data.fuel

import java.io.IOException
import java.util.UUID
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import ru.lumastack.lumawork.domain.fuel.FuelAdjustmentKind
import ru.lumastack.lumawork.domain.fuel.FuelDraft
import ru.lumastack.lumawork.domain.fuel.FuelRecord
import ru.lumastack.lumawork.domain.fuel.FuelRecordType

interface FuelApi {
    suspend fun fuelTypes(token: String): List<String> = emptyList()
    suspend fun records(token: String): List<FuelRecord>
    suspend fun create(token: String, draft: FuelDraft): FuelRecord
    suspend fun update(token: String, recordId: String, draft: FuelDraft): FuelRecord
    suspend fun delete(token: String, recordId: String)
}

sealed class FuelFailure(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Message(message: String) : FuelFailure(message)
    class Network(cause: IOException) : FuelFailure("Не удалось загрузить данные топлива. Проверьте подключение к интернету.", cause)
    data object Unauthorized : FuelFailure("Сессия приложения «Инженер» истекла.")
    class Http(val status: Int) : FuelFailure("Не удалось выполнить действие с топливом. Попробуйте ещё раз.")
    data object InvalidResponse : FuelFailure("Не удалось загрузить данные топлива.")
}

class OkHttpFuelApi(
    baseOrigin: String,
    private val json: Json,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build(),
) : FuelApi {
    private val origin = baseOrigin.trimEnd('/')

    override suspend fun fuelTypes(token: String): List<String> {
        val profile = execute<GsmProfileResponse>(request("/api/v2/gsm/profile", token)).profile
        return profile.fuelTypes.map(String::trim).filter(String::isNotBlank).ifEmpty { listOfNotNull(profile.fuelType?.trim()?.takeIf(String::isNotBlank)) }.distinct()
    }

    override suspend fun records(token: String): List<FuelRecord> =
        execute<FuelRecordsResponse>(request("/api/v2/fuel", token), ::normalizeFuelRecords).records

    override suspend fun create(token: String, draft: FuelDraft): FuelRecord =
        execute(request("/api/v2/fuel", token, "POST", draft.payload(UUID.randomUUID().toString())), ::normalizeFuelRecord)

    override suspend fun update(token: String, recordId: String, draft: FuelDraft): FuelRecord =
        execute(request(recordPath(recordId), token, "PUT", draft.payload(recordId)), ::normalizeFuelRecord)

    override suspend fun delete(token: String, recordId: String) {
        executeEmpty(request(recordPath(recordId), token, "DELETE"))
    }

    private fun recordPath(id: String) = "/api/v2/fuel/" + origin.toHttpUrl().newBuilder().addPathSegment(id).build().encodedPath.substringAfterLast('/')

    private fun request(path: String, token: String, method: String = "GET", body: String? = null): Request {
        val builder = Request.Builder().url("$origin$path")
            .header("Accept", "application/json")
            .header("Authorization", "Bearer $token")
            .header("Cache-Control", "no-cache")
        return when (method) {
            "POST" -> builder.post(requireNotNull(body).toRequestBody(JSON_MEDIA_TYPE)).build()
            "PUT" -> builder.put(requireNotNull(body).toRequestBody(JSON_MEDIA_TYPE)).build()
            "DELETE" -> builder.delete().build()
            else -> builder.get().build()
        }
    }

    private suspend inline fun <reified Value> execute(request: Request, crossinline normalize: (JsonElement) -> JsonElement = { it }): Value = withContext(Dispatchers.IO) {
        val response = executeResponse(request)
        response.use {
            runCatching { json.decodeFromJsonElement<Value>(normalize(json.parseToJsonElement(it.body.string()))) }.getOrElse { throw FuelFailure.InvalidResponse }
        }
    }

    // v2 returns Prisma enum names (FUEL, COMPENSATION_PAYMENT); iOS normalizes them too.
    private fun normalizeFuelRecord(element: JsonElement): JsonElement {
        val record = element as? JsonObject ?: throw FuelFailure.InvalidResponse
        return JsonObject(record.mapValues { (key, value) ->
            if (key == "recordType" || key == "adjustmentKind") {
                (value as? JsonPrimitive)?.contentOrNull?.let { JsonPrimitive(it.lowercase(Locale.ROOT)) } ?: value
            } else value
        })
    }

    private fun normalizeFuelRecords(element: JsonElement): JsonElement {
        val root = element as? JsonObject ?: throw FuelFailure.InvalidResponse
        val records = root["records"] as? JsonArray ?: throw FuelFailure.InvalidResponse
        return JsonObject(root + ("records" to JsonArray(records.map(::normalizeFuelRecord))))
    }

    private suspend fun executeEmpty(request: Request) {
        executeResponse(request).close()
    }

    private suspend fun executeResponse(request: Request): Response {
        val call = client.newCall(request)
        call.timeout().timeout(30, TimeUnit.SECONDS)
        val response = try {
            call.awaitFuel()
        } catch (error: IOException) {
            throw FuelFailure.Network(error)
        }
        if (response.code == 401 || response.code == 403) {
            response.close()
            throw FuelFailure.Unauthorized
        }
        if (!response.isSuccessful) {
            val code = response.code
            response.close()
            throw FuelFailure.Http(code)
        }
        return response
    }

    private fun FuelDraft.payload(id: String): String = json.encodeToString(
        FuelPayload.serializer(),
        FuelPayload(
            id = id,
            date = if (recordType == FuelRecordType.Adjustment) "$monthKey-01" else date.trim(),
            recordType = recordType,
            adjustmentKind = adjustmentKind.takeIf { recordType == FuelRecordType.Adjustment },
            monthKey = monthKey.takeIf { recordType == FuelRecordType.Adjustment },
            amount = amountValue.takeIf { recordType == FuelRecordType.Adjustment },
            carryoverDebtRub = carryoverDebtValue.takeIf { recordType == FuelRecordType.Adjustment },
            comment = comment.trim().ifBlank { null },
            mileage = mileageValue.takeIf { recordType == FuelRecordType.Fuel },
            liters = litersValue,
            fuelCost = fuelCostValue.takeIf { recordType == FuelRecordType.Fuel },
            fuelType = fuelType.trim().takeIf { recordType == FuelRecordType.Fuel && it.isNotEmpty() },
        ),
    )

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

@Serializable
private data class GsmProfileResponse(val profile: GsmFuelProfile = GsmFuelProfile())
@Serializable
private data class GsmFuelProfile(val fuelTypes: List<String> = emptyList(), val fuelType: String? = null)

@Serializable
private data class FuelRecordsResponse(val records: List<FuelRecord> = emptyList())

@Serializable
private data class FuelPayload(
    val id: String,
    val date: String,
    val recordType: FuelRecordType,
    val adjustmentKind: FuelAdjustmentKind? = null,
    val monthKey: String? = null,
    val amount: Double? = null,
    val carryoverDebtRub: Double? = null,
    val comment: String? = null,
    val mileage: Double? = null,
    val liters: Double? = null,
    val fuelCost: Double? = null,
    val fuelType: String? = null,
)

private suspend fun Call.awaitFuel(): Response = suspendCancellableCoroutine { continuation ->
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
