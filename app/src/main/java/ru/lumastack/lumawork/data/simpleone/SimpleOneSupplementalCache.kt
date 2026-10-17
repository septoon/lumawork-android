package ru.lumastack.lumawork.data.simpleone

import android.content.Context
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

class FileSimpleOneSupplementalCache internal constructor(
    private val directory: File,
    private val json: Json,
) : SimpleOneSupplementalCache {
    constructor(context: Context, json: Json) : this(File(context.noBackupFilesDir, "simpleone"), json)

    private val mutex = Mutex()

    override suspend fun readClosedArchive(ownerUserId: String): ClosedSimpleOneArchive? =
        mutex.withLock { read(closedFile(ownerUserId)) }

    override suspend fun saveClosedArchive(ownerUserId: String, archive: ClosedSimpleOneArchive) {
        mutex.withLock {
            if (archive.requests.size > MAX_CLOSED_REQUESTS) throw SimpleOneXlsxException("Архив слишком велик. Загрузка не сохранена.")
            val content = withContext(Dispatchers.Default) { json.encodeToString(archive) }
            write(closedFile(ownerUserId), content)
        }
    }

    override suspend fun readDetail(ownerUserId: String, requestId: String): SimpleOneDetailCacheEntry? =
        mutex.withLock { read<SimpleOneDetailCacheSnapshot>(detailsFile(ownerUserId))?.entries?.get(requestId) }

    override suspend fun saveDetail(ownerUserId: String, entry: SimpleOneDetailCacheEntry) = mutex.withLock {
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
        if (!file.isFile || file.length() > MAX_CACHE_BYTES) return@withContext null
        runCatching { json.decodeFromString<T>(file.readText()) }.getOrNull()
    }

    private suspend fun write(file: File, content: String) = withContext(Dispatchers.IO) {
        if (content.length > MAX_CACHE_BYTES) throw SimpleOneXlsxException("Архив слишком велик. Загрузка не сохранена.")
        val bytes = content.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_CACHE_BYTES) throw SimpleOneXlsxException("Архив слишком велик. Загрузка не сохранена.")
        file.parentFile?.mkdirs()
        val temporary = File.createTempFile(file.name, ".tmp", file.parentFile)
        try {
            temporary.outputStream().use { it.write(bytes) }
            if (!temporary.renameTo(file)) throw java.io.IOException("Не удалось сохранить локальный архив.")
        } finally {
            temporary.delete()
        }
    }

    private fun closedFile(ownerUserId: String): File = File(directory, "${ownerUserId.sha256()}-closed.json")

    private fun detailsFile(ownerUserId: String): File = File(directory, "${ownerUserId.sha256()}-details.json")

    private fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(toByteArray())
        .joinToString("") { "%02x".format(it) }

    companion object {
        private const val MAX_CLOSED_REQUESTS = 100_000
        private const val MAX_CACHE_BYTES = 128 * 1024 * 1024
        private const val MAX_DETAIL_REQUESTS = 100
    }
}

object EmptySimpleOneSupplementalCache : SimpleOneSupplementalCache {
    override suspend fun readClosedArchive(ownerUserId: String): ClosedSimpleOneArchive? = null
    override suspend fun saveClosedArchive(ownerUserId: String, archive: ClosedSimpleOneArchive) = Unit
    override suspend fun readDetail(ownerUserId: String, requestId: String): SimpleOneDetailCacheEntry? = null
    override suspend fun saveDetail(ownerUserId: String, entry: SimpleOneDetailCacheEntry) = Unit
}
