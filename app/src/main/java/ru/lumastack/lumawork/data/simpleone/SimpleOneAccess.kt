package ru.lumastack.lumawork.data.simpleone

import java.io.IOException
import okhttp3.Interceptor
import okhttp3.Response

/** One boundary for protected caches, network requests, exports and background work. */
class SimpleOneAccess(
    private val currentOwner: () -> String?,
    private val readSession: (String) -> StoredSimpleOneSession?,
    private val clearSession: (String) -> Unit = {},
) {
    private val revision = kotlinx.coroutines.flow.MutableStateFlow(0L)
    val changes: kotlinx.coroutines.flow.StateFlow<Long> = revision
    fun sessionChanged() { synchronized(revision) { revision.value += 1 } }
    fun invalidate(session: StoredSimpleOneSession) {
        if (readSession(session.ownerUserId) == session) clearSession(session.ownerUserId)
    }

    class Denied : IOException("Доступ запрещен")

    fun require(owner: String): StoredSimpleOneSession {
        if (owner.isBlank() || currentOwner() != owner) throw Denied()
        return readSession(owner)?.takeIf { it.ownerUserId == owner && it.authKey.isNotBlank() } ?: throw Denied()
    }
    fun requireCurrent(): StoredSimpleOneSession = require(currentOwner() ?: throw Denied())
    fun isAuthorized(owner: String): Boolean = try { require(owner); true } catch (_: Denied) { false }

    suspend fun <T> protect(owner: String, block: suspend () -> T): T {
        val session = require(owner)
        val value = block()
        if (require(owner) != session) throw Denied()
        return value
    }

    companion object {
        val DenyAll = SimpleOneAccess({ null }, { null })
        const val HEADER = "X-SimpleOne-Auth"
    }
}

class SimpleOneAccessInterceptor(private val access: SimpleOneAccess) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val session = access.requireCurrent()
        val response = chain.proceed(chain.request().newBuilder().header(SimpleOneAccess.HEADER, session.authKey).build())
        if (response.code == 403 && response.header("X-SimpleOne-Access") == "denied") access.invalidate(session)
        try {
            if (access.require(session.ownerUserId) != session) throw SimpleOneAccess.Denied()
        } catch (error: Exception) { response.close(); throw error }
        return response
    }
}
