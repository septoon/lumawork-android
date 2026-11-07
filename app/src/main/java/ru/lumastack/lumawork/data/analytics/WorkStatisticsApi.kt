package ru.lumastack.lumawork.data.analytics

import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import ru.lumastack.lumawork.domain.analytics.*

interface WorkStatisticsApi {
    suspend fun me(token: String): WorkStatisticsMe
    suspend fun upload(token: String, payload: WorkStatisticsUpload): WorkStatisticsReceipt
}
interface LeaderboardApi {
    suspend fun leaderboard(token: String, filter: LeaderboardFilter, offset: Int): LeaderboardResponse
    suspend fun capability(token: String): LeaderboardCapability
}
class StatisticsFailure(val status: Int, val code: String, val availableCities: List<LeaderboardCity> = emptyList()) : IOException(
    when (code) {
        "LEADERBOARD_DISABLED" -> "Рейтинг отключён"
        "CITY_NOT_AVAILABLE" -> "Выбранный город больше недоступен. Выберите город заново."
        "SIMPLEONE_ACCESS_DENIED" -> "Войдите в SimpleOne заново."
        "USER_BLOCKED" -> "Доступ к приложению заблокирован."
        "UNAUTHORIZED" -> "Сессия приложения истекла."
        "SOURCE_ACCOUNT_CHANGED", "SOURCE_ACCOUNT_ALREADY_LINKED" -> "Статистика привязана к другой учётной записи SimpleOne."
        else -> "Рейтинг временно недоступен. Попробуйте позже."
    },
)

/** Capability deliberately uses a separate bearer-only client. */
class OkHttpWorkStatisticsApi(
    private val origin: String, private val protectedClient: OkHttpClient,
    private val capabilityClient: OkHttpClient = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build(),
    private val onAuthFailure: (String) -> Unit = {},
) : WorkStatisticsApi, LeaderboardApi {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    override suspend fun me(token: String): WorkStatisticsMe = execute(protectedClient, request("/api/v2/work-statistics/me", token))
    override suspend fun upload(token: String, payload: WorkStatisticsUpload): WorkStatisticsReceipt {
        payload.validate()
        val body = json.encodeToString(payload)
        if (body.toByteArray().size > 1024 * 1024) throw StatisticsFailure(413, "PAYLOAD_TOO_LARGE")
        val response: WorkStatisticsReceipt = execute(protectedClient, request("/api/v2/work-statistics/me", token)
            .newBuilder().put(body.toRequestBody("application/json".toMediaType())).build())
        require(response.accepted && response.requestId == payload.requestId && response.sourceSyncedAt == payload.sourceSyncedAt && response.serverRevision > payload.expectedRevision)
        return response
    }
    override suspend fun capability(token: String): LeaderboardCapability = execute(capabilityClient, request("/api/v2/leaderboard-access", token))
    override suspend fun leaderboard(token: String, filter: LeaderboardFilter, offset: Int): LeaderboardResponse {
        require(offset >= 0)
        val url = "$origin/api/v2/leaderboard".toHttpUrl().newBuilder()
            .addQueryParameter("period", filter.period.name).addQueryParameter("category", filter.category.name)
            .addQueryParameter("city", filter.city).addQueryParameter("limit", "50").addQueryParameter("offset", offset.toString()).build()
        return execute<LeaderboardResponse>(protectedClient, request(url.toString(), token)).also { it.validate(filter, offset) }
    }
    private fun request(path: String, token: String): Request = Request.Builder().url(if (path.startsWith("http")) path else origin + path)
        .header("Authorization", "Bearer $token").header("X-LumaWork-Device-Platform", "Android").header("Cache-Control", "no-store").build()
    private suspend inline fun <reified T> execute(client: OkHttpClient, request: Request): T {
        val text = client.newCall(request).statisticsResponse().use { response ->
            if (response.body.contentLength() > 2 * 1024 * 1024) throw IOException("Ответ сервера слишком велик")
            val source = response.body.source()
            source.request(2 * 1024 * 1024L + 1)
            if (source.buffer.size > 2 * 1024 * 1024) throw IOException("Ответ сервера слишком велик")
            val body = source.readUtf8()
            if (!response.isSuccessful) {
                val error = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
                val cities = runCatching { json.decodeFromJsonElement<List<LeaderboardCity>>(error!!.getValue("availableCities")) }.getOrDefault(emptyList())
                val code = error?.get("error")?.jsonPrimitive?.contentOrNull.orEmpty()
                if (response.code == 401 || code == "USER_BLOCKED") onAuthFailure(request.header("Authorization").orEmpty().removePrefix("Bearer "))
                throw StatisticsFailure(response.code, code, cities)
            }
            body
        }
        return json.decodeFromString(text)
    }
}
private suspend fun Call.statisticsResponse(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) { if (!continuation.isCancelled) continuation.resumeWithException(e) }
        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response, onCancellation = { _, value, _ -> value.close() })
        }
    })
}
