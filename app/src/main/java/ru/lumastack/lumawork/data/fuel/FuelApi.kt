package ru.lumastack.lumawork.data.fuel

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
import ru.lumastack.lumawork.domain.fuel.FuelAdjustmentKind
import ru.lumastack.lumawork.domain.fuel.FuelDraft
import ru.lumastack.lumawork.domain.fuel.FuelRecord
import ru.lumastack.lumawork.domain.fuel.FuelRecordType

interface FuelApi {
    suspend fun records(token: String): List<FuelRecord>
    suspend fun create(token: String, draft: FuelDraft): FuelRecord
    suspend fun update(token: String, recordId: String, draft: FuelDraft): FuelRecord
    suspend fun delete(token: String, recordId: String)
}

sealed class FuelFailure(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Network(cause: IOException) : FuelFailure("Не удалось подключиться к серверу топлива.", cause)
    data object Unauthorized : FuelFailure("Сессия приложения «Инженер» истекла.")
    class Http(val status: Int) : FuelFailure("Сервер топлива вернул HTTP $status.")
    data object InvalidResponse : FuelFailure("Сервер топлива вернул некорректные данные.")
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

    override suspend fun records(token: String): List<FuelRecord> =
        execute<FuelRecordsResponse>(request("/api/v2/fuel", token)).records

    override suspend fun create(token: String, draft: FuelDraft): FuelRecord =
        execute(request("/api/v2/fuel", token, "POST", draft.payload(UUID.randomUUID().toString())))

    override suspend fun update(token: String, recordId: String, draft: FuelDraft): FuelRecord =
        execute(request("/api/v2/fuel/$recordId", token, "PUT", draft.payload(recordId)))

    override suspend fun delete(token: String, recordId: String) {
        executeEmpty(request("/api/v2/fuel/$recordId", token, "DELETE"))
    }

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

    private suspend inline fun <reified Value> execute(request: Request): Value {
        val response = executeResponse(request)
        return response.use {
            runCatching { json.decodeFromString<Value>(it.body.string()) }.getOrElse { throw FuelFailure.InvalidResponse }
        }
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
        ),
    )

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

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
