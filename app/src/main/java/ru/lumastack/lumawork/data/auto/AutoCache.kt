package ru.lumastack.lumawork.data.auto

import android.content.Context
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import ru.lumastack.lumawork.domain.auto.AutoSnapshot

interface AutoCache {
    suspend fun read(ownerUserId: String): AutoSnapshot?
    suspend fun save(ownerUserId: String, snapshot: AutoSnapshot)
}

class FileAutoCache(context: Context, private val json: Json) : AutoCache {
    private val directory = File(context.noBackupFilesDir, "auto")

    override suspend fun read(ownerUserId: String): AutoSnapshot? = withContext(Dispatchers.IO) {
        val file = file(ownerUserId)
        if (!file.isFile) return@withContext null
        runCatching { json.decodeFromString<AutoSnapshot>(file.readText()) }.getOrNull()
    }

    override suspend fun save(ownerUserId: String, snapshot: AutoSnapshot) = withContext(Dispatchers.IO) {
        val bounded = snapshot.copy(maintenance = snapshot.maintenance.take(MAX_MAINTENANCE))
        val destination = file(ownerUserId)
        destination.parentFile?.mkdirs()
        val temporary = File(destination.parentFile, "${destination.name}.tmp")
        val encoded = json.encodeToString(bounded)
        temporary.writeText(encoded)
        if (!temporary.renameTo(destination)) {
            destination.writeText(encoded)
            temporary.delete()
        }
    }

    private fun file(ownerUserId: String): File = File(directory, "${ownerUserId.sha256()}.json")
    private fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(toByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        private const val MAX_MAINTENANCE = 5_000
    }
}
