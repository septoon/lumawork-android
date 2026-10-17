package ru.lumastack.lumawork.data.requests

import android.content.Context
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
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
}

class FileClientPersonalCommentsCache(
    private val directory: File,
    private val json: Json,
) : ClientPersonalCommentsCache {
    constructor(context: Context, json: Json) : this(File(context.noBackupFilesDir, "client-personal-comments"), json)

    override suspend fun read(userId: String): ClientPersonalCommentsSnapshot = withContext(Dispatchers.IO) {
        fileMutex.withLock { runCatching { json.decodeFromString<ClientPersonalCommentsSnapshot>(file(userId).readText()) }.getOrDefault(ClientPersonalCommentsSnapshot()) }
    }

    // Finish a tiny atomic disk write even when an editor is disposed or the account changes.
    override suspend fun write(userId: String, snapshot: ClientPersonalCommentsSnapshot) = withContext(NonCancellable + Dispatchers.IO) {
        fileMutex.withLock {
            check(directory.isDirectory || directory.mkdirs()) { "Не удалось сохранить комментарий на устройстве." }
            val destination = file(userId)
            val temporary = File(directory, "${destination.name}.tmp")
            temporary.writeText(json.encodeToString(snapshot))
            check(temporary.renameTo(destination)) { "Не удалось сохранить комментарий на устройстве." }
        }
    }

    private fun file(userId: String): File {
        require(userId.isNotBlank())
        val key = MessageDigest.getInstance("SHA-256").digest(userId.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(directory, "$key-v2.json")
    }

    companion object { private val fileMutex = Mutex() }
}
