package ru.lumastack.lumawork.data.route

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.Serializable
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
import ru.lumastack.lumawork.domain.route.RouteWorkType
import ru.lumastack.lumawork.domain.route.RouteSettings
import ru.lumastack.lumawork.domain.route.RouteStop
import ru.lumastack.lumawork.domain.route.RouteStopStatus
import ru.lumastack.lumawork.domain.route.normalizeRouteDay

interface RouteApi {
    suspend fun fetchDay(token: String, date: String, settings: RouteSettings, workType: RouteWorkType = RouteWorkType.Pos): RouteDayRecord?
    suspend fun fetchMonth(token: String, month: String, settings: RouteSettings): List<RouteDayRecord> = emptyList()
    suspend fun fetchHistory(token: String, beforeDate: String, settings: RouteSettings): List<RouteDayRecord> = emptyList()
    suspend fun fetchOfficeAddresses(token: String): List<String> = emptyList()
    suspend fun suggestedOdometer(token: String, month: String): Int? = null
    suspend fun sendGsmReport(token: String, month: String): GsmReportResult = throw RouteFailure.InvalidResponse
    suspend fun excludedDates(token: String): Set<String> = emptySet()
    suspend fun deleteDay(token: String, date: String) { throw RouteFailure.InvalidResponse }
    suspend fun deliverySettings(token: String): GsmDeliverySettings { throw RouteFailure.InvalidResponse }
    suspend fun saveDeliverySettings(token: String, settings: GsmDeliverySettings): GsmDeliverySettings { throw RouteFailure.InvalidResponse }
    suspend fun sendDay(token: String, record: RouteDayRecord)
}

@Serializable
data class GsmDeliverySettings(
    val autoEnabled: Boolean = true,
    val time: String = "22:00",
    val timeZone: String = "Europe/Simferopol",
    val recipientEmail: String? = null,
    val effectiveEmail: String = "",
)

@Serializable
private data class GsmDeliverySettingsResponse(val settings: GsmDeliverySettings)

data class GsmReportResult(val fileName: String, val sentToEmail: String?, val confirmsEmailDelivery: Boolean, val skipEmail: Boolean) {
    val message: String get() = if (confirmsEmailDelivery && !sentToEmail.isNullOrBlank()) "$fileName отправлен на $sentToEmail." else "$fileName сформирован."
}

sealed class RouteFailure(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Network(cause: IOException) : RouteFailure("Не удалось загрузить маршрут. Проверьте подключение к интернету.", cause)
    data object Unauthorized : RouteFailure("Требуется повторный вход.")
    class Http(val status: Int, message: String) : RouteFailure(message)
    data object InvalidResponse : RouteFailure("Не удалось загрузить маршрут.")

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
        workType: RouteWorkType,
    ): RouteDayRecord? {
        val request = requestBuilder("/api/v2/routes?from=$date&to=$date&workType=${workType.wireValue}", token).get().build()
        return execute(request).use { response ->
            val body = requireSuccess(response)
            if (body.isBlank()) null else parser.parseDay(json.parseToJsonElement(body), date, settings, workType)
        }
    }

    override suspend fun fetchMonth(token: String, month: String, settings: RouteSettings): List<RouteDayRecord> {
        val period = java.time.YearMonth.parse(month)
        val request = requestBuilder("/api/v2/routes?from=${period.atDay(1)}&to=${period.atEndOfMonth()}", token).get().build()
        return execute(request).use { response ->
            val body = requireSuccess(response)
            if (body.isBlank()) emptyList() else parser.parseMonth(json.parseToJsonElement(body), month, settings)
        }
    }

    override suspend fun fetchHistory(token: String, beforeDate: String, settings: RouteSettings): List<RouteDayRecord> {
        val until = java.time.LocalDate.parse(beforeDate).minusDays(1)
        return execute(requestBuilder("/api/v2/routes?to=$until", token).get().build()).use { response ->
            val body = requireSuccess(response)
            if (body.isBlank()) emptyList() else parser.parseHistory(json.parseToJsonElement(body), settings)
        }
    }

    override suspend fun fetchOfficeAddresses(token: String): List<String> {
        val request = requestBuilder("/api/v2/offices", token).get().build()
        return execute(request).use { response ->
            val raw = json.parseToJsonElement(requireSuccess(response)) as? JsonObject ?: throw RouteFailure.InvalidResponse
            val offices = raw["offices"] as? JsonArray ?: throw RouteFailure.InvalidResponse
            offices.objects().map { it.string("address") }.filter(String::isNotBlank).distinct()
        }
    }

    override suspend fun suggestedOdometer(token: String, month: String): Int? {
        java.time.YearMonth.parse(month)
        return execute(requestBuilder("/api/v2/gsm/odometer-suggestion?month=$month", token).get().build()).use { response ->
            val raw = json.parseToJsonElement(requireSuccess(response)).jsonObject
            raw.int("startOdometer")?.takeIf { it >= 0 && raw.string("month") == month }
        }
    }

    override suspend fun sendGsmReport(token: String, month: String): GsmReportResult {
        java.time.YearMonth.parse(month)
        val body = buildJsonObject { put("month", month) }.toString().toRequestBody(JSON_MEDIA_TYPE)
        val request = requestBuilder("/api/v2/gsm/report", token).post(body).build()
        return execute(request, timeoutSeconds = 60).use { response ->
            val raw = json.parseToJsonElement(requireSuccess(response)).jsonObject
            if (raw.boolean("success") != true) throw RouteFailure.Http(response.code, raw.string("message", "error").ifBlank { "Сервер не подтвердил отправку отчёта." })
            val result = GsmReportResult(raw.string("outputFileName").ifBlank { "Excel-отчёт" }, raw.string("sentToEmail").ifBlank { null },
                raw.boolean("sent") == true || raw.string("status").equals("SENT", true), raw.boolean("skipEmail") == true)
            if (!result.skipEmail && !result.confirmsEmailDelivery) throw RouteFailure.Http(response.code, "ГСМ отчет сформирован, но письмо не отправлено. Повторите попытку.")
            result
        }
    }

    override suspend fun excludedDates(token: String): Set<String> =
        execute(requestBuilder("/api/v2/routes/exclusions", token).get().build()).use { response ->
            val root = json.parseToJsonElement(requireSuccess(response)).jsonObject
            (root["excludedDates"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.toSet()
                ?: throw RouteFailure.InvalidResponse
        }

    override suspend fun deleteDay(token: String, date: String) {
        java.time.LocalDate.parse(date)
        execute(requestBuilder("/api/v2/routes/$date", token).delete().build()).use(::requireSuccess)
    }

    override suspend fun deliverySettings(token: String): GsmDeliverySettings =
        execute(requestBuilder("/api/v2/gsm/delivery-settings", token).get().build()).use {
            json.decodeFromString<GsmDeliverySettingsResponse>(requireSuccess(it)).settings
        }

    override suspend fun saveDeliverySettings(token: String, settings: GsmDeliverySettings): GsmDeliverySettings {
        val body = buildJsonObject {
            put("autoEnabled", settings.autoEnabled)
            put("time", settings.time)
            put("recipientEmail", settings.recipientEmail?.let(::JsonPrimitive) ?: JsonNull)
        }.toString().toRequestBody(JSON_MEDIA_TYPE)
        return execute(requestBuilder("/api/v2/gsm/delivery-settings", token).put(body).build()).use {
            json.decodeFromString<GsmDeliverySettingsResponse>(requireSuccess(it)).settings
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

    private suspend fun execute(request: Request, timeoutSeconds: Long = 20): Response {
        val call = client.newCall(request)
        call.timeout().timeout(timeoutSeconds, TimeUnit.SECONDS)
        return try {
            (if (timeoutSeconds > 20) client.newBuilder().readTimeout(timeoutSeconds, TimeUnit.SECONDS).build().newCall(request).also { it.timeout().timeout(timeoutSeconds, TimeUnit.SECONDS) } else call).awaitRoute()
        } catch (error: IOException) {
            throw RouteFailure.Network(error)
        }
    }

    private fun requireSuccess(response: Response): String {
        val body = response.body.string()
        if (response.isSuccessful || response.code == 304) return body
        if (response.code == 401 || response.code == 403) throw RouteFailure.Unauthorized
        val message = errorMessage(body) ?: "Не удалось выполнить действие с маршрутом. Попробуйте ещё раз."
        throw RouteFailure.Http(response.code, message)
    }

    private fun errorMessage(body: String): String? {
        val payload = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        if (payload.string("error") == "GSM_PROFILE_REQUIRED") return "Заполните ГСМ профиль в настройках."
        return payload.string("message").ifBlank { payload.string("error") }.ifBlank { null }
    }

    private fun payload(record: RouteDayRecord): JsonObject = buildJsonObject {
        put("date", record.date)
        put("workType", record.workType.wireValue)
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
    fun parseDay(payload: JsonElement, date: String, settings: RouteSettings, workType: RouteWorkType = RouteWorkType.Pos): RouteDayRecord? {
        val raw = extractDay(payload, date, workType) ?: return null
        val stops = (raw["stops"] as? JsonArray).orEmpty().mapNotNull(::parseStop)
        return normalizeRouteDay(
            RouteDayRecord(
                date = date,
                workType = workType,
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

    fun parseMonth(payload: JsonElement, month: String, settings: RouteSettings): List<RouteDayRecord> {
        val dates = (1..java.time.YearMonth.parse(month).lengthOfMonth()).map { java.time.YearMonth.parse(month).atDay(it).toString() }
        return dates.flatMap { date -> RouteWorkType.entries.mapNotNull { parseDay(payload, date, settings, it) } }
    }

    fun parseHistory(payload: JsonElement, settings: RouteSettings): List<RouteDayRecord> {
        val dates = when (payload) {
            is JsonArray -> payload.objects().map { it.string("date") }
            is JsonObject -> (payload["records"] as? JsonArray)?.objects()?.map { it.string("date") }
                ?: (payload["days"] as? JsonObject ?: payload).keys.map { it.take(10) } + payload.string("date")
            else -> emptyList()
        }.filter { runCatching { java.time.LocalDate.parse(it) }.isSuccess }.distinct()
        return dates.flatMap { date -> RouteWorkType.entries.mapNotNull { parseDay(payload, date, settings, it) } }
    }

    private fun extractDay(payload: JsonElement, date: String, workType: RouteWorkType): JsonObject? {
        fun matches(raw: JsonObject) = raw.string("date") == date &&
            (raw.string("workType").ifBlank { "POS" }.equals(workType.wireValue, ignoreCase = true))
        return when (payload) {
            is JsonArray -> payload.objects().firstOrNull(::matches)
            is JsonObject -> {
                val key = workType.storageKey(date)
                (payload["records"] as? JsonArray)?.objects()?.firstOrNull(::matches)
                    ?: ((payload["days"] as? JsonObject)?.get(key) as? JsonObject)?.takeIf {
                        it.string("workType").ifBlank { workType.wireValue }.equals(workType.wireValue, true)
                    }
                    ?: (payload[key] as? JsonObject)?.takeIf {
                        it.string("workType").ifBlank { workType.wireValue }.equals(workType.wireValue, true)
                    }
                    ?: payload.takeIf(::matches)
            }
            else -> null
        }
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
