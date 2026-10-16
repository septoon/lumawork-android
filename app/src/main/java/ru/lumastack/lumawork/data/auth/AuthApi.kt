package ru.lumastack.lumawork.data.auth

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import ru.lumastack.lumawork.domain.model.AppSession
import ru.lumastack.lumawork.domain.model.AppUser
import ru.lumastack.lumawork.domain.model.UserProfile
import java.util.Base64

interface AuthApi {
    suspend fun requestCode(email: String)
    suspend fun verifyCode(email: String, code: String): AppSession
    suspend fun currentUser(token: String): AppUser
    suspend fun logout(token: String)
    suspend fun updateProfile(token: String, profile: UserProfile): AppUser
    suspend fun uploadAvatar(token: String, bytes: ByteArray): AppUser
    suspend fun deleteAvatar(token: String): AppUser
}

sealed class AuthFailure(message: String, cause: Throwable? = null) : Exception(message, cause) {
    data object AccessRevoked : AuthFailure(
        "Доступ был отозван. Для восстановления обратитесь в поддержку contact@lumastack.ru.",
    )

    class Http(val status: Int, message: String) : AuthFailure(message)
    class Network(cause: IOException) : AuthFailure("Не удалось войти. Проверьте подключение к интернету.", cause)
    data object InvalidResponse : AuthFailure("Не удалось войти. Попробуйте ещё раз.")
}

class OkHttpAuthApi(
    baseOrigin: String,
    private val client: OkHttpClient = defaultClient(),
    private val json: Json = defaultJson,
) : AuthApi {
    private val origin = baseOrigin.trimEnd('/')

    override suspend fun requestCode(email: String) {
        val request = post("/auth/request-code", RequestCodeBody(email))
        execute(request).use(::requireSuccess)
    }

    override suspend fun verifyCode(email: String, code: String): AppSession {
        val request = post("/auth/verify-code", VerifyCodeBody(email, code))
        return execute(request).use { response ->
            val body = requireSuccess(response)
            decode(body)
        }
    }

    override suspend fun currentUser(token: String): AppUser {
        val request = requestBuilder("/me", token).get().build()
        return execute(request, timeoutSeconds = 8).use { response ->
            val body = requireSuccess(response)
            decode<CurrentUserResponse>(body).user
        }
    }

    override suspend fun logout(token: String) {
        val request = requestBuilder("/auth/logout", token)
            .post(ByteArray(0).toRequestBody(null))
            .build()
        execute(request).use(::requireSuccess)
    }

    override suspend fun updateProfile(token: String, profile: UserProfile): AppUser = withContext(Dispatchers.IO) {
        val request = requestBuilder("/api/v2/profile", token)
            .put(json.encodeToString(profile).toRequestBody(JSON_MEDIA_TYPE)).build()
        execute(request).use { decode<CurrentUserResponse>(requireSuccess(it)).user }
    }

    override suspend fun uploadAvatar(token: String, bytes: ByteArray): AppUser = withContext(Dispatchers.IO) {
        val body = AvatarBody(Base64.getEncoder().encodeToString(bytes), "image/jpeg")
        val request = requestBuilder("/api/v2/profile/avatar", token)
            .post(json.encodeToString(body).toRequestBody(JSON_MEDIA_TYPE)).build()
        execute(request).use { decode<CurrentUserResponse>(requireSuccess(it)).user }
    }

    override suspend fun deleteAvatar(token: String): AppUser = withContext(Dispatchers.IO) {
        val request = requestBuilder("/api/v2/profile/avatar", token).delete().build()
        execute(request).use { decode<CurrentUserResponse>(requireSuccess(it)).user }
    }

    private inline fun <reified Body> post(path: String, body: Body): Request = requestBuilder(path)
        .post(json.encodeToString(body).toRequestBody(JSON_MEDIA_TYPE))
        .build()

    private fun requestBuilder(path: String, token: String? = null): Request.Builder = Request.Builder()
        .url("$origin$path")
        .header("Accept", "application/json")
        .header("Cache-Control", "no-cache")
        .apply {
            if (token != null) header("Authorization", "Bearer $token")
        }

    private suspend fun execute(request: Request, timeoutSeconds: Long = 45): Response {
        val call = client.newCall(request)
        call.timeout().timeout(timeoutSeconds, TimeUnit.SECONDS)
        return try {
            call.await()
        } catch (error: IOException) {
            throw AuthFailure.Network(error)
        }
    }

    private fun requireSuccess(response: Response): String {
        val body = response.body.string()
        if (response.isSuccessful) return body

        val payload = runCatching { json.decodeFromString<ErrorResponse>(body) }.getOrNull()
        when (payload?.error) {
            "USER_BLOCKED" -> throw AuthFailure.AccessRevoked
            "CODE_RECENTLY_SENT" -> throw AuthFailure.Http(
                response.code,
                payload.message ?: "Код уже отправлен. Повторите через минуту.",
            )
            "INVALID_CODE" -> throw AuthFailure.Http(response.code, "Код не подошёл или истёк.")
        }
        throw AuthFailure.Http(
            status = response.code,
            message = payload?.message ?: "Не удалось войти. Попробуйте ещё раз.",
        )
    }

    private inline fun <reified Value> decode(body: String): Value = try {
        json.decodeFromString(body)
    } catch (_: Exception) {
        throw AuthFailure.InvalidResponse
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val defaultJson = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .writeTimeout(45, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}

private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            if (continuation.isActive) {
                continuation.resume(response)
            } else {
                response.close()
            }
        }
    })
}

@Serializable
private data class RequestCodeBody(val email: String)

@Serializable
private data class VerifyCodeBody(val email: String, val code: String)

@Serializable
private data class CurrentUserResponse(val user: AppUser)

@Serializable
private data class AvatarBody(val imageBase64: String, val mimeType: String)

@Serializable
private data class ErrorResponse(
    val error: String? = null,
    val message: String? = null,
)
