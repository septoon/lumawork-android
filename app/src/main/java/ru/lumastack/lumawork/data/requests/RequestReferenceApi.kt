package ru.lumastack.lumawork.data.requests

import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl

/** Read-only references used by request details; paths and fields match the iOS client. */
internal class RequestReferenceApi(private val origin: String, private val json: Json) {
    private val client = OkHttpClient.Builder().callTimeout(20, java.util.concurrent.TimeUnit.SECONDS).build()
    suspend fun company(tin: String, token: String): List<Pair<String, String>> {
        require(tin.length in listOf(10, 12) && tin.all(Char::isDigit)) { "Некорректный ИНН." }
        val company = get("api/v2/reference/companies/$tin", token)["company"] as? JsonObject ?: error("Сервер вернул некорректные данные организации.")
        val titles = linkedMapOf("name" to "Название", "fullName" to "Полное наименование", "inn" to "ИНН", "kpp" to "КПП",
            "ogrn" to "ОГРН", "status" to "Статус", "legalForm" to "Организационно-правовая форма", "directorName" to "Руководитель",
            "directorPost" to "Должность", "address" to "Адрес", "okved" to "ОКВЭД", "okvedName" to "Вид деятельности", "registrationDate" to "Дата регистрации")
        return titles.mapNotNull { (key, title) -> (company[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }?.let { title to it } }
    }
    suspend fun drivers(token: String): Pair<String, String> {
        val drivers = get("api/v2/evotor-drivers", token)["drivers"] as? JsonObject ?: error("Сервер не вернул ссылки драйверов Эвотор.")
        fun url(key: String): String = (drivers[key] as? JsonPrimitive)?.contentOrNull?.trim()?.also {
            require(it.isBlank() || it.toHttpUrl().let { url -> url.username.isBlank() && url.password.isBlank() }) { "Некорректная ссылка драйвера." }
        } ?: error("Сервер не вернул ссылки драйверов Эвотор.")
        return url("aisinoURL") to url("paxURL")
    }
    private suspend fun get(path: String, token: String): JsonObject = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("${origin.trimEnd('/')}/$path").header("Accept", "application/json")
            .header("Authorization", "Bearer $token").build()
        client.newCall(request).awaitReference().use {
            val text = it.body.string()
            if (!it.isSuccessful) error("Не удалось загрузить данные. Попробуйте ещё раз.")
            json.parseToJsonElement(text) as? JsonObject ?: error("Сервер вернул некорректные данные.")
        }
    }
}
private suspend fun Call.awaitReference(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
        override fun onResponse(call: Call, response: Response) { if (continuation.isActive) continuation.resume(response) else response.close() }
    })
}
