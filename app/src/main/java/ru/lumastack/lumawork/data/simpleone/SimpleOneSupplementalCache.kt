package ru.lumastack.lumawork.data.simpleone

import android.content.Context
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
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

    /** Stage expensive work before the caller's short session-validated atomic commit. */
    suspend fun saveClosedArchive(ownerUserId: String, archive: ClosedSimpleOneArchive,
        commit: suspend (suspend () -> Unit) -> Unit) = commit { saveClosedArchive(ownerUserId, archive) }
    suspend fun saveDetail(ownerUserId: String, entry: SimpleOneDetailCacheEntry,
        commit: suspend (suspend () -> Unit) -> Unit) = commit { saveDetail(ownerUserId, entry) }
}

@Serializable
data class SimpleOneDetailCacheEntry(
    val request: SimpleOneRequest,
    val cachedAtEpochMillis: Long,
    val sessionFingerprint: String = "",
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

    private val archiveMutex = Mutex()
    private val detailMutex = Mutex()
    private val detailWriteMutex = Mutex()
    private data class LoadedArchive(val archive: ClosedSimpleOneArchive?, val payloadFingerprint: String?)
    private val archives = mutableMapOf<String, LoadedArchive>()
    private val details = mutableMapOf<String, SimpleOneDetailCacheSnapshot>()

    @Serializable
    private data class ArchiveMetadata(
        val payloadFingerprint: String,
        val archive: ClosedSimpleOneArchive,
    )

    override suspend fun readClosedArchive(ownerUserId: String): ClosedSimpleOneArchive? = archiveMutex.withLock {
        loadArchive(ownerUserId).archive
    }

    private suspend fun loadArchive(owner: String): LoadedArchive {
        archives[owner]?.let { return it }
        val file = closedFile(owner)
        val loaded = withContext(Dispatchers.IO) {
            if (!file.isFile || file.length() > MAX_CACHE_BYTES) return@withContext LoadedArchive(null, null)
            val bytes = runCatching { file.readBytes() }.getOrNull() ?: return@withContext LoadedArchive(null, null)
            val payload = runCatching { json.decodeFromString<ClosedSimpleOneArchive>(bytes.toString(Charsets.UTF_8)) }.getOrNull()
                ?: return@withContext LoadedArchive(null, null)
            val fingerprint = bytes.sha256()
            val metadata = read<ArchiveMetadata>(metadataFile(owner))?.takeIf { it.payloadFingerprint == fingerprint }
            LoadedArchive(metadata?.archive?.copy(requests = payload.requests) ?: payload, fingerprint)
        }
        return loaded.also { archives[owner] = it }
    }

    override suspend fun saveClosedArchive(ownerUserId: String, archive: ClosedSimpleOneArchive) =
        saveClosedArchive(ownerUserId, archive) { it() }

    override suspend fun saveClosedArchive(ownerUserId: String, archive: ClosedSimpleOneArchive,
        commit: suspend (suspend () -> Unit) -> Unit) = archiveMutex.withLock {
        if (archive.requests.size > MAX_CLOSED_REQUESTS) throw SimpleOneXlsxException("Архив слишком велик. Загрузка не сохранена.")
        val previous = loadArchive(ownerUserId)
        val sameRequests = withContext(Dispatchers.Default) { previous.archive?.requests == archive.requests }
        if (sameRequests && previous.payloadFingerprint != null) {
            if (withContext(Dispatchers.Default) { previous.archive == archive }) { commit {}; return@withLock }
            // Sidecar references the exact persisted payload. A crash can replay old metadata,
            // but can never apply a cursor to rows that have not been committed.
            val content = withContext(Dispatchers.Default) {
                json.encodeToString(ArchiveMetadata(previous.payloadFingerprint, archive.copy(requests = emptyList())))
            }
            write(metadataFile(ownerUserId), content, commit, afterReplace = {
                archives[ownerUserId] = LoadedArchive(archive, previous.payloadFingerprint)
            })
        } else {
            val content = withContext(Dispatchers.Default) { json.encodeToString(archive) }
            val fingerprint = withContext(Dispatchers.Default) { content.toByteArray(Charsets.UTF_8).sha256() }
            write(closedFile(ownerUserId), content, commit, beforeReplace = {
                val metadata = metadataFile(ownerUserId)
                if (metadata.exists() && !metadata.delete()) throw java.io.IOException("Не удалось обновить метаданные архива.")
            }, afterReplace = { archives[ownerUserId] = LoadedArchive(archive, fingerprint) })
        }
    }

    private suspend fun loadDetails(owner: String): SimpleOneDetailCacheSnapshot = details[owner]
        ?: (read<SimpleOneDetailCacheSnapshot>(detailsFile(owner)) ?: SimpleOneDetailCacheSnapshot())
            .also { details[owner] = it }

    override suspend fun readDetail(ownerUserId: String, requestId: String): SimpleOneDetailCacheEntry? =
        detailMutex.withLock { loadDetails(ownerUserId).entries[requestId] }

    override suspend fun saveDetail(ownerUserId: String, entry: SimpleOneDetailCacheEntry) =
        saveDetail(ownerUserId, entry) { it() }

    override suspend fun saveDetail(ownerUserId: String, entry: SimpleOneDetailCacheEntry,
        commit: suspend (suspend () -> Unit) -> Unit) = detailWriteMutex.withLock {
        val existing = detailMutex.withLock { loadDetails(ownerUserId).entries }
        val current = existing[entry.request.id]
        val currentVersion = current?.request?.sysUpdatedAt?.let(::closedSyncTimestamp)
        val incomingVersion = closedSyncTimestamp(entry.request.sysUpdatedAt)
        if (current?.sessionFingerprint == entry.sessionFingerprint && currentVersion != null &&
            incomingVersion != null && incomingVersion < currentVersion) {
            commit {}
            return@withLock
        }
        val snapshot = withContext(Dispatchers.Default) {
            SimpleOneDetailCacheSnapshot((existing + (entry.request.id to entry)).entries
                .sortedByDescending { it.value.cachedAtEpochMillis }.take(MAX_DETAIL_REQUESTS).associate { it.toPair() })
        }
        val content = withContext(Dispatchers.Default) { json.encodeToString(snapshot) }
        write(detailsFile(ownerUserId), content, commit, afterReplace = {
            detailMutex.withLock { details[ownerUserId] = snapshot }
        })
    }

    private suspend inline fun <reified T> read(file: File): T? = withContext(Dispatchers.IO) {
        if (!file.isFile || file.length() > MAX_CACHE_BYTES) return@withContext null
        runCatching { json.decodeFromString<T>(file.readText()) }.getOrNull()
    }

    private suspend fun write(file: File, content: String,
        commit: suspend (suspend () -> Unit) -> Unit,
        beforeReplace: () -> Unit = {}, afterReplace: suspend () -> Unit = {},
    ): Unit = withContext(Dispatchers.IO) {
        val started = System.nanoTime()
        if (content.length > MAX_CACHE_BYTES) throw SimpleOneXlsxException("Архив слишком велик. Загрузка не сохранена.")
        val bytes = content.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_CACHE_BYTES) throw SimpleOneXlsxException("Архив слишком велик. Загрузка не сохранена.")
        file.parentFile?.mkdirs()
        val temporary = File.createTempFile(file.name, ".tmp", file.parentFile)
        try {
            temporary.outputStream().use { it.write(bytes) }
            val stagedAt = System.nanoTime()
            commit {
                withContext(NonCancellable + Dispatchers.IO) {
                    beforeReplace()
                    if (!temporary.renameTo(file)) throw java.io.IOException("Не удалось сохранить локальный архив.")
                    afterReplace()
                }
            }
            val kind = when {
                file.name.endsWith("-closed-metadata.json") -> "closedMetadata"
                file.name.endsWith("-closed.json") -> "closedPayload"
                else -> "details"
            }
            android.util.Log.i("SimpleOneSync", "cache=$kind bytes=${bytes.size} stageMs=${(stagedAt - started) / 1_000_000} " +
                "commitMs=${(System.nanoTime() - stagedAt) / 1_000_000}")
        } finally {
            temporary.delete()
        }
    }

    private fun closedFile(ownerUserId: String): File = File(directory, "${ownerUserId.sha256()}-closed.json")

    private fun metadataFile(ownerUserId: String): File = File(directory, "${ownerUserId.sha256()}-closed-metadata.json")

    private fun detailsFile(ownerUserId: String): File = File(directory, "${ownerUserId.sha256()}-details.json")

    private fun String.sha256(): String = toByteArray().sha256()

    private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(this)
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
