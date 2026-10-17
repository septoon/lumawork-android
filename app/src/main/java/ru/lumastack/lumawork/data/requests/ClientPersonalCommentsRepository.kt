package ru.lumastack.lumawork.data.requests

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.lumastack.lumawork.domain.model.AppSession
import ru.lumastack.lumawork.domain.requests.ClientPersonalCommentDraft
import ru.lumastack.lumawork.domain.requests.normalizedClientCommentScope

/** One repository is bound to one app account/token. Never retarget an in-flight repository. */
class ClientPersonalCommentsRepository(
    private val session: AppSession,
    private val api: ClientPersonalCommentsApi,
    private val cache: ClientPersonalCommentsCache,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val mutableSnapshot = MutableStateFlow(ClientPersonalCommentsSnapshot())
    val snapshot = mutableSnapshot.asStateFlow()
    private val mutableError = MutableStateFlow<String?>(null)
    val error = mutableError.asStateFlow()
    private val operations = Mutex()
    private val storage = Mutex()
    private var hydrated = false
    private var lastRefreshed: Long? = null

    suspend fun hydrate() = storage.withLock {
        if (!hydrated) {
            mutableSnapshot.value = cache.read(session.user.id)
            hydrated = true
        }
    }

    suspend fun refresh(offline: () -> Boolean, force: Boolean = false) {
        hydrate()
        if (offline()) return
        operations.withLock {
            if (offline() || (!force && lastRefreshed?.let { now() - it < 300_000 } == true)) return@withLock
            mutableError.value = null
            try {
                val comments = api.comments(session.token)
                currentCoroutineContext().ensureActive()
                storage.withLock {
                    val updated = mutableSnapshot.value.copy(comments = comments)
                    cache.write(session.user.id, updated)
                    mutableSnapshot.value = updated
                }
                lastRefreshed = now()
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { mutableError.value = error.message ?: "Не удалось загрузить личные комментарии." }
        }
    }

    suspend fun persistDraft(draft: ClientPersonalCommentDraft) {
        hydrate()
        storage.withLock {
            val updated = mutableSnapshot.value.copy(draft = draft)
            cache.write(session.user.id, updated)
            mutableSnapshot.value = updated
        }
    }

    suspend fun save(draft: ClientPersonalCommentDraft, offline: () -> Boolean) {
        if (offline()) throw ClientPersonalCommentsFailure.Offline
        if (!draft.canSave) throw ClientPersonalCommentsFailure.InvalidDraft
        persistDraft(draft)
        operations.withLock {
            if (offline()) throw ClientPersonalCommentsFailure.Offline
            val comment = api.save(session.token, draft)
            currentCoroutineContext().ensureActive()
            storage.withLock {
                val transferred = comment.terminalIDs.map(::normalizedClientCommentScope).toSet()
                val comments = mutableSnapshot.value.comments.filterNot { it.id == comment.id }.map { source ->
                    if (source.id != draft.sourceCommentID) source else source.copy(terminalIDs = source.terminalIDs.filterNot { normalizedClientCommentScope(it) in transferred })
                } + comment
                val updated = mutableSnapshot.value.copy(comments = comments, draft = null)
                // A successful server mutation stays visible even if persisting its cache fails.
                mutableSnapshot.value = updated
                try { cache.write(session.user.id, updated) }
                catch (error: CancellationException) { throw error }
                catch (_: Exception) { mutableError.value = "Комментарий сохранён на сервере. Не удалось обновить кеш устройства." }
            }
        }
    }

    fun clearError() { mutableError.value = null }
}
