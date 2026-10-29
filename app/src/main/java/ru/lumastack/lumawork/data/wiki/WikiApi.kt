package ru.lumastack.lumawork.data.wiki

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import ru.lumastack.lumawork.domain.wiki.WikiArticle
import ru.lumastack.lumawork.domain.wiki.WikiSearchResult

interface WikiApi {
    suspend fun catalogVersion(): String?
    suspend fun search(query: String, limit: Int = 10): List<WikiSearchResult>
    suspend fun article(id: String, maxChars: Int = 90_000): WikiArticle
    suspend fun isPdfAvailable(id: String): Boolean
    suspend fun pdf(id: String): ByteArray = throw WikiFailure.NotConfigured
    fun pdfUrl(id: String): String
}

sealed class WikiFailure(message: String, cause: Throwable? = null) : Exception(message, cause) {
    data object NotConfigured : WikiFailure("Вики временно недоступна.")
    class Network(cause: IOException) : WikiFailure("Вики временно недоступна.", cause)
    class Http(status: Int) : WikiFailure("Не удалось загрузить Вики. Попробуйте ещё раз.")
    data object InvalidResponse : WikiFailure("Вики вернула некорректные данные.")
}

class OkHttpWikiApi(
    origin: String,
    private val token: () -> String,
    private val json: Json,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true).build(),
) : WikiApi {
    private val base = origin.trimEnd('/').toHttpUrl()

    override suspend fun catalogVersion(): String? {
        val response = executeSuspend<WikiHealthResponse>(request(base.newBuilder().addPathSegment("health").build().toString()))
        return response.snapshotId?.takeIf(String::isNotBlank) ?: response.contentVersion?.takeIf(String::isNotBlank)
    }

    override suspend fun search(query: String, limit: Int): List<WikiSearchResult> {
        requireToken()
        val url = base.newBuilder().addPathSegment("search").addQueryParameter("q", query)
            .addQueryParameter("limit", limit.toString()).build()
        return executeSuspend<WikiSearchResponse>(request(url.toString())).results
    }

    override suspend fun article(id: String, maxChars: Int): WikiArticle {
        requireToken()
        val url = base.newBuilder().addPathSegment("article").addPathSegment(id)
            .addQueryParameter("maxChars", maxChars.toString()).build()
        return executeSuspend(request(url.toString()))
    }

    override suspend fun isPdfAvailable(id: String): Boolean {
        requireToken()
        val response = runCatching { executeResponse(request(pdfUrl(id), method = "HEAD", accept = "application/pdf")) }.getOrNull()
            ?: return false
        return response.use { it.isSuccessful }
    }

    override fun pdfUrl(id: String): String = base.newBuilder().addPathSegment("pdf").addPathSegment(id).build().toString()

    override suspend fun pdf(id: String): ByteArray {
        requireToken()
        return executeResponse(request(pdfUrl(id), accept = "application/pdf")).use { response ->
            val source = response.body.source()
            if (source.request(20_000_001)) throw WikiFailure.InvalidResponse
            source.readByteArray()
        }
    }

    private fun request(url: String, method: String = "GET", accept: String = "application/json"): Request = Request.Builder()
        .url(url).header("Accept", accept).apply {
            token().takeIf(String::isNotBlank)?.let { header("Authorization", "Bearer $it") }
            if (method == "HEAD") head() else get()
        }.build()

    private suspend fun executeResponse(request: Request): Response {
        val response = try { client.newCall(request).awaitWiki() } catch (error: IOException) { throw WikiFailure.Network(error) }
        if (!response.isSuccessful) {
            val status = response.code
            response.close()
            throw WikiFailure.Http(status)
        }
        return response
    }

    private suspend inline fun <reified Value> executeSuspend(request: Request): Value = executeResponse(request).use { response ->
        runCatching { json.decodeFromString<Value>(response.body.string()) }.getOrElse { throw WikiFailure.InvalidResponse }
    }

    private fun requireToken() { if (token().isBlank()) throw WikiFailure.NotConfigured }

}

@Serializable
private data class WikiHealthResponse(val snapshotId: String? = null, val contentVersion: String? = null)

@Serializable
private data class WikiSearchResponse(val query: String = "", val results: List<WikiSearchResult> = emptyList())

private suspend fun Call.awaitWiki(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
        override fun onResponse(call: Call, response: Response) {
            if (continuation.isActive) continuation.resume(response) else response.close()
        }
    })
}
