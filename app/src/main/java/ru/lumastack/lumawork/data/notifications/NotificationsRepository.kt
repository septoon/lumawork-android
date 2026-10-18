package ru.lumastack.lumawork.data.notifications

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.lumastack.lumawork.domain.model.AppSession
import ru.lumastack.lumawork.domain.simpleone.SimpleOneSnapshot

class NotificationsRepository(
    private val api: NotificationsApi,
    private val cache: NotificationsCache,
    private val currentSession: () -> AppSession?,
) {
    private val stateMutex = Mutex()
    private val syncMutex = Mutex()
    private val processMutex = Mutex()
    private val stored = mutableMapOf<String, NotificationStorage>()
    private val flows = mutableMapOf<String, MutableStateFlow<NotificationSettingsState>>()

    @Synchronized
    fun settings(owner: String): StateFlow<NotificationSettingsState> = flow(owner)
    @Synchronized
    private fun flow(owner: String) = flows.getOrPut(owner) { MutableStateFlow(NotificationSettingsState()) }

    private fun requireCurrent(session: AppSession) {
        val current = currentSession()
        if (current?.token != session.token || current.user.id != session.user.id) throw CancellationException("Notification owner changed")
    }

    suspend fun loadLocal(session: AppSession) {
        requireCurrent(session)
        stateMutex.withLock { readLocked(session.user.id) }
    }

    /** Collect sequentially: a newer refresh must not cancel startup while preferences are in flight. */
    suspend fun observeSnapshots(session: AppSession, snapshots: Flow<SimpleOneSnapshot>, deliverLocal: suspend (NotificationEvent) -> Boolean) {
        var observed = false
        snapshots.distinctUntilChangedBy { it.sessionFingerprint to it.updatedAtEpochMillis }.collect { snapshot ->
            if (snapshot.updatedAtEpochMillis == null || snapshot.sessionFingerprint.isBlank()) return@collect
            try {
                if (!observed) {
                    process(session, snapshot, offline = false, seedOnly = true, deliverLocal = deliverLocal)
                    observed = true
                }
                synchronize(session)
                process(session, snapshot, offline = false, deliverLocal = deliverLocal)
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { /* A later full refresh retries persisted work. */ }
        }
    }

    private suspend fun readLocked(owner: String): NotificationStorage = stored[owner] ?: (cache.read(owner) ?: NotificationStorage()).also {
        stored[owner] = it
        flow(owner).update { state -> state.copy(preferences = it.preferences, ready = true) }
    }

    private suspend fun change(session: AppSession, transform: (NotificationStorage) -> NotificationStorage): NotificationStorage = stateMutex.withLock {
        requireCurrent(session)
        val owner = session.user.id
        val previous = readLocked(owner)
        val next = transform(previous)
        if (next != previous) { cache.save(owner, next); stored[owner] = next }
        flow(owner).update { it.copy(preferences = next.preferences, ready = true) }
        next
    }

    suspend fun setPreference(session: AppSession, key: NotificationPreference, value: Boolean, offline: Boolean) {
        if (offline && key.isEmail) return
        try {
            change(session) { it.copy(preferences = it.preferences.changing(key, value), dirty = it.dirty + key, revision = it.revision + 1) }
            flow(session.user.id).update { it.copy(error = null, notice = if (offline) "Настройки сохранены на устройстве." else null) }
            if (!offline) synchronize(session)
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) { flow(session.user.id).update { it.copy(error = "Не удалось сохранить настройки уведомлений.", saving = false) } }
    }

    /** Serializes GET/PUT and merges only unsynced fields, including edits made while GET was in flight. */
    suspend fun synchronize(session: AppSession) = syncMutex.withLock {
        requireCurrent(session)
        loadLocal(session)
        val owner = session.user.id
        flow(owner).update { it.copy(saving = true, error = null) }
        try {
            val remote = api.fetch(session.token)
            currentCoroutineContext().ensureActive()
            val pending = change(session) { local ->
                val merged = local.dirty.fold(remote) { preferences, key -> preferences.changing(key, local.preferences.enabled(key)) }
                local.copy(preferences = merged)
            }
            if (pending.dirty.isNotEmpty()) {
                requireCurrent(session)
                api.save(session.token, pending.preferences)
                currentCoroutineContext().ensureActive()
                change(session) { current ->
                    if (current.revision == pending.revision) current.copy(dirty = emptySet()) else current
                }
                flow(owner).update { it.copy(notice = "Настройки уведомлений сохранены.") }
            }
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) { flow(owner).update { it.copy(error = "Настройки сохранены на устройстве, но сервер их не принял.") } }
        finally { flow(owner).update { it.copy(saving = false) } }
    }

    /** Processes full refreshes only. The caller seeds the first restored foreground snapshot. */
    suspend fun process(
        session: AppSession,
        snapshot: SimpleOneSnapshot,
        offline: Boolean,
        seedOnly: Boolean = false,
        deliverLocal: suspend (NotificationEvent) -> Boolean,
    ) = processMutex.withLock {
        requireCurrent(session)
        val time = snapshot.updatedAtEpochMillis ?: return@withLock
        if (snapshot.sessionFingerprint.isBlank() || offline) return@withLock
        val previous = change(session) { it }
        val sameIdentity = previous.fingerprint == snapshot.sessionFingerprint
        if (sameIdentity && previous.snapshotTime != null && time <= previous.snapshotTime) {
            if (!seedOnly) retryPendingEmails(session)
            return@withLock
        }
        val events = if (seedOnly || !sameIdentity || previous.snapshotTime == null) emptyList()
            else notificationEvents(previous.baseline, snapshot.activeRequests)
        change(session) { state ->
            val pending = if (sameIdentity) state.pendingEmail else emptyList()
            state.copy(
                fingerprint = snapshot.sessionFingerprint, snapshotTime = time,
                baseline = snapshot.activeRequests.distinctBy { it.id }.map { NotificationBaseline(it.id, it.assignedUser.trim()) },
                pendingEmail = (pending + events.filter { state.preferences.emailEnabled(it) && it.canEmail && it.eventID !in state.deliveredEmail })
                    .distinctBy { it.eventID }.takeLast(200),
            )
        }
        // Foreground cache restoration commits its baseline without waiting for any network operation.
        if (seedOnly) return@withLock
        for (event in events) {
            requireCurrent(session)
            val state = change(session) { it }
            if (state.preferences.localEnabled(event) && event.eventID !in state.deliveredLocal) {
                val delivered = try { deliverLocal(event) }
                catch (error: CancellationException) { throw error }
                catch (_: Exception) { false }
                if (delivered) change(session) { it.copy(deliveredLocal = (it.deliveredLocal + event.eventID).distinct().takeLast(500)) }
            }
        }
        retryPendingEmails(session)
    }

    private suspend fun retryPendingEmails(session: AppSession) {
        val pending = change(session) { it }.pendingEmail
        for (event in pending) {
            requireCurrent(session)
            val state = change(session) { it }
            if (!state.preferences.emailEnabled(event) || !event.canEmail || event.eventID in state.deliveredEmail) {
                change(session) { it.copy(pendingEmail = it.pendingEmail.filterNot { pending -> pending.eventID == event.eventID }) }
                continue
            }
            try {
                api.send(session.token, event)
                change(session) { it.copy(
                    pendingEmail = it.pendingEmail.filterNot { pending -> pending.eventID == event.eventID },
                    deliveredEmail = (it.deliveredEmail + event.eventID).distinct().takeLast(500),
                ) }
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { /* Persisted queue retries after the next successful full refresh. */ }
        }
    }
}
