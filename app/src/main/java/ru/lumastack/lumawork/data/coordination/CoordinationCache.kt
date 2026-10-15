package ru.lumastack.lumawork.data.coordination

import android.content.Context
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import ru.lumastack.lumawork.domain.coordination.CoordinationRequestGroup
import ru.lumastack.lumawork.domain.coordination.CoordinationSnapshot

interface CoordinationCache {
    suspend fun read(ownerUserId: String): CoordinationSnapshot?
    suspend fun save(ownerUserId: String, snapshot: CoordinationSnapshot)
}

class FileCoordinationCache(
    context: Context,
    private val json: Json,
) : CoordinationCache {
    private val directory = File(context.noBackupFilesDir, "coordination")

    override suspend fun read(ownerUserId: String): CoordinationSnapshot? = withContext(Dispatchers.IO) {
        val file = file(ownerUserId)
        if (!file.isFile) return@withContext null
        runCatching { json.decodeFromString<CoordinationSnapshot>(file.readText()) }.getOrNull()
    }

    override suspend fun save(ownerUserId: String, snapshot: CoordinationSnapshot) = withContext(Dispatchers.IO) {
        val bounded = snapshot.copy(
            regions = snapshot.regions.mapValues { (_, group) -> group.bounded() },
            returnEquipment = snapshot.returnEquipment?.bounded(),
        )
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

    private fun CoordinationRequestGroup.bounded(): CoordinationRequestGroup =
        copy(requests = requests.take(MAX_REQUESTS_PER_GROUP))

    private fun file(ownerUserId: String): File = File(directory, "${ownerUserId.sha256()}.json")

    private fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(toByteArray())
        .joinToString("") { "%02x".format(it) }

    companion object {
        private const val MAX_REQUESTS_PER_GROUP = 3_000
    }
}
