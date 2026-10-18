package ru.lumastack.lumawork.data.gsm

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

interface GsmProfileApi {
    suspend fun fetch(token: String): GsmProfileLoadResult
    suspend fun projects(token: String): List<GsmProjectOption>
    suspend fun save(token: String, profile: GsmProfile): GsmProfile
}

sealed class GsmProfileFailure(message: String) : Exception(message) {
    class Validation(message: String) : GsmProfileFailure(message)
    data object Unauthorized : GsmProfileFailure("Сессия приложения «Инженер» истекла.")
    data object Network : GsmProfileFailure("Не удалось подключиться. Проверьте подключение к интернету.")
    data object InvalidResponse : GsmProfileFailure("Не удалось загрузить ГСМ профиль.")
    class Http(val code: Int) : GsmProfileFailure("Не удалось выполнить действие с ГСМ профилем. Попробуйте ещё раз.")
}

class OkHttpGsmProfileApi(
    baseOrigin: String,
    private val json: Json,
    private val client: OkHttpClient = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS).writeTimeout(30, TimeUnit.SECONDS).build(),
) : GsmProfileApi {
    private val origin = baseOrigin.trimEnd('/')

    override suspend fun fetch(token: String): GsmProfileLoadResult = GsmProfileCodec.decode(execute(request("profile", token)))
    override suspend fun projects(token: String): List<GsmProjectOption> = GsmProfileCodec.projects(execute(request("projects", token)))

    override suspend fun save(token: String, profile: GsmProfile): GsmProfile {
        val normalized = profile.normalizedForSave()
        // The backend derives these from the selected POS and visible contact fields;
        // its optional legacy properties require nonempty strings when supplied.
        val optionalLegacyFields = setOf("budgetCode", "projectName", "employeeTitleCompany", "employeeAddressPhone")
        val fields = json.encodeToJsonElement(GsmProfile.serializer(), normalized).jsonObject.filterNot { (name, value) ->
            name in optionalLegacyFields && value.jsonPrimitive.content.isBlank()
        }
        // Nil links must be explicit even when the app's Json instance omits null properties.
        val body = JsonObject(fields + mapOf(
            "vehicleId" to (normalized.vehicleId?.let(::JsonPrimitive) ?: JsonNull),
            "posProjectId" to (normalized.posProjectId?.let(::JsonPrimitive) ?: JsonNull),
            "armProjectId" to (normalized.armProjectId?.let(::JsonPrimitive) ?: JsonNull),
        ))
        val response = execute(request("profile", token, body.toString()))
        return GsmProfileCodec.profileObject(response)?.let(GsmProfileCodec::profile) ?: normalized
    }

    private fun request(resource: String, token: String, body: String? = null): Request {
        if (token.isBlank()) throw GsmProfileFailure.Unauthorized
        val request = Request.Builder().url("$origin/api/v2/gsm/$resource").header("Accept", "application/json")
            .header("Authorization", "Bearer $token").header("Cache-Control", "no-cache")
        return if (body == null) request.get().build() else request.put(body.toRequestBody("application/json; charset=utf-8".toMediaType())).build()
    }

    private suspend fun execute(request: Request): JsonElement = withContext(Dispatchers.IO) {
        val response = try { client.newCall(request).awaitGsm() } catch (_: IOException) { throw GsmProfileFailure.Network }
        response.use {
            if (it.code == 401 || it.code == 403) throw GsmProfileFailure.Unauthorized
            if (!it.isSuccessful) throw GsmProfileFailure.Http(it.code)
            try { json.parseToJsonElement(it.body.string()) } catch (_: IllegalArgumentException) { throw GsmProfileFailure.InvalidResponse }
        }
    }
}

/** Mirrors the iOS compatibility mapper: wrapper/alias and old single-fuel support. */
internal object GsmProfileCodec {
    fun decode(value: JsonElement): GsmProfileLoadResult {
        val root = value as? JsonObject ?: throw GsmProfileFailure.InvalidResponse
        return GsmProfileLoadResult(profileObject(root)?.let(::profile) ?: GsmProfile(), strings(root["availableFuelTypes"]))
    }

    fun profileObject(value: JsonElement): JsonObject? {
        val root = value as? JsonObject ?: return null
        listOf("profile", "gsmProfile", "gsm_profile", "data").forEach { key -> (root[key] as? JsonObject)?.let { return it } }
        return root.takeIf { it.keys.any { key -> key.contains("employee", true) || key.contains("budget", true) } }
    }

    fun profile(value: JsonObject): GsmProfile {
        fun text(camel: String, snake: String): String = listOf(value[camel], value[snake]).map(::string).firstOrNull { it.isNotBlank() }.orEmpty()
        val legacyFuel = text("fuelType", "fuel_type")
        val fuels = listOf("fuelTypes", "fuel_types").map { strings(value[it]) }.firstOrNull { it.isNotEmpty() }
            ?: listOf(legacyFuel).filter(String::isNotEmpty)
        fun number(camel: String, snake: String): Double = listOf(value[camel], value[snake])
            .mapNotNull { string(it).replace(',', '.').toDoubleOrNull()?.takeIf(Double::isFinite) }.firstOrNull() ?: 0.0
        fun integer(camel: String, snake: String): Int = listOf(value[camel], value[snake]).mapNotNull { string(it).toIntOrNull() }.firstOrNull() ?: 0
        return GsmProfile(
            vehicleId = text("vehicleId", "vehicle_id").ifEmpty { null },
            posProjectId = text("posProjectId", "pos_project_id").ifEmpty { null },
            armProjectId = text("armProjectId", "arm_project_id").ifEmpty { null },
            budgetCode = text("budgetCode", "budget_code"), employeeFullName = text("employeeFullName", "employee_full_name"),
            employeeShortName = text("employeeShortName", "employee_short_name"), employeeReportSignName = text("employeeReportSignName", "employee_report_sign_name"),
            authorizedFullName = text("authorizedFullName", "authorized_full_name"), authorizedShortName = text("authorizedShortName", "authorized_short_name"),
            employeeJobTitle = text("employeeJobTitle", "employee_job_title"), employeeCompany = text("employeeCompany", "employee_company"),
            employeeAddress = text("employeeAddress", "employee_address"), employeePhone = GsmPhoneFormatter.format(text("employeePhone", "employee_phone")),
            employeeTitleCompany = text("employeeTitleCompany", "employee_title_company"), employeeAddressPhone = text("employeeAddressPhone", "employee_address_phone"),
            driverLicenseNumber = text("driverLicenseNumber", "driver_license_number"), fuelCardNumber = text("fuelCardNumber", "fuel_card_number"),
            carModel = text("carModel", "car_model"), licensePlate = text("licensePlate", "license_plate"),
            fuelNorm = number("fuelNorm", "fuel_norm"), fuelType = legacyFuel, fuelTypes = fuels,
            defaultStartOdometer = integer("defaultStartOdometer", "default_start_odometer"),
            reportStartMonth = text("reportStartMonth", "report_start_month").ifEmpty { "2026-04" }, projectName = text("projectName", "project_name"),
        )
    }

    fun projects(value: JsonElement): List<GsmProjectOption> = ((value as? JsonObject)?.get("projects") as? JsonArray).orEmpty().mapNotNull { raw ->
        val project = raw as? JsonObject ?: return@mapNotNull null
        val id = string(project["id"]); val name = string(project["name"]); val budget = string(project["budgetCode"])
        if (id.isEmpty() || name.isEmpty() || budget.isEmpty()) null else GsmProjectOption(id, name, budget)
    }

    private fun strings(value: JsonElement?): List<String> = (value as? JsonArray).orEmpty().map(::string).filter(String::isNotEmpty)
    private fun string(value: JsonElement?): String = (value as? JsonPrimitive)?.contentOrNull.orEmpty()
}

private suspend fun Call.awaitGsm(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
        override fun onResponse(call: Call, response: Response) { if (continuation.isActive) continuation.resume(response) else response.close() }
    })
}
