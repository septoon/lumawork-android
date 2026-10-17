package ru.lumastack.lumawork.data.profile

import android.content.Context
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import ru.lumastack.lumawork.domain.profile.WorkDocument

interface WorkDocumentsCache {
    suspend fun read(ownerUserId: String): List<WorkDocument>
    suspend fun save(ownerUserId: String, documents: List<WorkDocument>)
}

class FileWorkDocumentsCache(private val directory: File, private val json: Json) : WorkDocumentsCache {
    constructor(context: Context, json: Json) : this(File(context.noBackupFilesDir, "work-documents"), json)
    override suspend fun read(ownerUserId: String): List<WorkDocument> = withContext(Dispatchers.IO) {
        val file = file(ownerUserId)
        if (!file.isFile || file.length() > 1024 * 1024) return@withContext emptyList()
        runCatching { json.decodeFromString<List<WorkDocument>>(file.readText()).take(100) }.getOrDefault(emptyList())
    }
    override suspend fun save(ownerUserId: String, documents: List<WorkDocument>) = withContext(Dispatchers.IO) {
        val destination = file(ownerUserId)
        directory.mkdirs()
        val temporary = File(directory, "${destination.name}.${UUID.randomUUID()}.tmp")
        try {
            temporary.writeText(json.encodeToString(documents.take(100)))
            currentCoroutineContext().ensureActive()
            check(temporary.renameTo(destination)) { "Не удалось сохранить список документов." }
        } finally { temporary.delete() }
    }
    private fun file(owner: String) = File(directory, "${workDocumentsOwnerHash(owner)}.json")
}

internal fun workDocumentsOwnerHash(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
    .joinToString("") { "%02x".format(it) }
