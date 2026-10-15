package ru.lumastack.lumawork.data.auth

import ru.lumastack.lumawork.data.session.RememberedEmailStore
import ru.lumastack.lumawork.data.session.SessionStore
import ru.lumastack.lumawork.domain.model.AppSession

class AuthRepository(
    private val api: AuthApi,
    private val sessionStore: SessionStore,
    private val emailStore: RememberedEmailStore,
) {
    var session: AppSession? = sessionStore.read()
        private set

    val rememberedEmail: String
        get() = session?.user?.email ?: emailStore.read()

    suspend fun requestCode(rawEmail: String): String {
        val email = normalizeEmail(rawEmail)
        api.requestCode(email)
        emailStore.save(email)
        return email
    }

    suspend fun verifyCode(rawEmail: String, rawCode: String): AppSession {
        val email = normalizeEmail(rawEmail)
        val code = rawCode.trim()
        return api.verifyCode(email, code).also(::saveSession)
    }

    suspend fun restoreSession(): RestoreResult {
        val cached = session ?: return RestoreResult.SignedOut
        return try {
            val freshUser = api.currentUser(cached.token)
            val freshSession = cached.copy(user = freshUser)
            saveSession(freshSession)
            RestoreResult.Active(freshSession)
        } catch (_: AuthFailure.AccessRevoked) {
            clearSession()
            RestoreResult.AccessRevoked
        } catch (error: AuthFailure.Http) {
            if (error.status == 401 || error.status == 403) {
                clearSession()
                RestoreResult.SignedOut
            } else {
                RestoreResult.Offline(cached)
            }
        } catch (_: Exception) {
            RestoreResult.Offline(cached)
        }
    }

    suspend fun logout() {
        val token = session?.token
        if (token != null) runCatching { api.logout(token) }
        clearSession()
    }

    fun clearSession() {
        sessionStore.clear()
        session = null
    }

    private fun saveSession(value: AppSession) {
        sessionStore.save(value)
        emailStore.save(value.user.email)
        session = value
    }
}

sealed interface RestoreResult {
    data class Active(val session: AppSession) : RestoreResult
    data class Offline(val session: AppSession) : RestoreResult
    data object SignedOut : RestoreResult
    data object AccessRevoked : RestoreResult
}

fun normalizeEmail(value: String): String = value.trim().lowercase()

