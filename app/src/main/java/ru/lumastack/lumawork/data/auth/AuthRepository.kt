package ru.lumastack.lumawork.data.auth

import ru.lumastack.lumawork.data.gsm.GsmProfile
import ru.lumastack.lumawork.data.session.RememberedEmailStore
import ru.lumastack.lumawork.data.session.SessionStore
import ru.lumastack.lumawork.domain.model.AppSession
import ru.lumastack.lumawork.domain.model.AppUser
import ru.lumastack.lumawork.domain.model.UserProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class AuthRepository(
    private val api: AuthApi,
    private val sessionStore: SessionStore,
    private val emailStore: RememberedEmailStore,
) {
    private val mutableSession = MutableStateFlow(sessionStore.read())
    val sessionChanges = mutableSession.asStateFlow()
    val session: AppSession? get() = mutableSession.value
    private val userUpdateMutex = Mutex()

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

    suspend fun restoreSession(): RestoreResult = userUpdateMutex.withLock {
        val cached = session ?: return@withLock RestoreResult.SignedOut
        try {
            val freshUser = api.currentUser(cached.token)
            if (session?.token != cached.token) return@withLock RestoreResult.SignedOut
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

    suspend fun updateProfile(profile: UserProfile) = updateUser { api.updateProfile(it, profile) }
    /** Runs only from the explicit Save of an imported GSM draft. */
    suspend fun fillProfileFromGsm(profile: GsmProfile, owner: String) = updateUser { token ->
        val current = api.currentUser(token)
        check(current.id == owner && session?.user?.id == owner && session?.token == token) {
            "Аккаунт изменился. Откройте ГСМ профиль заново."
        }
        val existing = current.profile ?: UserProfile()
        val filled = profile.fillingUserProfile(existing)
        if (filled == existing) current else api.updateProfile(token, filled)
    }

    suspend fun uploadAvatar(bytes: ByteArray) = updateUser { api.uploadAvatar(it, bytes) }
    suspend fun deleteAvatar() = updateUser { api.deleteAvatar(it) }

    private suspend fun updateUser(action: suspend (String) -> AppUser): AppSession = userUpdateMutex.withLock {
        val cached = session ?: throw AuthFailure.Http(401, "Войдите в аккаунт заново.")
        val user = try {
            action(cached.token)
        } catch (error: AuthFailure) {
            if (error is AuthFailure.AccessRevoked || error is AuthFailure.Http && error.status in listOf(401, 403)) clearSession()
            throw error
        }
        // A late response must never restore a session after logout or a different sign-in.
        if (session?.token != cached.token || user.id != cached.user.id) throw AuthFailure.Http(401, "Сессия изменилась. Войдите заново.")
        cached.copy(user = user).also(::saveSession)
    }

    suspend fun logout() {
        val token = session?.token
        if (token != null) runCatching { api.logout(token) }
        clearSession()
    }

    fun clearSession() {
        sessionStore.clear()
        mutableSession.value = null
    }

    private fun saveSession(value: AppSession) {
        sessionStore.save(value)
        emailStore.save(value.user.email)
        mutableSession.value = value
    }
}

sealed interface RestoreResult {
    data class Active(val session: AppSession) : RestoreResult
    data class Offline(val session: AppSession) : RestoreResult
    data object SignedOut : RestoreResult
    data object AccessRevoked : RestoreResult
}

fun normalizeEmail(value: String): String = value.trim().lowercase()

