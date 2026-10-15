package ru.lumastack.lumawork.data.simpleone

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

internal class SimpleOneExportClient(
    private val origin: String,
    private val client: OkHttpClient,
    private val json: Json,
) {
    suspend fun exportClosedRequests(userId: String, authKey: String): ByteArray = export(
        userId = userId,
        authKey = authKey,
        payload = exportPayload(
            userId = userId,
            tableName = "itsm_request",
            condition = closedCondition(userId),
            columns = CLOSED_EXPORT_COLUMNS,
        ),
    )

    suspend fun exportTimeReport(userId: String, authKey: String): ByteArray = export(
        userId = userId,
        authKey = authKey,
        payload = exportPayload(
            userId = userId,
            tableName = "itsm_tchnsrv_time_report",
            condition = TIME_REPORT_CONDITION,
            columns = TIME_REPORT_EXPORT_COLUMNS,
        ),
    )

    private suspend fun export(userId: String, authKey: String, payload: JsonObject): ByteArray {
        val existingIds: Set<String>? = try {
            availableExportIds(userId, authKey).toSet()
        } catch (error: CancellationException) {
            throw error
        } catch (error: SimpleOneFailure.Unauthorized) {
            throw error
        } catch (_: Exception) {
            null
        }
        var currentIds = try {
            exportIds(
                jsonRequest(
                    url = "$origin/exports",
                    method = "POST",
                    authKey = authKey,
                    body = payload,
                ),
            ).filterNot { existingIds?.contains(it) == true }
        } catch (error: CancellationException) {
            throw error
        } catch (error: SimpleOneFailure.Unauthorized) {
            throw error
        } catch (error: Exception) {
            if (existingIds == null) throw error
            emptyList()
        }

        repeat(MAX_POLL_ATTEMPTS) { attempt ->
            currentCoroutineContext().ensureActive()
            if (currentIds.isEmpty() && existingIds != null) {
                currentIds = try {
                    availableExportIds(userId, authKey).filterNot(existingIds::contains)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: SimpleOneFailure.Unauthorized) {
                    throw error
                } catch (_: Exception) {
                    emptyList()
                }
            }

            for (exportId in currentIds) {
                currentCoroutineContext().ensureActive()
                val downloadUrl = try {
                    downloadUrl(exportId, userId, authKey)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: SimpleOneFailure.Unauthorized) {
                    throw error
                } catch (_: Exception) {
                    null
                }
                if (downloadUrl != null) return download(downloadUrl)
            }

            delay(if (attempt < 8) 500L else 1_000L)
        }
        throw SimpleOneFailure.Http(408, "SimpleOne не подготовил XLSX-выгрузку.")
    }

    private suspend fun availableExportIds(userId: String, authKey: String): List<String> =
        exportIds(jsonRequest("$origin/exports?userId=$userId", authKey = authKey))

    private suspend fun downloadUrl(exportId: String, userId: String, authKey: String): String? {
        val response = jsonRequest(
            url = "$origin/exports/download-url",
            method = "POST",
            authKey = authKey,
            body = buildJsonObject {
                put("sysIds", buildJsonArray { add(JsonPrimitive(exportId)) })
                put("userId", userId)
            },
        )
        val data = response["data"] as? JsonObject
        return (data?.get("downloadUrls") as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim() }
            ?.firstOrNull(String::isNotBlank)
    }

    private suspend fun download(url: String): ByteArray {
        val request = Request.Builder()
            .url(url)
            .get()
            .header("Accept", XLSX_ACCEPT)
            .header("Cache-Control", "no-cache")
            .build()
        val call = client.newCall(request)
        call.timeout().timeout(45, TimeUnit.SECONDS)
        val response = try {
            call.awaitExport()
        } catch (error: IOException) {
            throw SimpleOneFailure.Network(error)
        }
        return response.use { httpResponse ->
            if (!httpResponse.isSuccessful) {
                throw SimpleOneFailure.Http(
                    httpResponse.code,
                    "Не удалось скачать XLSX: HTTP ${httpResponse.code}.",
                )
            }
            val contentLength = httpResponse.body.contentLength()
            if (contentLength > MAX_XLSX_BYTES) {
                throw SimpleOneFailure.Http(413, "XLSX-выгрузка превышает 30 МиБ.")
            }
            httpResponse.body.bytes().also { bytes ->
                if (bytes.isEmpty()) throw SimpleOneFailure.InvalidResponse
                if (bytes.size > MAX_XLSX_BYTES) {
                    throw SimpleOneFailure.Http(413, "XLSX-выгрузка превышает 30 МиБ.")
                }
            }
        }
    }

    private suspend fun jsonRequest(
        url: String,
        method: String = "GET",
        authKey: String,
        body: JsonObject? = null,
    ): JsonObject {
        val builder = Request.Builder()
            .url(url)
            .header("Accept", "application/json, text/plain, */*")
            .header("Accept-Language", "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7")
            .header("Cache-Control", "no-cache")
            .header("User-Agent", USER_AGENT)
            .header("Origin", "https://sd.servionica.ru")
            .header("Referer", "https://sd.servionica.ru/list/itsm_request")
            .header("Cookie", "auth=$authKey")
        if (method == "POST") {
            builder.post(body.orEmpty().toString().toRequestBody(JSON_MEDIA_TYPE))
        } else {
            builder.get()
        }
        val call = client.newCall(builder.build())
        call.timeout().timeout(45, TimeUnit.SECONDS)
        val response = try {
            call.awaitExport()
        } catch (error: IOException) {
            throw SimpleOneFailure.Network(error)
        }
        return response.use { httpResponse ->
            val raw = httpResponse.body.string()
            if (httpResponse.code == 401 || httpResponse.code == 403) throw SimpleOneFailure.Unauthorized
            if (!httpResponse.isSuccessful) {
                throw SimpleOneFailure.Http(httpResponse.code, "SimpleOne вернул HTTP ${httpResponse.code}.")
            }
            val payload = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
                ?: throw SimpleOneFailure.InvalidResponse
            exportError(payload)?.let { throw it }
            payload
        }
    }

    private fun exportPayload(
        userId: String,
        tableName: String,
        condition: String,
        columns: List<String>,
    ): JsonObject = buildJsonObject {
        put("export", buildJsonObject {
            put("condition", condition)
            put("type", "excel")
            put("tableName", tableName)
            put("columns", buildJsonArray {
                columns.forEach { add(JsonPrimitive(it)) }
            })
        })
        put("confirmExportLimitExceeded", false)
        put("userId", userId)
    }

    private fun exportIds(response: JsonObject): List<String> {
        val ids = mutableListOf<String>()
        appendExportIds(response["data"], ids)
        appendExportIds(response, ids)
        return ids.map(String::trim).filter(String::isNotEmpty).distinct()
    }

    private fun appendExportIds(element: JsonElement?, ids: MutableList<String>) {
        when (element) {
            is JsonObject -> {
                listOf("sys_id", "sysId", "id").forEach { key ->
                    (element[key] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)?.let(ids::add)
                }
                listOf("items", "list", "records", "exports").forEach { key ->
                    appendExportIds(element[key], ids)
                }
            }
            is JsonArray -> element.forEach { appendExportIds(it, ids) }
            else -> Unit
        }
    }

    private fun exportError(response: JsonObject): SimpleOneFailure? {
        if ((response["status"] as? JsonPrimitive)?.contentOrNull == "OK") return null
        val message = (response["errors"] as? JsonArray)
            ?.mapNotNull { (it as? JsonObject)?.get("message") as? JsonPrimitive }
            ?.mapNotNull { it.contentOrNull }
            ?.firstOrNull(String::isNotBlank)
            ?: (response["message"] as? JsonPrimitive)?.contentOrNull
            ?: return null
        return SimpleOneFailure.Http(200, message)
    }

    private fun JsonObject?.orEmpty(): JsonObject = this ?: JsonObject(emptyMap())

    companion object {
        private const val MAX_POLL_ATTEMPTS = 120
        private const val MAX_XLSX_BYTES = 30 * 1024 * 1024
        private const val XLSX_ACCEPT = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 Chrome/140.0 Mobile Safari/537.36"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val CLOSED_EXPORT_COLUMNS = listOf(
            "number",
            "short_description",
            "state",
            "multicard_request_type",
            "multicard_terminal_address",
            "multicard_name_client",
            "multicard_terminal_model",
            "description",
            "multicard_id_terminal",
            "multicard_comment_ing",
            "multicard_additional_information",
            "assigned_user",
            "multicard_contact_person",
            "multicard_deadline",
            "pb_sn_pos_install",
            "pb_sn_pos_uninstall",
            "multicard_pos",
            "multicard_return_number_pos",
            "multicard_pin_pad",
            "multicard_return_number_pin",
            "multicard_closing_date",
            "resolved_at",
            "sys_created_at",
            "new_closure_code",
            "closure_notes",
        )
        private val TIME_REPORT_EXPORT_COLUMNS = listOf(
            "task",
            "task.sys_created_at",
            "task.closed_at",
            "task.deadline",
            "sys_created_at",
            "time_of_work",
            "travel_time",
            "extracurricular_time",
            "work_start",
            "work_end",
            "assignment_group",
            "person",
            "person.c_corporate_structure",
            "task.initiator",
            "task.client",
            "task.company",
            "task.activity",
            "task.activity.phase",
            "task.activity.sale_iteco",
            "task.activity.contract_number",
            "task.activity.budget_number",
            "task.activity.crm_code",
            "task.service",
            "task.activity.service_manager",
            "result",
        )

        private const val TIME_REPORT_CONDITION =
            "(personDYNAMIC156957141416138823)^ORDERBYDESCdate_of_work"

        private fun closedCondition(userId: String): String =
            "(((multicard_engineer=$userId^ORassigned_user=$userId^ORengineer_schedule.employee=$userId)" +
                "^(resolved_atONopt:167638634011265859^ORresolved_atONopt:167638599011967439)))"
    }
}

private suspend fun Call.awaitExport(): Response = suspendCancellableCoroutine { continuation ->
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
