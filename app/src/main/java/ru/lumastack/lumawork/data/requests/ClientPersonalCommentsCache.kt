package ru.lumastack.lumawork.data.requests

import android.content.Context
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import ru.lumastack.lumawork.domain.requests.ClientPersonalComment
import ru.lumastack.lumawork.domain.requests.ClientPersonalCommentDraft

@Serializable
data class ClientPersonalCommentsSnapshot(
    val comments: List<ClientPersonalComment> = emptyList(),
    val draft: ClientPersonalCommentDraft? = null,
)

interface ClientPersonalCommentsCache {
    suspend fun read(userId: String): ClientPersonalCommentsSnapshot
    suspend fun write(userId: String, snapshot: ClientPersonalCommentsSnapshot)
    suspend fun writeComments(userId: String, comments: List<ClientPersonalComment>) {
        write(userId, read(userId).copy(comments = comments))
    }
    suspend fun writeDraft(userId: String, draft: ClientPersonalCommentDraft?) {
        write(userId, read(userId).copy(draft = draft))
    }
}

class FileClientPersonalCommentsCache(
    private val directory: File,
    private val json: Json,
) : ClientPersonalCommentsCache {
    constructor(context: Context, json: Json) : this(File(context.noBackupFilesDir, "client-personal-comments"), json)

    override suspend fun read(userId: String): ClientPersonalCommentsSnapshot = withContext(Dispatchers.IO) {
        val snapshot = fileMutex.withLock {
            currentCoroutineContext().ensureActive()
            runCatching { json.decodeFromString<ClientPersonalCommentsSnapshot>(file(userId).readText()) }.getOrDefault(ClientPersonalCommentsSnapshot())
        }
        draftMutex.withLock {
            currentCoroutineContext().ensureActive()
            val draftFile = draftFile(userId)
            // Absence means a legacy v2 embedded draft; explicit null prevents its resurrection.
            if (!draftFile.exists()) {
                // Migrate before a later list refresh removes the legacy embedded draft.
                snapshot.draft?.let { runCatching { atomicWrite(draftFile, json.encodeToString(DraftSnapshot(it))) } }
                snapshot
            } else runCatching {
                snapshot.copy(draft = json.decodeFromString<DraftSnapshot>(draftFile.readText()).draft)
            }.getOrDefault(snapshot)
        }
    }

    override suspend fun write(userId: String, snapshot: ClientPersonalCommentsSnapshot) {
        writeComments(userId, snapshot.comments)
        writeDraft(userId, snapshot.draft)
    }

    override suspend fun writeComments(userId: String, comments: List<ClientPersonalComment>) = withContext(Dispatchers.IO) {
        val contents = json.encodeToString(ClientPersonalCommentsSnapshot(comments))
        fileMutex.withLock {
            currentCoroutineContext().ensureActive()
            atomicWrite(file(userId), contents)
        }
    }

    override suspend fun writeDraft(userId: String, draft: ClientPersonalCommentDraft?) = withContext(Dispatchers.IO) {
        val contents = json.encodeToString(DraftSnapshot(draft))
        draftMutex.withLock {
            currentCoroutineContext().ensureActive()
            atomicWrite(draftFile(userId), contents)
        }
    }

    private suspend fun atomicWrite(destination: File, contents: String) {
        check(directory.isDirectory || directory.mkdirs()) { "Не удалось сохранить комментарий на устройстве." }
        val temporary = File(directory, "${destination.name}.tmp")
        try {
            temporary.writeText(contents)
            currentCoroutineContext().ensureActive()
            // Only the final replacement is non-cancellable, while holding the file's mutex.
            withContext(NonCancellable) {
                check(temporary.renameTo(destination)) { "Не удалось сохранить комментарий на устройстве." }
            }
        } finally { temporary.delete() }
    }

    private fun draftFile(userId: String) = File(directory, "${file(userId).nameWithoutExtension}-draft.json")

    private fun file(userId: String): File {
        require(userId.isNotBlank())
        val key = MessageDigest.getInstance("SHA-256").digest(userId.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(directory, "$key-v2.json")
    }

    @Serializable private data class DraftSnapshot(val draft: ClientPersonalCommentDraft? = null)
    companion object {
        private val fileMutex = Mutex()
        private val draftMutex = Mutex()
    }
}
