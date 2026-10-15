package ru.lumastack.lumawork.data.simpleone

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.lumastack.lumawork.domain.simpleone.ClosedArchiveSource
import ru.lumastack.lumawork.domain.simpleone.ClosedSimpleOneArchive
import ru.lumastack.lumawork.domain.simpleone.SimpleOneRequest
import ru.lumastack.lumawork.domain.simpleone.SimpleOneSnapshot

class SimpleOneRepository(
    private val api: SimpleOneApi,
    private val sessionStore: SimpleOneSessionStore,
    private val cache: SimpleOneCache,
    private val supplementalCache: SimpleOneSupplementalCache = EmptySimpleOneSupplementalCache,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) {
    suspend fun storedSession(ownerUserId: String): StoredSimpleOneSession? = withContext(Dispatchers.IO) {
        sessionStore.read(ownerUserId)
    }

    suspend fun rememberedUsername(ownerUserId: String): String = withContext(Dispatchers.IO) {
        sessionStore.readUsername(ownerUserId)
    }

    suspend fun readSnapshot(ownerUserId: String): SimpleOneSnapshot? = cache.read(ownerUserId)

    suspend fun readClosedArchive(ownerUserId: String): ClosedSimpleOneArchive? =
        supplementalCache.readClosedArchive(ownerUserId)

    suspend fun signIn(
        ownerUserId: String,
        username: String,
        password: String,
        cached: SimpleOneSnapshot?,
    ): SimpleOneSnapshot {
        val authKey = api.login(username.trim(), password.trim())
        withContext(Dispatchers.IO) {
            sessionStore.save(StoredSimpleOneSession(ownerUserId, authKey, username.trim()))
        }
        return refresh(ownerUserId, cached)
    }

    suspend fun refresh(ownerUserId: String, cached: SimpleOneSnapshot?): SimpleOneSnapshot {
        val session = storedSession(ownerUserId) ?: throw SimpleOneFailure.MissingCredentials
        return try {
            val currentUser = cached?.currentUser ?: api.currentUser(session.authKey)
            val requests = api.activeRequests(currentUser.sysId, session.authKey)
            SimpleOneSnapshot(
                currentUser = currentUser,
                activeRequests = requests,
                updatedAtEpochMillis = nowEpochMillis(),
            ).also { cache.save(ownerUserId, it) }
        } catch (error: SimpleOneFailure.Unauthorized) {
            withContext(Dispatchers.IO) { sessionStore.clear(ownerUserId) }
            throw error
        }
    }

    suspend fun refreshClosedArchive(
        ownerUserId: String,
        cachedSnapshot: SimpleOneSnapshot,
    ): ClosedSimpleOneArchive {
        val session = storedSession(ownerUserId) ?: throw SimpleOneFailure.MissingCredentials
        return try {
            val currentUser = cachedSnapshot.currentUser ?: api.currentUser(session.authKey)
            val archive = try {
                val xlsx = api.exportClosedRequests(currentUser.sysId, session.authKey)
                val records = withContext(Dispatchers.Default) { SimpleOneXlsxParser.parse(xlsx) }
                ClosedSimpleOneArchive(
                    requests = records,
                    updatedAtEpochMillis = nowEpochMillis(),
                    isComplete = true,
                    source = ClosedArchiveSource.Xlsx,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: SimpleOneFailure.Unauthorized) {
                throw error
            } catch (_: Exception) {
                ClosedSimpleOneArchive(
                    requests = api.closedRequests(currentUser.sysId, session.authKey),
                    updatedAtEpochMillis = nowEpochMillis(),
                    isComplete = true,
                    source = ClosedArchiveSource.ListApi,
                )
            }
            supplementalCache.saveClosedArchive(ownerUserId, archive)
            archive
        } catch (error: SimpleOneFailure.Unauthorized) {
            withContext(Dispatchers.IO) { sessionStore.clear(ownerUserId) }
            throw error
        }
    }

    suspend fun requestDetails(ownerUserId: String, request: SimpleOneRequest): SimpleOneRequest {
        val cached = supplementalCache.readDetail(ownerUserId, request.id)
        if (cached != null && nowEpochMillis() - cached.cachedAtEpochMillis < DETAIL_CACHE_LIFETIME_MILLIS) {
            return cached.request
        }

        val session = storedSession(ownerUserId) ?: return cached?.request ?: request
        return try {
            api.requestDetails(request, session.authKey).also { detailed ->
                supplementalCache.saveDetail(
                    ownerUserId,
                    SimpleOneDetailCacheEntry(detailed, nowEpochMillis()),
                )
            }
        } catch (error: SimpleOneFailure.Unauthorized) {
            withContext(Dispatchers.IO) { sessionStore.clear(ownerUserId) }
            throw error
        } catch (error: Exception) {
            cached?.request ?: throw error
        }
    }

    suspend fun signOut(ownerUserId: String) {
        withContext(Dispatchers.IO) { sessionStore.clear(ownerUserId) }
    }

    companion object {
        private const val DETAIL_CACHE_LIFETIME_MILLIS = 24L * 60 * 60 * 1_000
    }
}
