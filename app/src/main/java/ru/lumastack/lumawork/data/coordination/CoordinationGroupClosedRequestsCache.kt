package ru.lumastack.lumawork.data.coordination

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import ru.lumastack.lumawork.domain.simpleone.SimpleOneRequest

@Serializable
data class GroupClosedRequestsSnapshot(
    val records: List<SimpleOneRequest> = emptyList(),
    val refreshedIDs: Set<String> = emptySet(),
    val nextPage: Int? = 1,
    val totalCount: Int? = null,
    val updatedAtEpochMillis: Long? = null,
    val isIncremental: Boolean = false,
)

interface CoordinationGroupClosedRequestsCache {
    /** Opaque digest of owner and SimpleOne user; authorization keys are never part of the persisted namespace. */
    suspend fun read(scopeKey: String): GroupClosedRequestsSnapshot?
    suspend fun save(scopeKey: String, snapshot: GroupClosedRequestsSnapshot)
}

class FileCoordinationGroupClosedRequestsCache(
    context: Context,
    private val json: Json,
) : CoordinationGroupClosedRequestsCache {
    private val directory = File(context.noBackupFilesDir, "coordination-group-closed")

    override suspend fun read(scopeKey: String): GroupClosedRequestsSnapshot? = withContext(Dispatchers.IO) {
        runCatching {
            file(scopeKey).openRead().bufferedReader().use { json.decodeFromString<GroupClosedRequestsSnapshot>(it.readText()) }
        }.getOrNull()
    }

    override suspend fun save(scopeKey: String, snapshot: GroupClosedRequestsSnapshot) = withContext(Dispatchers.IO) {
        directory.mkdirs()
        val file = file(scopeKey)
        val stream = file.startWrite()
        try {
            stream.write(json.encodeToString(snapshot).toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (error: Throwable) {
            file.failWrite(stream)
            throw error
        }
    }

    private fun file(scopeKey: String) = AtomicFile(File(directory, "${groupClosedDigest(scopeKey)}.json"))
}

internal fun groupClosedDigest(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
