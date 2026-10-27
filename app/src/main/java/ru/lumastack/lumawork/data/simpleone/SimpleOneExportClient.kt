package ru.lumastack.lumawork.data.simpleone

import java.io.IOException
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
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
    private val closedArchiveCondition: () -> String = { ClosedRequestsArchiveQuery().condition() },
) {
    suspend fun exportClosedRequests(userId: String, authKey: String): ByteArray = export(
        userId = userId,
        authKey = authKey,
        payload = exportPayload(
            userId = userId,
            tableName = "itsm_request",
            condition = closedArchiveCondition(),
            columns = CLOSED_EXPORT_COLUMNS,
        ),
    )

    suspend fun exportTimeReport(userId: String, authKey: String): ByteArray = export(
        userId = userId,
        authKey = authKey,
        payload = exportPayload(
            userId = userId,
            tableName = "itsm_tchnsrv_time_report",
            condition = timeReportCondition(),
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
                if (downloadUrl != null) return download(downloadUrl, authKey)
            }

            delay(if (attempt < 8) 500L else 1_000L)
        }
        throw SimpleOneFailure.Http(408, "Не удалось подготовить данные. Попробуйте ещё раз.")
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

    private suspend fun download(url: String, authKey: String): ByteArray = withContext(Dispatchers.IO) {
        val resolvedUrl = origin.toHttpUrl().resolve(url) ?: throw SimpleOneFailure.InvalidResponse
        val requestBuilder = Request.Builder()
            .url(resolvedUrl)
            .get()
            .header("Accept", XLSX_ACCEPT)
            .header("Cache-Control", "no-cache")
        val sourceUrl = origin.toHttpUrl()
        val sameOrigin = resolvedUrl.scheme == sourceUrl.scheme && resolvedUrl.host == sourceUrl.host && resolvedUrl.port == sourceUrl.port
        if (sameOrigin) requestBuilder.header("Cookie", "auth=$authKey")
        val call = client.newCall(requestBuilder.build())
        call.timeout().timeout(45, TimeUnit.SECONDS)
        val response = try {
            call.awaitExport()
        } catch (error: IOException) {
            throw SimpleOneFailure.Network(error)
        }
        response.use { httpResponse ->
            if (sameOrigin && (httpResponse.code == 401 || httpResponse.code == 403)) throw SimpleOneFailure.Unauthorized
            if (!httpResponse.isSuccessful) {
                throw SimpleOneFailure.Http(
                    httpResponse.code,
                    "Не удалось загрузить данные. Попробуйте ещё раз.",
                )
            }
            val contentLength = httpResponse.body.contentLength()
            if (contentLength > MAX_XLSX_BYTES) {
                throw SimpleOneFailure.Http(413, "Размер файла превышает 30 МиБ.")
            }
            val output = ByteArrayOutputStream()
            httpResponse.body.byteStream().use { input ->
                val buffer = ByteArray(8 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > MAX_XLSX_BYTES) {
                        throw SimpleOneFailure.Http(413, "Размер файла превышает 30 МиБ.")
                    }
                    output.write(buffer, 0, count)
                }
            }
            if (output.size() == 0) throw SimpleOneFailure.InvalidResponse
            output.toByteArray()
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
                throw SimpleOneFailure.Http(httpResponse.code, "Не удалось загрузить данные из SimpleOne. Попробуйте ещё раз.")
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
            "sys_id",
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
            "sys_id", "acticvity", "month", "sys_created_at", "date_of_work", "time_of_work_hours",
            "time_of_work_minutes", "time_of_work", "extracurricular_activities_hours", "travel_time_hours",
            "travel_time_minutes", "travel_time", "extracurricular_time", "extracurricular_work", "result", "person",
        )



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
