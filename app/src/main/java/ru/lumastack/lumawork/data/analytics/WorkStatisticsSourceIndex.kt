package ru.lumastack.lumawork.data.analytics

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Instant
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import ru.lumastack.lumawork.domain.analytics.*
import ru.lumastack.lumawork.domain.simpleone.*

@Serializable data class IndexedWork(val sysId: String, val number: String, val contribution: WorkContribution)
@Serializable data class WorkSourceIndex(
    val owner: String, val sourceUserId: String, val sessionFingerprint: String,
    val entries: Map<String, IndexedWork>, val identities: Map<String, String>,
    val sourceSyncedAt: String, val baselineAt: String = sourceSyncedAt,
    val monthlyCursor: ClosedRequestsSyncCursor? = null, val wideCursor: ClosedRequestsSyncCursor? = null,
    val rulesVersion: Int = 1, val queryRevision: Int = 2,
) {
    fun apply(changes: List<ClosedRequestsSyncChange>): WorkSourceIndex {
        val rows = entries.toMutableMap()
        val ids = identities.toMutableMap()
        val idsByNumber = ids.entries.associate { it.value to it.key }.toMutableMap()
        changes.forEach { change ->
            val id = normalizeIdentity(change.sysId)
            val number = normalizeIdentity(change.number)
            require(id.isNotBlank() && number.isNotBlank())
            require(ids[id]?.let { it == number } != false && idsByNumber[number]?.let { it == id } != false) { "Conflicting source identity" }
            ids[id] = number
            idsByNumber[number] = id
            rows.remove("number:$number")
            if (change.request == null) rows.remove("id:$id") else {
                require(normalizeIdentity(change.request.sysId) == id && normalizeIdentity(change.request.number) == number)
                rows["id:$id"] = IndexedWork(id, number, workContribution(change.request))
            }
        }
        return copy(entries = rows, identities = ids)
    }
    fun aggregate(): Pair<List<WorkStatisticsMonth>, WorkMetrics> {
        val months = sortedMapOf<String, WorkMetrics>()
        var unknown = WorkMetrics()
        entries.values.forEach { entry ->
            val c = entry.contribution
            if (c.monthKey == null) unknown += c.metrics else months[c.monthKey] = (months[c.monthKey] ?: WorkMetrics()) + c.metrics
        }
        return months.map { WorkStatisticsMonth(it.key, it.value) } to unknown
    }
    companion object {
        fun baseline(owner: String, sourceUserId: String, sessionFingerprint: String, records: List<SimpleOneRequest>, syncedAt: String): WorkSourceIndex {
            val identities = mutableMapOf<String, String>()
            val byNumber = mutableMapOf<String, String>()
            records.forEach { r ->
                val id = normalizeIdentity(r.sysId)
                val number = normalizeIdentity(r.number)
                require(number.isNotBlank())
                if (id.isNotBlank()) {
                    require(identities[id]?.let { it == number } != false && byNumber[number]?.let { it == id } != false) { "Conflicting source identity" }
                    identities[id] = number
                    byNumber[number] = id
                }
            }
            val entries = mutableMapOf<String, IndexedWork>()
            records.forEach { r ->
                val number = normalizeIdentity(r.number)
                val id = byNumber[number].orEmpty()
                val key = if (id.isBlank()) "number:$number" else "id:$id"
                val row = IndexedWork(id, number, workContribution(r))
                require(entries[key]?.let { it == row } != false) { "Conflicting duplicate source row" }
                entries[key] = row
            }
            return WorkSourceIndex(owner, sourceUserId, sessionFingerprint, entries, identities, syncedAt)
        }
    }
}
private fun normalizeIdentity(value: String) = value.trim().lowercase(Locale.ROOT)

@Serializable data class WorkStatisticsPending(
    val owner: String, val fingerprint: String, val generation: Long, val payload: WorkStatisticsUpload,
)
@Serializable data class WorkStatisticsDiskState(
    val index: WorkSourceIndex? = null, val dirty: Boolean = false, val generation: Long = 0,
    val pending: WorkStatisticsPending? = null,
) {
    fun requiresBaseline(owner: String, source: String, fingerprint: String, nowMillis: Long): Boolean {
        val i = index ?: return true
        return dirty || i.owner != owner || i.sourceUserId != source || i.sessionFingerprint != fingerprint || i.rulesVersion != 1 || i.queryRevision != 2 ||
            runCatching { nowMillis - Instant.parse(i.baselineAt).toEpochMilli() >= 24 * 60 * 60 * 1000L }.getOrDefault(true)
    }
}

/** All methods are called under the owning repository's mutex. Files live in noBackupFilesDir. */
class WorkStatisticsSourceIndex(private val directory: File, private val json: Json) {
    suspend fun read(owner: String): WorkStatisticsDiskState = withContext(Dispatchers.IO) {
        val file = File(directory, "${analyticsFileKey(owner)}.json")
        if (!file.isFile || file.length() > 32 * 1024 * 1024) return@withContext WorkStatisticsDiskState(dirty = true)
        runCatching { json.decodeFromString<WorkStatisticsDiskState>(file.readText()) }.getOrElse { WorkStatisticsDiskState(dirty = true) }
    }
    suspend fun write(owner: String, state: WorkStatisticsDiskState) {
        val content = withContext(Dispatchers.Default) { json.encodeToString(state) }
        withContext(Dispatchers.IO) { atomicAnalyticsWrite(File(directory, "${analyticsFileKey(owner)}.json"), content) }
    }
    suspend fun clear() = withContext(Dispatchers.IO) { directory.listFiles()?.forEach { check(it.delete()) } ; Unit }
}
internal fun analyticsFileKey(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
internal fun atomicAnalyticsWrite(file: File, content: String) {
    check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
    val temp = File.createTempFile(file.name, ".tmp", file.parentFile)
    try {
        FileOutputStream(temp).use { it.write(content.toByteArray()); it.fd.sync() }
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } finally { temp.delete() }
}
