package ru.lumastack.lumawork.data.notifications

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

interface NotificationsApi {
    suspend fun fetch(token: String): NotificationPreferences
    suspend fun save(token: String, value: NotificationPreferences)
    suspend fun send(token: String, event: NotificationEvent)
}

class NotificationsFailure(message: String) : Exception(message)

class OkHttpNotificationsApi(
    baseOrigin: String,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false },
    private val client: OkHttpClient = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS).callTimeout(45, TimeUnit.SECONDS).build(),
) : NotificationsApi {
    private val origin = baseOrigin.trimEnd('/').toHttpUrl()
    override suspend fun fetch(token: String): NotificationPreferences {
        val body = execute(token, "preferences")
        return try { json.decodeFromString<PreferencesResponse>(body).preferences }
        catch (_: IllegalArgumentException) { throw NotificationsFailure("Сервер уведомлений вернул неизвестный ответ.") }
    }
    override suspend fun save(token: String, value: NotificationPreferences) {
        execute(token, "preferences", "PUT", json.encodeToString(value))
    }
    override suspend fun send(token: String, event: NotificationEvent) {
        execute(token, "events", "POST", json.encodeToString(event))
    }
    private suspend fun execute(token: String, path: String, method: String = "GET", body: String? = null): String = withContext(Dispatchers.IO) {
        if (token.isBlank()) throw NotificationsFailure("Сессия приложения «Инженер» недоступна.")
        val request = Request.Builder().url(origin.newBuilder().addPathSegments("api/v2/notifications").addPathSegment(path).build())
            .header("Authorization", "Bearer $token").header("Accept", "application/json").header("Cache-Control", "no-cache")
            .method(method, body?.toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        val response = try { client.newCall(request).awaitNotification() }
        catch (_: IOException) { throw NotificationsFailure("Не удалось подключиться к серверу уведомлений.") }
        response.use {
            if (it.code == 401 || it.code == 403) throw NotificationsFailure("Сессия приложения «Инженер» истекла.")
            if (!it.isSuccessful) throw NotificationsFailure("Сервер уведомлений не принял действие. Попробуйте ещё раз.")
            it.body.string()
        }
    }
}
@Serializable private data class PreferencesResponse(val preferences: NotificationPreferences)

private suspend fun Call.awaitNotification(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
        override fun onResponse(call: Call, response: Response) { if (continuation.isActive) continuation.resume(response) else response.close() }
    })
}
