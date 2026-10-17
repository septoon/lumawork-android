package ru.lumastack.lumawork.data.profile

import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.lumastack.lumawork.domain.model.AppSession
import ru.lumastack.lumawork.domain.profile.*

class WorkDocumentsRepository(private val api: WorkDocumentsApi, private val cache: WorkDocumentsCache) {
    // Serializing list and mutations prevents an older refresh from overwriting confirmed uploads/deletes.
    private val mutex = Mutex()
    private val mutableChanges = MutableSharedFlow<Pair<String, List<WorkDocument>>>(replay = 1, extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val changes = mutableChanges.asSharedFlow()

    suspend fun read(ownerUserId: String): List<WorkDocument> = mutex.withLock { cache.read(ownerUserId) }

    suspend fun refresh(session: AppSession, offline: Boolean = false): List<WorkDocument> = mutex.withLock {
        if (offline) cache.read(session.user.id) else persist(session.user.id, api.fetch(session.token))
    }

    suspend fun upload(session: AppSession, input: WorkDocumentInput, replacing: WorkDocument? = null,
        category: WorkDocumentCategory = replacing?.category ?: input.category, title: String = replacing?.title ?: input.title,
        offline: Boolean = false, onProgress: (Double) -> Unit = {}): List<WorkDocument> = mutex.withLock {
        requireOnline(offline)
        val documents = cache.read(session.user.id)
        if (replacing == null && documents.size >= WorkDocumentInput.MAX_DOCUMENTS) throw WorkDocumentsFailure.Document("Можно сохранить не более 100 рабочих документов.")
        val saved = api.upload(session.token, input, replacing?.id ?: UUID.randomUUID().toString(), category, validatedWorkDocumentTitle(title), onProgress)
        currentCoroutineContext().ensureActive()
        persist(session.user.id, documents.filterNot { it.id == saved.id } + saved)
    }

    suspend fun update(session: AppSession, document: WorkDocument, offline: Boolean = false): List<WorkDocument> = mutex.withLock {
        requireOnline(offline)
        val saved = api.update(session.token, document.copy(title = validatedWorkDocumentTitle(document.title)))
        currentCoroutineContext().ensureActive()
        persist(session.user.id, cache.read(session.user.id).filterNot { it.id == saved.id } + saved)
    }

    suspend fun delete(session: AppSession, documentId: String, offline: Boolean = false): List<WorkDocument> = mutex.withLock {
        requireOnline(offline)
        api.delete(session.token, documentId)
        currentCoroutineContext().ensureActive()
        persist(session.user.id, cache.read(session.user.id).filterNot { it.id == documentId })
    }

    suspend fun download(session: AppSession, documentId: String, offline: Boolean = false): ByteArray {
        requireOnline(offline)
        return api.download(session.token, documentId)
    }

    private suspend fun persist(owner: String, documents: List<WorkDocument>): List<WorkDocument> {
        currentCoroutineContext().ensureActive()
        val sorted = documents.sortedWith(compareBy<WorkDocument> { it.category.ordinal }.thenBy { it.title.lowercase() })
        cache.save(owner, sorted)
        currentCoroutineContext().ensureActive()
        mutableChanges.tryEmit(owner to sorted)
        return sorted
    }
    private fun requireOnline(offline: Boolean) { if (offline) throw WorkDocumentsFailure.Offline }
}
