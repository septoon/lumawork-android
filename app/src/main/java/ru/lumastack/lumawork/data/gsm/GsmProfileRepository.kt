package ru.lumastack.lumawork.data.gsm

import android.content.Context
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

interface GsmProfileCache {
    suspend fun read(owner: String): GsmProfileSnapshot?
    suspend fun save(owner: String, snapshot: GsmProfileSnapshot)
}

class FileGsmProfileCache(context: Context, private val json: Json) : GsmProfileCache {
    private val directory = File(context.noBackupFilesDir, "gsm-profile")
    override suspend fun read(owner: String): GsmProfileSnapshot? = withContext(Dispatchers.IO) {
        val source = file(owner)
        if (!source.isFile) null else runCatching { json.decodeFromString<GsmProfileSnapshot>(source.readText()) }.getOrNull()
    }

    override suspend fun save(owner: String, snapshot: GsmProfileSnapshot) = withContext(Dispatchers.IO) {
        val target = file(owner)
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, "${target.name}.tmp")
        temporary.writeText(json.encodeToString(snapshot))
        if (!temporary.renameTo(target)) { target.writeText(temporary.readText()); temporary.delete() }
    }

    private fun file(owner: String) = File(directory, MessageDigest.getInstance("SHA-256").digest(owner.toByteArray()).joinToString("") { "%02x".format(it) } + ".json")
}

class GsmProfileRepository(
    private val api: GsmProfileApi,
    private val cache: GsmProfileCache,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private val revisions = mutableMapOf<String, Long>()
    suspend fun read(owner: String): GsmProfileSnapshot? = cache.read(owner)?.let { it.copy(profile = it.profile.copy(employeePhone = GsmPhoneFormatter.format(it.profile.employeePhone))) }

    suspend fun refresh(owner: String, token: String): GsmProfileSnapshot {
        val revision = mutex.withLock { revisions[owner] ?: 0L }
        val loaded = api.fetch(token)
        val projects = try { api.projects(token) } catch (error: CancellationException) { throw error } catch (_: Exception) { null }
        return mutex.withLock {
            currentCoroutineContext().ensureActive()
            val previous = read(owner) ?: GsmProfileSnapshot()
            if ((revisions[owner] ?: 0L) != revision) previous
            else GsmProfileSnapshot(loaded.profile, projects ?: previous.projects, loaded.availableFuelTypes, now()).also { cache.save(owner, it) }
        }
    }

    suspend fun save(owner: String, token: String, profile: GsmProfile): GsmProfileSnapshot {
        val normalized = profile.normalizedForSave()
        mutex.withLock { revisions[owner] = (revisions[owner] ?: 0L) + 1L }
        val saved = api.save(token, normalized)
        return mutex.withLock {
            currentCoroutineContext().ensureActive()
            revisions[owner] = (revisions[owner] ?: 0L) + 1L
            val previous = read(owner) ?: GsmProfileSnapshot()
            previous.copy(profile = saved, updatedAtEpochMillis = now()).also { cache.save(owner, it) }
        }
    }
}
