package ru.lumastack.lumawork.data.personal

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable data class PersonalEntry(val key: String, val value: String?)
@Serializable data class PendingPersonalEntry(val value: String?, val id: String = UUID.randomUUID().toString())
@Serializable data class PersonalState(
    val values: Map<String, String> = emptyMap(),
    val seed: Map<String, String> = emptyMap(),
    val pending: Map<String, PendingPersonalEntry> = emptyMap(),
)
interface PersonalStateCache {
    suspend fun read(owner: String): PersonalState?
    suspend fun save(owner: String, state: PersonalState)
}
fun interface PersonalStateApi {
    suspend fun sync(token: String, collection: String, seed: List<PersonalEntry>, changes: List<PersonalEntry>): List<PersonalEntry>
}

/** Local writes are durable before upload; only the exact sent edits are acknowledged. */
class PersonalStateRepository(
    private val collection: String,
    private val cache: PersonalStateCache,
    private val api: PersonalStateApi,
    private val token: (String) -> String?,
    private val scope: CoroutineScope,
) {
    private val mutex = Mutex()
    private val syncMutex = Mutex()
    private val revision = MutableStateFlow(0L)
    val changes = revision.asStateFlow()

    suspend fun values(owner: String, seed: Map<String, String> = emptyMap()): Map<String, String> = mutex.withLock {
        val state = cache.read(owner) ?: PersonalState(values = seed, seed = seed).also { cache.save(owner, it) }
        state.values
    }

    suspend fun set(owner: String, key: String, value: String?) {
        mutex.withLock {
            val current = cache.read(owner) ?: PersonalState()
            cache.save(owner, current.copy(values = current.values.toMutableMap().apply {
                if (value == null) remove(key) else put(key, value)
            }, pending = current.pending + (key to PendingPersonalEntry(value))))
            revision.value += 1
        }
        scope.launch { synchronize(owner) }
    }

    suspend fun synchronize(owner: String) = syncMutex.withLock {
        try {
            val auth = token(owner) ?: return@withLock
            val sent = mutex.withLock { cache.read(owner) } ?: return@withLock
            val response = api.sync(auth, collection, sent.seed.map { PersonalEntry(it.key, it.value) },
                sent.pending.map { PersonalEntry(it.key, it.value.value) })
            currentCoroutineContext().ensureActive()
            if (token(owner) != auth) return@withLock
            mutex.withLock {
                val current = cache.read(owner) ?: return@withLock
                val pending = current.pending.filter { (key, value) -> sent.pending[key]?.id != value.id }
                val merged = response.mapNotNull { item -> item.value?.let { item.key to it } }.toMap().toMutableMap()
                pending.forEach { (key, entry) -> if (entry.value == null) merged.remove(key) else merged[key] = entry.value }
                cache.save(owner, PersonalState(values = merged, pending = pending))
                revision.value += 1
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* Keep seed and pending edits for the next foreground retry. */ }
    }
}
