package ru.lumastack.lumawork.data.requests

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ru.lumastack.lumawork.domain.model.AppSession
import ru.lumastack.lumawork.domain.requests.ClientPersonalComment
import ru.lumastack.lumawork.domain.requests.ClientPersonalCommentDraft
import ru.lumastack.lumawork.domain.requests.ClientPersonalCommentOptionsIndex
import ru.lumastack.lumawork.domain.requests.normalizedClientCommentScope
import ru.lumastack.lumawork.domain.simpleone.SimpleOneRequest

/** One repository is bound to one app account/token. Never retarget an in-flight repository. */
class ClientPersonalCommentsRepository(
    private val session: AppSession,
    private val api: ClientPersonalCommentsApi,
    private val cache: ClientPersonalCommentsCache,
    private val now: () -> Long = System::currentTimeMillis,
    private val sessionScope: CoroutineScope? = null,
) {
    private val mutableSnapshot = MutableStateFlow(ClientPersonalCommentsSnapshot())
    val snapshot = mutableSnapshot.asStateFlow()
    private val mutableError = MutableStateFlow<String?>(null)
    val error = mutableError.asStateFlow()
    private val mutableRefreshing = MutableStateFlow(false)
    val isRefreshing = mutableRefreshing.asStateFlow()
    private val saves = Mutex()
    private val saveRequests = Mutex()
    private var saveTask: Pair<ClientPersonalCommentDraft, Deferred<Unit>>? = null
    private val refreshes = Mutex()
    private val storage = Mutex()
    private val commentWrites = Mutex()
    private val draftWrites = Mutex()
    private val optionPreparation = Mutex()
    private var hydrated = false
    private var initialized = false
    private var lastRefreshed: Long? = null
    private val mutableLastSuccess = MutableStateFlow<Long?>(null)
    val lastSuccess = mutableLastSuccess.asStateFlow()
    private var refreshTask: CompletableDeferred<Unit>? = null
    private val savedDuringRefresh = mutableListOf<SavedComment>()
    private var optionRecords: List<SimpleOneRequest>? = null
    private var optionIndex: ClientPersonalCommentOptionsIndex? = null

    suspend fun hydrate() = storage.withLock {
        if (!hydrated) {
            mutableSnapshot.value = cache.read(session.user.id)
            hydrated = true
        }
    }

    /** Called by the session owner, never by navigation. Offline startup can initialize once online. */
    suspend fun initialize(offline: () -> Boolean) {
        hydrate()
        val start = refreshes.withLock {
            if (initialized || offline()) false else { initialized = true; true }
        }
        if (start) refresh(offline)
    }

    suspend fun optionsIndex(records: List<SimpleOneRequest>): ClientPersonalCommentOptionsIndex = withContext(Dispatchers.Default) {
        optionPreparation.withLock {
            optionIndex?.takeIf { optionRecords === records || optionRecords == records }?.also { optionRecords = records }
                ?: ClientPersonalCommentOptionsIndex(records).also { optionIndex = it; optionRecords = records }
        }
    }

    suspend fun refresh(offline: () -> Boolean, force: Boolean = false) {
        val owner = sessionScope
        if (owner == null) refreshNow(offline, force) else owner.async { refreshNow(offline, force) }.await()
    }

    private suspend fun refreshNow(offline: () -> Boolean, force: Boolean) {
        hydrate()
        if (offline()) return
        val (task, owner) = refreshes.withLock {
            refreshTask?.let { return@withLock it to false }
            if (offline() || (!force && lastRefreshed?.let { now() - it < 300_000 } == true)) return
            CompletableDeferred<Unit>().also { refreshTask = it } to true
        }
        if (!owner) { task.await(); return }
        try {
            storage.withLock { mutableRefreshing.value = true; savedDuringRefresh.clear() }
            mutableError.value = null
            val comments = api.comments(session.token)
            currentCoroutineContext().ensureActive()
            storage.withLock {
                val merged = savedDuringRefresh.fold(comments) { current, saved -> saved.applyTo(current) }
                val updated = mutableSnapshot.value.copy(comments = merged)
                mutableSnapshot.value = updated
            }
            persistComments()
            lastRefreshed = now()
            mutableLastSuccess.value = lastRefreshed
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) { mutableError.value = error.message ?: "Не удалось загрузить личные комментарии." }
        finally {
            // Always release refresh waiters, including when the owning session is cancelled.
            withContext(NonCancellable) {
                mutableRefreshing.value = false
                refreshes.withLock { if (refreshTask === task) refreshTask = null; task.complete(Unit) }
            }
        }
    }

    suspend fun persistDraft(draft: ClientPersonalCommentDraft) {
        hydrate()
        draftWrites.withLock {
            storage.withLock { mutableSnapshot.value = mutableSnapshot.value.copy(draft = draft) }
            cache.writeDraft(session.user.id, draft)
        }
    }

    /** Survives destination disposal, but remains cancellable when the account session ends. */
    internal fun persistDraftOnExit(draft: ClientPersonalCommentDraft, fallbackScope: CoroutineScope) =
        (sessionScope ?: fallbackScope).launch(start = CoroutineStart.UNDISPATCHED) {
            currentCoroutineContext().ensureActive()
            try { persistDraft(draft) }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { mutableError.value = "Не удалось сохранить черновик на устройстве." }
        }

    private suspend fun persistComments() = commentWrites.withLock {
        cache.writeComments(session.user.id, mutableSnapshot.value.comments)
    }

    suspend fun save(draft: ClientPersonalCommentDraft, offline: () -> Boolean) {
        val owner = sessionScope
        if (owner == null) { saveNow(draft, offline); return }
        val task = saveRequests.withLock {
            saveTask?.takeIf { it.first == draft && it.second.isActive }?.second
                ?: owner.async { saveNow(draft, offline) }.also { saveTask = draft to it }
        }
        task.await()
    }

    private suspend fun saveNow(draft: ClientPersonalCommentDraft, offline: () -> Boolean) = saves.withLock {
        if (offline()) throw ClientPersonalCommentsFailure.Offline
        if (!draft.canSave) throw ClientPersonalCommentsFailure.InvalidDraft
        persistDraft(draft)
        if (offline()) throw ClientPersonalCommentsFailure.Offline
        // POST is independent of a pending GET. Only publication and disk writes share storage.
        val comment = api.save(session.token, draft)
        currentCoroutineContext().ensureActive()
        storage.withLock {
            val mutation = SavedComment(comment, draft.sourceCommentID)
            if (mutableRefreshing.value) savedDuringRefresh += mutation
            val updated = mutableSnapshot.value.copy(comments = mutation.applyTo(mutableSnapshot.value.comments), draft = null)
            mutableSnapshot.value = updated
        }
        try {
            persistComments()
            draftWrites.withLock { cache.writeDraft(session.user.id, null) }
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) { mutableError.value = "Комментарий сохранён на сервере. Не удалось обновить кеш устройства." }
    }

    fun clearError() { mutableError.value = null }

    private data class SavedComment(val comment: ClientPersonalComment, val sourceID: String?) {
        fun applyTo(comments: List<ClientPersonalComment>): List<ClientPersonalComment> {
            val transferred = comment.terminalIDs.map(::normalizedClientCommentScope).toSet()
            return comments.filterNot { it.id == comment.id }.map { source ->
                if (source.id != sourceID) source else source.copy(terminalIDs = source.terminalIDs.filterNot { normalizedClientCommentScope(it) in transferred })
            } + comment
        }
    }
}
