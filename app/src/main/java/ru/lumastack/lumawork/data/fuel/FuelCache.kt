package ru.lumastack.lumawork.data.fuel

import android.content.Context
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import ru.lumastack.lumawork.domain.fuel.FuelSnapshot

interface FuelCache {
    suspend fun read(ownerUserId: String): FuelSnapshot?
    suspend fun save(ownerUserId: String, snapshot: FuelSnapshot)
}

class FileFuelCache(context: Context, private val json: Json) : FuelCache {
    private val directory = File(context.noBackupFilesDir, "fuel")

    override suspend fun read(ownerUserId: String): FuelSnapshot? = withContext(Dispatchers.IO) {
        val file = file(ownerUserId)
        if (!file.isFile) return@withContext null
        runCatching { json.decodeFromString<FuelSnapshot>(file.readText()) }.getOrNull()
    }

    override suspend fun save(ownerUserId: String, snapshot: FuelSnapshot) = withContext(Dispatchers.IO) {
        val destination = file(ownerUserId)
        destination.parentFile?.mkdirs()
        val temporary = File(destination.parentFile, "${destination.name}.tmp")
        val encoded = json.encodeToString(snapshot.copy(records = snapshot.records.take(MAX_RECORDS)))
        temporary.writeText(encoded)
        if (!temporary.renameTo(destination)) {
            destination.writeText(encoded)
            temporary.delete()
        }
    }

    private fun file(ownerUserId: String): File = File(directory, "${ownerUserId.sha256()}.json")
    private fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(toByteArray()).joinToString("") { "%02x".format(it) }

    companion object { private const val MAX_RECORDS = 10_000 }
}
