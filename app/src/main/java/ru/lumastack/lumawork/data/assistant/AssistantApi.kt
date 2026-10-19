package ru.lumastack.lumawork.data.assistant

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

class OkHttpAssistantApi(origin: String,
    private val client: OkHttpClient = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(180, TimeUnit.SECONDS)
        .callTimeout(210, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build(),
) : AssistantApi {
    private val base = origin.trimEnd('/').toHttpUrl().also { require(it.isHttps) }
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; coerceInputValues = true }
    override suspend fun run(token: String, request: AssistantRunRequest, streaming: Boolean, onDelta: (String) -> Unit): AssistantResponse {
        val path = "api/v2/assistant/runs" + if (streaming) "/stream" else ""
        val call = authorized(token, path).post(body(json.encodeToString(request)))
            .header("Accept", if (streaming) "text/event-stream" else "application/json").build()
        return execute(call) { response ->
            val text = if (response.isSuccessful) null else readBounded(response)
            if (!response.isSuccessful) {
                val error = runCatching { json.parseToJsonElement(text!!).jsonObject }.getOrNull()
                if (error?.get("error")?.jsonPrimitive?.contentOrNull == "ASSISTANT_CONVERSATION_TOKEN_LIMIT")
                    return@execute AssistantResponse("conversation_full", error["message"]?.jsonPrimitive?.contentOrNull,
                        conversation = error["conversation"]?.let { runCatching { json.decodeFromJsonElement<AssistantStatus>(it) }.getOrNull() })
                throw failure(response.code, error)
            }
            if (streaming) AssistantSseReader.read(requireNotNull(response.body).source(), json, onDelta)
            else json.decodeFromString<AssistantResponse>(readBounded(response))
        }
    }
    override suspend fun conversations(token: String, cursor: String?): AssistantConversationPage = get(token, "api/v2/assistant/conversations", cursor)
    override suspend fun messages(token: String, conversationId: String, cursor: String?): AssistantMessagePage = get(token, "api/v2/assistant/conversations/${safeId(conversationId)}/messages", cursor)
    override suspend fun rename(token: String, conversationId: String, title: String) = mutate(authorized(token, "api/v2/assistant/conversations/${safeId(conversationId)}")
        .patch(body(json.encodeToString(mapOf("title" to title.trim().take(80))))).build())
    override suspend fun delete(token: String, conversationId: String) = mutate(authorized(token, "api/v2/assistant/conversations/${safeId(conversationId)}").delete().build())
    override suspend fun feedback(token: String, body: JsonObject) = mutate(authorized(token, "api/v2/knowledge/feedback").post(body(json.encodeToString(body))).build())
    private suspend inline fun <reified T> get(token: String, path: String, cursor: String?): T {
        val url = base.newBuilder().addPathSegments(path).addQueryParameter("limit", "60").apply { cursor?.let { addQueryParameter("cursor", it) } }.build()
        return execute(authorized(token, path).url(url).get().build()) { response -> validate(response); json.decodeFromString<T>(readBounded(response)) }
    }
    private suspend fun mutate(request: Request) = execute(request) { response -> validate(response); Unit }
    private fun validate(response: Response) {
        if (!response.isSuccessful) throw failure(response.code, runCatching { json.parseToJsonElement(readBounded(response)).jsonObject }.getOrNull())
    }
    private fun failure(code: Int, error: JsonObject?) = AssistantFailure(
        if (code == 401 || code == 403) "Сессия приложения истекла. Войдите заново."
        else error?.get("message")?.jsonPrimitive?.contentOrNull?.take(300) ?: "Помощник временно недоступен. Повторите запрос.",
        code, retryable = code in listOf(404, 405, 408, 429) || code >= 500)
    private fun authorized(token: String, path: String): Request.Builder {
        require(token.isNotBlank()) { "Войдите в приложение заново." }
        return Request.Builder().url(base.newBuilder().addPathSegments(path).build()).header("Authorization", "Bearer $token")
            .header("Accept", "application/json").header("Cache-Control", "no-cache")
    }
    private fun safeId(value: String) = java.util.UUID.fromString(value).toString()
    private fun body(value: String) = value.toRequestBody("application/json; charset=utf-8".toMediaType())
    private fun readBounded(response: Response): String {
        val source = requireNotNull(response.body).source()
        check(!source.request(2_000_001)) { "Ответ помощника превышает допустимый размер." }
        return source.readUtf8()
    }
    private suspend fun <T> execute(request: Request, read: (Response) -> T): T = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(AssistantFailure("Не удалось подключиться к помощнику. Повторите запрос.")) }
            override fun onResponse(call: Call, response: Response) {
                try { val result = response.use(read); if (continuation.isActive) continuation.resume(result) }
                catch (error: Exception) { if (continuation.isActive) continuation.resumeWithException(error) }
            }
        })
    }
}
internal object AssistantSseReader {
    fun read(source: okio.BufferedSource, json: Json, onDelta: (String) -> Unit): AssistantResponse {
        var name = ""; val data = mutableListOf<String>(); var characters = 0
        fun consume(): AssistantResponse? {
            if (data.isEmpty()) return null
            val value = data.joinToString("\n"); data.clear()
            return when (name) {
                "delta" -> { val delta = json.parseToJsonElement(value).jsonObject["text"]?.jsonPrimitive?.contentOrNull.orEmpty(); onDelta(delta); null }
                "done" -> json.decodeFromString<AssistantResponse>(value)
                "error" -> {
                    val error = json.parseToJsonElement(value).jsonObject
                    if (error["error"]?.jsonPrimitive?.contentOrNull == "ASSISTANT_CONVERSATION_TOKEN_LIMIT")
                        AssistantResponse("conversation_full", error["message"]?.jsonPrimitive?.contentOrNull,
                            conversation = error["conversation"]?.let { json.decodeFromJsonElement<AssistantStatus>(it) })
                    else throw AssistantFailure(error["message"]?.jsonPrimitive?.contentOrNull ?: "Не удалось получить ответ помощника.")
                }
                else -> null
            }
        }
        while (true) {
            val line = source.readUtf8LineStrictOrNull() ?: break
            characters += line.length
            check(characters <= 2_000_000) { "Поток ответа превышает допустимый размер." }
            when {
                line.isEmpty() -> { consume()?.let { return it }; name = "" }
                line.startsWith("event:") -> name = line.drop(6).trim()
                line.startsWith("data:") -> data += line.drop(5).removePrefix(" ")
            }
        }
        consume()?.let { return it }
        throw AssistantFailure("Поток ответа завершился раньше времени. Повторите запрос.")
    }
    private fun okio.BufferedSource.readUtf8LineStrictOrNull(): String? = if (exhausted()) null else try { readUtf8LineStrict(2_000_000) } catch (error: java.io.EOFException) {
        if (buffer.size > 2_000_000) throw error
        readUtf8()
    }
}
