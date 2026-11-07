package ru.lumastack.lumawork.data.analytics

import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.lumastack.lumawork.domain.analytics.*

/** Runtime credentials only; no bearer or SO auth key is written to disk. */
data class StatisticsIdentity(val owner: String, val token: String, val fingerprint: String)
data class WorkStatisticsPass(
    val identity: StatisticsIdentity, val sourceUserId: String, val generation: Long,
    val previous: WorkSourceIndex?, val forceBaseline: Boolean, val expectedRevision: Int?,
)

class WorkStatisticsSyncCoordinator(
    private val api: WorkStatisticsApi, private val disk: WorkStatisticsSourceIndex,
    private val currentIdentity: () -> StatisticsIdentity?, private val scope: CoroutineScope,
    private val now: () -> Instant = Instant::now,
) {
    private val mutex = Mutex()
    private var active: WorkStatisticsPass? = null
    private var uploadJob: Job? = null
    private val mutableNotice = MutableStateFlow<String?>(null)
    val notice = mutableNotice.asStateFlow()

    suspend fun begin(owner: String, sourceUserId: String, fingerprint: String): WorkStatisticsPass {
        val identity = currentIdentity()?.takeIf { it.owner == owner && it.fingerprint == fingerprint }
            ?: throw CancellationException("Statistics session changed")
        return mutex.withLock {
            uploadJob?.cancel(); uploadJob = null
            check(active == null) { "Source pass already active" }
            val previous = disk.read(owner)
            checkIdentity(identity)
            val generation = previous.generation + 1
            // Before any archive commit. A crash after this write always forces a full source baseline.
            disk.write(owner, previous.copy(dirty = true, generation = generation, pending = null))
            checkIdentity(identity)
            val revision = try {
                api.me(identity.token).also {
                    require(it.rulesVersion == 1 && it.serverRevision >= 0 && it.sourceUserId == sourceUserId)
                }.serverRevision
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
            checkIdentity(identity)
            WorkStatisticsPass(identity, sourceUserId, generation, previous.index,
                previous.requiresBaseline(owner, sourceUserId, fingerprint, now().toEpochMilli()), revision).also { active = it }
        }
    }
    suspend fun commit(pass: WorkStatisticsPass, index: WorkSourceIndex, publishable: Boolean) = mutex.withLock {
        checkPass(pass)
        require(index.owner == pass.identity.owner && index.sourceUserId == pass.sourceUserId && index.sessionFingerprint == pass.identity.fingerprint)
        val payload = if (publishable && pass.expectedRevision != null) withContext(Dispatchers.Default) {
            val (months, unknown) = index.aggregate()
            WorkStatisticsUpload(requestId = UUID.randomUUID().toString(), expectedRevision = pass.expectedRevision,
                sourceUserId = pass.sourceUserId, sourceSyncedAt = index.sourceSyncedAt, months = months, unknownDate = unknown).also { it.validate() }
        } else null
        checkPass(pass)
        val state = disk.read(pass.identity.owner)
        checkPass(pass)
        if (state.generation != pass.generation) throw CancellationException("Statistics generation changed")
        disk.write(pass.identity.owner, WorkStatisticsDiskState(index, false, pass.generation,
            payload?.let { WorkStatisticsPending(pass.identity.owner, pass.identity.fingerprint, pass.generation, it) }))
        checkIdentity(pass.identity)
        mutableNotice.value = if (publishable) "Заявки обновлены. Статистика для рейтинга будет отправлена позже" else null
    }
    suspend fun end(pass: WorkStatisticsPass) = withContext(NonCancellable) {
        mutex.withLock { if (active === pass) active = null }
    }
    /** Invoked only after archiveMutex is released, and on foreground. */
    fun resumePending() {
        synchronized(this) {
            if (uploadJob?.isActive == true) return
            uploadJob = scope.launch {
                for (attempt in 0..3) {
                    if (attempt > 0) delay(1000L shl (2 * (attempt - 1)))
                    if (!sendPendingOnce()) break
                }
            }
        }
    }
    /** True means a transient failure can be retried. */
    suspend fun sendPendingOnce(): Boolean {
        val identity = currentIdentity() ?: return false
        val pending = mutex.withLock {
            if (active != null) return false
            val state = disk.read(identity.owner)
            checkIdentity(identity)
            state.pending?.takeIf { !state.dirty && it.owner == identity.owner && it.fingerprint == identity.fingerprint && it.generation == state.generation && state.index?.sourceUserId == it.payload.sourceUserId }
        } ?: return false
        try {
            checkIdentity(identity)
            api.upload(identity.token, pending.payload)
            mutex.withLock {
                checkIdentity(identity)
                val state = disk.read(identity.owner)
                checkIdentity(identity)
                if (state.pending == pending && active == null) {
                    disk.write(identity.owner, state.copy(pending = null))
                    mutableNotice.value = null
                }
            }
            return false
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            if (currentIdentity() != identity) return false
            val retry = error !is StatisticsFailure || error.status >= 500 || error.status == 429
            if (!retry) mutex.withLock {
                val state = disk.read(identity.owner)
                checkIdentity(identity)
                if (state.pending == pending) disk.write(identity.owner, state.copy(pending = null, dirty = true))
            }
            return retry
        }
    }
    fun stopRetries() { synchronized(this) { uploadJob?.cancel(); uploadJob = null } }
    suspend fun clear() = mutex.withLock {
        uploadJob?.cancel(); uploadJob = null; active = null
        disk.clear(); mutableNotice.value = null
    }
    private fun checkIdentity(identity: StatisticsIdentity) {
        if (currentIdentity() != identity) throw CancellationException("Statistics session changed")
    }
    private fun checkPass(pass: WorkStatisticsPass) {
        checkIdentity(pass.identity)
        if (active !== pass) throw CancellationException("Statistics pass changed")
    }
}
