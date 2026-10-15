package ru.lumastack.lumawork.data.route

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import ru.lumastack.lumawork.domain.route.RouteDayRecord
import ru.lumastack.lumawork.domain.route.RouteSettings
import ru.lumastack.lumawork.domain.route.RouteStop
import ru.lumastack.lumawork.domain.route.RouteStopStatus
import ru.lumastack.lumawork.domain.route.normalizeRouteDay

interface RouteApi {
    suspend fun fetchDay(token: String, date: String, settings: RouteSettings): RouteDayRecord?
    suspend fun sendDay(token: String, record: RouteDayRecord)
}

sealed class RouteFailure(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Network(cause: IOException) : RouteFailure("Не удалось подключиться к серверу маршрутов.", cause)
    data object Unauthorized : RouteFailure("Требуется повторный вход.")
    class Http(val status: Int, message: String) : RouteFailure(message)
    data object InvalidResponse : RouteFailure("Сервер маршрутов вернул некорректный ответ.")

    val isRetryable: Boolean
        get() = this is Network || this is Http && status in setOf(502, 503, 504)
}

class OkHttpRouteApi(
    baseOrigin: String,
    private val client: OkHttpClient = defaultClient(),
    private val json: Json = defaultJson,
) : RouteApi {
    private val origin = baseOrigin.trimEnd('/')
    private val parser = RoutePayloadParser()

    override suspend fun fetchDay(
        token: String,
        date: String,
        settings: RouteSettings,
    ): RouteDayRecord? {
        val request = requestBuilder("/api/v2/routes?from=$date&to=$date", token).get().build()
        return execute(request).use { response ->
            val body = requireSuccess(response)
            if (body.isBlank()) null else parser.parseDay(json.parseToJsonElement(body), date, settings)
        }
    }

    override suspend fun sendDay(token: String, record: RouteDayRecord) {
        val request = requestBuilder("/api/v2/routes", token)
            .post(payload(record).toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        execute(request).use(::requireSuccess)
    }

    private fun requestBuilder(path: String, token: String): Request.Builder = Request.Builder()
        .url("$origin$path")
        .header("Accept", "application/json")
        .header("Authorization", "Bearer $token")

    private suspend fun execute(request: Request): Response {
        val call = client.newCall(request)
        call.timeout().timeout(20, TimeUnit.SECONDS)
        return try {
            call.awaitRoute()
        } catch (error: IOException) {
            throw RouteFailure.Network(error)
        }
    }

    private fun requireSuccess(response: Response): String {
        val body = response.body.string()
        if (response.isSuccessful || response.code == 304) return body
        if (response.code == 401 || response.code == 403) throw RouteFailure.Unauthorized
        val message = errorMessage(body) ?: "Ошибка маршрутов (${response.code})."
        throw RouteFailure.Http(response.code, message)
    }

    private fun errorMessage(body: String): String? {
        val payload = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        return payload.string("error").ifBlank { payload.string("message") }.ifBlank { null }
    }

    private fun payload(record: RouteDayRecord): JsonObject = buildJsonObject {
        put("date", record.date)
        putNullable("distanceKm", record.distanceKm)
        putNullable("periodStartOdometer", record.periodStartOdometer)
        put("sent", record.sent)
        put("stops", buildJsonArray {
            record.stops.forEach { stop ->
                add(buildJsonObject {
                    put("id", stop.id)
                    put("address", stop.address.trim())
                    put("org", stop.org.trim())
                    put("tid", stop.tid.trim())
                    put("reason", stop.reason.trim())
                    put("status", stop.status.serverLabel)
                    put("rejectReason", stop.declineReason.trim())
                    put("requestNumber", stop.requestNumber.trim())
                })
            }
        })
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val defaultJson = Json { ignoreUnknownKeys = true }

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}

class RoutePayloadParser {
    fun parseDay(payload: JsonElement, date: String, settings: RouteSettings): RouteDayRecord? {
        val raw = extractDay(payload, date) ?: return null
        val stops = (raw["stops"] as? JsonArray).orEmpty().mapNotNull(::parseStop)
        return normalizeRouteDay(
            RouteDayRecord(
                date = date,
                stops = stops,
                distanceKm = raw.int("distanceKm", "distance_km"),
                periodStartOdometer = raw.int("periodStartOdometer", "period_start_odometer"),
                reportedDistanceKm = raw.double("reportedDistanceKm", "distanceKm", "distance_km"),
                routeSummary = raw.string("routeSummary", "route").ifBlank { null },
                requestNumbersSummary = raw.string("requestNumbersSummary", "request_numbers").ifBlank { null },
                reportedPeriodStartOdometer = raw.int(
                    "reportedPeriodStartOdometer",
                    "periodStartOdometer",
                    "period_start_odometer",
                ),
                fuelDate = raw.string("fuelDate", "fuel_date").ifBlank { null },
                fuelLiters = raw.double("fuelLiters", "fuel_liters"),
                fuelCostRub = raw.double("fuelCostRub", "fuel_cost_rub"),
                sent = raw.boolean("sent") ?: false,
            ),
            date,
            settings,
        )
    }

    private fun extractDay(payload: JsonElement, date: String): JsonObject? = when (payload) {
        is JsonArray -> payload.objects().firstOrNull { it.string("date") == date }
        is JsonObject -> {
            (payload["records"] as? JsonArray)?.objects()?.firstOrNull { it.string("date") == date }
                ?: (payload["days"] as? JsonObject)?.get(date) as? JsonObject
                ?: payload[date] as? JsonObject
                ?: payload.takeIf { it.string("date") == date }
        }
        else -> null
    }

    private fun parseStop(element: JsonElement): RouteStop? {
        val raw = element as? JsonObject ?: return null
        return RouteStop(
            id = raw.string("id").ifBlank { "remote-${raw.hashCode()}" },
            address = raw.string("address"),
            org = raw.string("org"),
            tid = raw.string("tid"),
            reason = raw.string("reason"),
            status = parseStatus(raw.string("status")),
            declineReason = raw.string("declineReason", "rejectReason"),
            requestNumber = raw.string("requestNumber"),
        )
    }

    private fun parseStatus(value: String): RouteStopStatus {
        val normalized = value.trim().lowercase()
        return when {
            "decline" in normalized || "отказ" in normalized -> RouteStopStatus.Declined
            "done" in normalized || "выполн" in normalized -> RouteStopStatus.Done
            else -> RouteStopStatus.Pending
        }
    }
}

private suspend fun Call.awaitRoute(): Response = suspendCancellableCoroutine { continuation ->
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

private fun JsonArray.objects(): List<JsonObject> = mapNotNull { it as? JsonObject }

private fun JsonObject.string(vararg keys: String): String = keys.firstNotNullOfOrNull { key ->
    (get(key) as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty)
}.orEmpty()

private fun JsonObject.int(vararg keys: String): Int? = keys.firstNotNullOfOrNull { key ->
    (get(key) as? JsonPrimitive)?.let { it.intOrNull ?: it.doubleOrNull?.toInt() }
}

private fun JsonObject.double(vararg keys: String): Double? = keys.firstNotNullOfOrNull { key ->
    (get(key) as? JsonPrimitive)?.doubleOrNull
}

private fun JsonObject.boolean(key: String): Boolean? = (get(key) as? JsonPrimitive)?.booleanOrNull

private fun kotlinx.serialization.json.JsonObjectBuilder.putNullable(key: String, value: Int?) {
    put(key, value?.let(::JsonPrimitive) ?: JsonNull)
}

private val RouteStopStatus.serverLabel: String
    get() = when (this) {
        RouteStopStatus.Done -> "Выполнена"
        RouteStopStatus.Declined -> "Отказ"
        RouteStopStatus.Pending -> "В процессе"
    }
