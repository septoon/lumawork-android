package ru.lumastack.lumawork.data.timereport

import android.content.Context
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import ru.lumastack.lumawork.domain.timereport.TimeReportSnapshot

interface TimeReportCache {
    suspend fun read(ownerUserId: String): TimeReportSnapshot?
    suspend fun save(ownerUserId: String, snapshot: TimeReportSnapshot)
    suspend fun delete(ownerUserId: String)
}

class FileTimeReportCache(
    context: Context,
    private val json: Json,
) : TimeReportCache {
    private val directory = File(context.noBackupFilesDir, "time-report")

    override suspend fun read(ownerUserId: String): TimeReportSnapshot? = withContext(Dispatchers.IO) {
        val source = file(ownerUserId)
        if (!source.isFile) return@withContext null
        runCatching { json.decodeFromString<TimeReportSnapshot>(source.readText()) }.getOrNull()
    }

    override suspend fun save(ownerUserId: String, snapshot: TimeReportSnapshot) = withContext(Dispatchers.IO) {
        val destination = file(ownerUserId)
        destination.parentFile?.mkdirs()
        val temporary = File(destination.parentFile, "${destination.name}.tmp")
        val encoded = json.encodeToString(snapshot)
        temporary.writeText(encoded)
        if (!temporary.renameTo(destination)) {
            destination.writeText(encoded)
            temporary.delete()
        }
    }

    override suspend fun delete(ownerUserId: String) = withContext(Dispatchers.IO) {
        file(ownerUserId).delete()
        Unit
    }

    private fun file(ownerUserId: String): File = File(directory, "${ownerUserId.sha256()}.json")

    private fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(toByteArray())
        .joinToString("") { "%02x".format(it) }
}
