package ru.lumastack.lumawork.data.requests

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
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.encodeToJsonElement
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import ru.lumastack.lumawork.domain.requests.ClientPersonalComment
import ru.lumastack.lumawork.domain.requests.ClientPersonalCommentDraft
import ru.lumastack.lumawork.domain.requests.ClientPersonalCommentTarget
import ru.lumastack.lumawork.domain.requests.canonicalClientCommentPhone

interface ClientPersonalCommentsApi {
    suspend fun comments(token: String): List<ClientPersonalComment>
    suspend fun save(token: String, draft: ClientPersonalCommentDraft): ClientPersonalComment
}

sealed class ClientPersonalCommentsFailure(message: String, cause: Throwable? = null) : Exception(message, cause) {
    data object Unauthorized : ClientPersonalCommentsFailure("Сессия приложения «Инженер» истекла.")
    data object Offline : ClientPersonalCommentsFailure("Сохранение комментария недоступно в офлайн-режиме.")
    data object InvalidDraft : ClientPersonalCommentsFailure("Укажите ИНН из 10 или 12 цифр и заполните комментарий.")
    data object InvalidResponse : ClientPersonalCommentsFailure("Не удалось прочитать комментарии сервера.")
    class Network(cause: IOException) : ClientPersonalCommentsFailure("Не удалось подключиться. Проверьте подключение к интернету.", cause)
    class Http(val status: Int, message: String = "Не удалось загрузить или сохранить комментарий. Попробуйте ещё раз.") : ClientPersonalCommentsFailure(message)
}

class OkHttpClientPersonalCommentsApi(
    baseOrigin: String,
    private val json: Json,
    private val client: OkHttpClient = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS).writeTimeout(20, TimeUnit.SECONDS).build(),
) : ClientPersonalCommentsApi {
    private val responseJson = Json(json) { coerceInputValues = true }
    private val endpoint = "${baseOrigin.trimEnd('/')}/api/v2/client-personal-comments"

    override suspend fun comments(token: String): List<ClientPersonalComment> =
        execute<CommentsResponse>(request(token)).comments

    override suspend fun save(token: String, draft: ClientPersonalCommentDraft): ClientPersonalComment {
        if (!draft.canSave) throw ClientPersonalCommentsFailure.InvalidDraft
        val payload = SavePayload(id = draft.serverID, sourceCommentID = draft.sourceCommentID, displayTIN = draft.tin,
            targets = draft.targets, terminalIDs = draft.terminalIDs, contactPerson = draft.contactPerson,
            phone = canonicalClientCommentPhone(draft.phone), email = draft.email, extraInfo = draft.extraInfo)
        return execute<SaveResponse>(request(token).newBuilder()
            .post(JsonObject(json.encodeToJsonElement(payload).jsonObject.filterValues { it != JsonNull }).toString()
                .toRequestBody("application/json; charset=utf-8".toMediaType())).build()).comment
    }

    private fun request(token: String): Request {
        if (token.isBlank()) throw ClientPersonalCommentsFailure.Unauthorized
        return Request.Builder().url(endpoint).header("Authorization", "Bearer $token")
            .header("Accept", "application/json").header("Cache-Control", "no-cache")
            .header("X-LumaWork-Personal-Comments-Version", "2").build()
    }

    private suspend inline fun <reified T> execute(request: Request): T = withContext(Dispatchers.IO) {
        val response = try { client.newCall(request).awaitComments() }
        catch (error: IOException) { throw ClientPersonalCommentsFailure.Network(error) }
        response.use {
            if (it.code == 401 || it.code == 403) throw ClientPersonalCommentsFailure.Unauthorized
            val body = it.body.string()
            if (!it.isSuccessful) {
                val message = if (it.code == 409) runCatching { json.decodeFromString<ErrorResponse>(body).message }
                    .getOrNull()?.trim()?.takeIf { text -> text.isNotEmpty() && text.length <= 500 } else null
                throw ClientPersonalCommentsFailure.Http(it.code, message ?: "Не удалось загрузить или сохранить комментарий. Попробуйте ещё раз.")
            }
            try { responseJson.decodeFromString<T>(body) }
            catch (_: kotlinx.serialization.SerializationException) { throw ClientPersonalCommentsFailure.InvalidResponse }
            catch (_: IllegalArgumentException) { throw ClientPersonalCommentsFailure.InvalidResponse }
        }
    }
}

@Serializable private data class CommentsResponse(val comments: List<ClientPersonalComment>)
@Serializable private data class SaveResponse(val comment: ClientPersonalComment)
@Serializable private data class ErrorResponse(val message: String)
@Serializable private data class SavePayload(
    val id: String?, val sourceCommentID: String?, val displayTIN: String,
    val targets: List<ClientPersonalCommentTarget>, val terminalIDs: List<String>,
    val contactPerson: String, val phone: String, val email: String, val extraInfo: String,
)

private suspend fun Call.awaitComments(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
        override fun onResponse(call: Call, response: Response) { if (continuation.isActive) continuation.resume(response) else response.close() }
    })
}
