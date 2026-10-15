package ru.lumastack.lumawork.data.employees

import android.content.Context
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import ru.lumastack.lumawork.domain.employees.EmployeeDirectory
import ru.lumastack.lumawork.domain.employees.EmployeeSnapshot

interface EmployeeCache {
    suspend fun read(ownerUserId: String): EmployeeSnapshot?
    suspend fun save(ownerUserId: String, snapshot: EmployeeSnapshot)
}

class FileEmployeeCache(
    context: Context,
    private val json: Json,
) : EmployeeCache {
    private val directory = File(context.noBackupFilesDir, "employees")

    override suspend fun read(ownerUserId: String): EmployeeSnapshot? = withContext(Dispatchers.IO) {
        val file = file(ownerUserId)
        if (!file.isFile) return@withContext null
        runCatching { json.decodeFromString<EmployeeSnapshot>(file.readText()) }.getOrNull()
    }

    override suspend fun save(ownerUserId: String, snapshot: EmployeeSnapshot) = withContext(Dispatchers.IO) {
        val bounded = snapshot.copy(
            savedAddresses = snapshot.savedAddresses.takeLast(MAX_SAVED_ADDRESSES),
            directoriesByAddressId = snapshot.directoriesByAddressId.entries
                .toList()
                .takeLast(MAX_SAVED_ADDRESSES)
                .associate { (id, directory) ->
                    id to EmployeeDirectory(
                        employees = directory.employees.take(MAX_EMPLOYEES_PER_ADDRESS),
                        totalCount = directory.totalCount,
                        updatedAtEpochMillis = directory.updatedAtEpochMillis,
                    )
                },
            detailCache = snapshot.detailCache.entries.toList().takeLast(MAX_DETAILS).associate { it.toPair() },
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

    private fun file(ownerUserId: String): File = File(directory, "${ownerUserId.sha256()}.json")

    private fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(toByteArray())
        .joinToString("") { "%02x".format(it) }

    companion object {
        private const val MAX_SAVED_ADDRESSES = 12
        private const val MAX_EMPLOYEES_PER_ADDRESS = 1_000
        private const val MAX_DETAILS = 100
    }
}
