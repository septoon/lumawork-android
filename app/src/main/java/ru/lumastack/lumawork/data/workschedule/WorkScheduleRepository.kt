package ru.lumastack.lumawork.data.workschedule

import android.content.Context
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import ru.lumastack.lumawork.data.simpleone.SimpleOneApi
import ru.lumastack.lumawork.data.simpleone.SimpleOneFailure
import ru.lumastack.lumawork.data.simpleone.SimpleOneSessionStore
import ru.lumastack.lumawork.domain.workschedule.*

interface WorkScheduleCache {
    suspend fun read(ownerUserId: String): WorkScheduleSnapshot?
    suspend fun save(ownerUserId: String, snapshot: WorkScheduleSnapshot)
}

class FileWorkScheduleCache(context: Context, private val json: Json) : WorkScheduleCache {
    private val directory = File(context.noBackupFilesDir, "work-schedule")
    private fun file(owner: String) = File(directory, MessageDigest.getInstance("SHA-256")
        .digest(owner.toByteArray()).joinToString("") { "%02x".format(it) } + ".json")
    override suspend fun read(ownerUserId: String): WorkScheduleSnapshot? = withContext(Dispatchers.IO) {
        val file = file(ownerUserId)
        if (!file.isFile) null else runCatching { json.decodeFromString<WorkScheduleSnapshot>(file.readText()) }.getOrNull()
    }
    override suspend fun save(ownerUserId: String, snapshot: WorkScheduleSnapshot): Unit = withContext(Dispatchers.IO) {
        directory.mkdirs()
        val destination = file(ownerUserId)
        val temporary = File(directory, "${destination.name}.tmp")
        temporary.writeText(json.encodeToString(snapshot))
        if (!temporary.renameTo(destination)) {
            destination.writeText(temporary.readText())
            temporary.delete()
        }
    }
}

class WorkScheduleRepository(
    private val api: SimpleOneApi,
    private val sessionStore: SimpleOneSessionStore,
    private val cache: WorkScheduleCache,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val cacheMutex = Mutex()
    suspend fun read(ownerUserId: String, simpleOneUserId: String): WorkScheduleSnapshot? = cacheMutex.withLock {
        cache.read(ownerUserId)?.takeIf { it.simpleOneUserId == simpleOneUserId }
    }
    suspend fun refreshJournals(ownerUserId: String, simpleOneUserId: String): WorkScheduleSnapshot = authorized(ownerUserId) { key ->
        val journals = api.workScheduleJournals(key)
        update(ownerUserId, simpleOneUserId) { it.copy(journals = journals) }
    }
    suspend fun loadSchedule(ownerUserId: String, simpleOneUserId: String, journal: WorkScheduleJournal, forceRefresh: Boolean): WorkScheduleSnapshot {
        val cached = read(ownerUserId, simpleOneUserId)
        if (cached?.schedulesByJournalId?.get(journal.sysId)?.canReuse(forceRefresh) == true) return cached
        return authorized(ownerUserId) { key ->
            val schedule = api.workSchedule(journal, key)
            update(ownerUserId, simpleOneUserId) { it.copy(schedulesByJournalId = it.schedulesByJournalId + (journal.sysId to schedule)) }
        }
    }
    private suspend fun update(owner: String, user: String, transform: (WorkScheduleSnapshot) -> WorkScheduleSnapshot): WorkScheduleSnapshot = cacheMutex.withLock {
        val previous = cache.read(owner)?.takeIf { it.simpleOneUserId == user } ?: WorkScheduleSnapshot(user)
        currentCoroutineContext().ensureActive()
        transform(previous).also { cache.save(owner, it) }
    }
    private suspend fun <T> authorized(owner: String, block: suspend (String) -> T): T {
        val key = withContext(ioDispatcher) { sessionStore.read(owner)?.authKey?.takeIf(String::isNotBlank) }
            ?: throw SimpleOneFailure.MissingCredentials
        return try { withContext(ioDispatcher) { block(key) } }
        catch (error: CancellationException) { throw error }
        catch (error: SimpleOneFailure.Unauthorized) {
            withContext(ioDispatcher) { if (sessionStore.read(owner)?.authKey == key) sessionStore.clear(owner) }
            throw error
        }
    }
}
