package ru.lumastack.lumawork.data.backpack

import android.content.Context
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import ru.lumastack.lumawork.domain.backpack.BackpackSnapshot

interface BackpackCache {
    suspend fun read(ownerUserId: String): BackpackSnapshot?
    suspend fun save(ownerUserId: String, snapshot: BackpackSnapshot)
}

class FileBackpackCache(
    context: Context,
    private val json: Json,
) : BackpackCache {
    private val directory = File(context.noBackupFilesDir, "backpack")

    override suspend fun read(ownerUserId: String): BackpackSnapshot? = withContext(Dispatchers.IO) {
        val source = file(ownerUserId)
        if (!source.isFile) return@withContext null
        runCatching { json.decodeFromString<BackpackSnapshot>(source.readText()) }.getOrNull()
    }

    override suspend fun save(ownerUserId: String, snapshot: BackpackSnapshot) = withContext(Dispatchers.IO) {
        val destination = file(ownerUserId)
        destination.parentFile?.mkdirs()
        val temporary = File(destination.parentFile, "${destination.name}.tmp")
        val encoded = json.encodeToString(snapshot.copy(items = snapshot.items.take(MAX_ITEMS)))
        temporary.writeText(encoded)
        if (!temporary.renameTo(destination)) {
            destination.writeText(encoded)
            temporary.delete()
        }
    }

    private fun file(ownerUserId: String): File = File(directory, "${ownerUserId.sha256()}.json")

    private fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(toByteArray())
        .joinToString("") { "%02x".format(it) }

    private companion object {
        const val MAX_ITEMS = 3_000
    }
}
