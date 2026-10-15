package ru.lumastack.lumawork.data.simpleone

import android.content.Context
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import ru.lumastack.lumawork.domain.simpleone.ClosedSimpleOneArchive
import ru.lumastack.lumawork.domain.simpleone.SimpleOneRequest

interface SimpleOneSupplementalCache {
    suspend fun readClosedArchive(ownerUserId: String): ClosedSimpleOneArchive?
    suspend fun saveClosedArchive(ownerUserId: String, archive: ClosedSimpleOneArchive)
    suspend fun readDetail(ownerUserId: String, requestId: String): SimpleOneDetailCacheEntry?
    suspend fun saveDetail(ownerUserId: String, entry: SimpleOneDetailCacheEntry)
}

@Serializable
data class SimpleOneDetailCacheEntry(
    val request: SimpleOneRequest,
    val cachedAtEpochMillis: Long,
)

@Serializable
private data class SimpleOneDetailCacheSnapshot(
    val entries: Map<String, SimpleOneDetailCacheEntry> = emptyMap(),
)

class FileSimpleOneSupplementalCache(
    context: Context,
    private val json: Json,
) : SimpleOneSupplementalCache {
    private val directory = File(context.noBackupFilesDir, "simpleone")

    override suspend fun readClosedArchive(ownerUserId: String): ClosedSimpleOneArchive? =
        read(closedFile(ownerUserId))

    override suspend fun saveClosedArchive(ownerUserId: String, archive: ClosedSimpleOneArchive) {
        val bounded = archive.copy(requests = archive.requests.take(MAX_CLOSED_REQUESTS))
        write(closedFile(ownerUserId), json.encodeToString(bounded))
    }

    override suspend fun readDetail(ownerUserId: String, requestId: String): SimpleOneDetailCacheEntry? =
        read<SimpleOneDetailCacheSnapshot>(detailsFile(ownerUserId))?.entries?.get(requestId)

    override suspend fun saveDetail(ownerUserId: String, entry: SimpleOneDetailCacheEntry) {
        val file = detailsFile(ownerUserId)
        val existing = read<SimpleOneDetailCacheSnapshot>(file)?.entries.orEmpty()
        val entries = (existing + (entry.request.id to entry))
            .entries
            .sortedByDescending { it.value.cachedAtEpochMillis }
            .take(MAX_DETAIL_REQUESTS)
            .associate { it.toPair() }
        write(file, json.encodeToString(SimpleOneDetailCacheSnapshot(entries)))
    }

    private suspend inline fun <reified T> read(file: File): T? = withContext(Dispatchers.IO) {
        if (!file.isFile) return@withContext null
        runCatching { json.decodeFromString<T>(file.readText()) }.getOrNull()
    }

    private suspend fun write(file: File, content: String) = withContext(Dispatchers.IO) {
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.writeText(content)
        if (!temporary.renameTo(file)) {
            file.writeText(content)
            temporary.delete()
        }
    }

    private fun closedFile(ownerUserId: String): File = File(directory, "${ownerUserId.sha256()}-closed.json")

    private fun detailsFile(ownerUserId: String): File = File(directory, "${ownerUserId.sha256()}-details.json")

    private fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(toByteArray())
        .joinToString("") { "%02x".format(it) }

    companion object {
        private const val MAX_CLOSED_REQUESTS = 5_000
        private const val MAX_DETAIL_REQUESTS = 100
    }
}

object EmptySimpleOneSupplementalCache : SimpleOneSupplementalCache {
    override suspend fun readClosedArchive(ownerUserId: String): ClosedSimpleOneArchive? = null
    override suspend fun saveClosedArchive(ownerUserId: String, archive: ClosedSimpleOneArchive) = Unit
    override suspend fun readDetail(ownerUserId: String, requestId: String): SimpleOneDetailCacheEntry? = null
    override suspend fun saveDetail(ownerUserId: String, entry: SimpleOneDetailCacheEntry) = Unit
}
