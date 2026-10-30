package ru.lumastack.lumawork.data.coordination

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.lumastack.lumawork.data.simpleone.SimpleOneApi
import ru.lumastack.lumawork.data.simpleone.SimpleOneFailure
import ru.lumastack.lumawork.data.simpleone.SimpleOneSessionStore
import ru.lumastack.lumawork.domain.coordination.CoordinationRegion
import ru.lumastack.lumawork.domain.coordination.groupClosedRequestTypes
import ru.lumastack.lumawork.domain.coordination.prepareGroupClosedRequests
import ru.lumastack.lumawork.domain.requests.ClosedRequestsItem
import ru.lumastack.lumawork.domain.simpleone.SimpleOneRequest

/** App-owned progress survives navigation and is checkpointed after every completed page. */
data class GroupClosedRequestsState(
    val ownerUserId: String = "",
    val simpleOneUserId: String = "",
    val region: CoordinationRegion? = null,
    val records: List<SimpleOneRequest> = emptyList(),
    val preparedItems: List<ClosedRequestsItem> = emptyList(),
    val availableTypes: List<Pair<String, String>> = emptyList(),
    val isLoading: Boolean = false,
    val didLoadCache: Boolean = false,
    val loadedCount: Int = 0,
    val totalCount: Int? = null,
    val updatedAtEpochMillis: Long? = null,
    val errorMessage: String? = null,
    val requiresAuthorization: Boolean = false,
    val revision: Long = 0,
    val completedRevision: Long = 0,
)

class CoordinationGroupClosedRequestsRepository(
    private val api: SimpleOneApi,
    private val sessionStore: SimpleOneSessionStore,
    private val cache: CoordinationGroupClosedRequestsCache,
    private val applicationScope: CoroutineScope,
    private val preparationDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) {
    private data class Identity(val owner: String, val user: String, val authKey: String, val region: CoordinationRegion) {
        val legacyCacheKey: String = groupClosedDigest("${owner.length}:$owner${user.length}:$user")
        val cacheKey: String = groupClosedDigest("${owner.length}:$owner${user.length}:$user|city:${region.companyLocationId}")
    }

    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(GroupClosedRequestsState())
    val state: StateFlow<GroupClosedRequestsState> = mutableState.asStateFlow()
    private var identity: Identity? = null
    private var snapshot = GroupClosedRequestsSnapshot()
    private var refreshJob: Job? = null
    private var generation = 0L
    private var networkAllowed = false
    private var pendingForceFull = false

    /** With refreshOnActivate=false, restores cache and validates ongoing work without starting a network pass. */
    suspend fun activate(
        ownerUserId: String,
        simpleOneUserId: String,
        authorized: Boolean,
        region: CoordinationRegion?,
        startOffline: Boolean = false,
        refreshOnActivate: Boolean = true,
    ) = mutex.withLock {
        val authKey = if (authorized) sessionStore.read(ownerUserId)?.authKey else null
        val next = if (!authKey.isNullOrBlank() && simpleOneUserId.isNotBlank() && region != null) {
            Identity(ownerUserId, simpleOneUserId, authKey, region)
        } else null
        if (identity != next) {
            val sameAccount = next != null && identity?.owner == next.owner && identity?.user == next.user && identity?.region == next.region
            generation += 1
            pendingForceFull = false
            refreshJob?.cancel()
            refreshJob = null
            identity = next
            if (!sameAccount) {
                snapshot = GroupClosedRequestsSnapshot()
                mutableState.value = GroupClosedRequestsState(
                    ownerUserId = next?.owner.orEmpty(), simpleOneUserId = next?.user.orEmpty(), region = next?.region,
                    revision = mutableState.value.revision + 1,
                )
            } else {
                mutableState.value = mutableState.value.copy(isLoading = false, errorMessage = null, requiresAuthorization = false)
            }
            if (next != null) {
                // Finish initial disk hydration even if the initiating screen leaves. The network pass
                // is then launched in the application scope, never in the screen scope.
                val loaded = withContext(NonCancellable) {
                    // The legacy namespace was hardcoded to Alushta. Never restore it into another city.
                    val cached = cache.read(next.cacheKey)
                        ?: next.takeIf { it.region == CoordinationRegion.Alushta }?.let { cache.read(it.legacyCacheKey) }
                        ?: snapshot
                    publish(cached, next, generation, didLoadCache = true)
                }
                if (!loaded) return@withLock
            }
        }
        mutableState.value = mutableState.value.copy(requiresAuthorization = false)
        networkAllowed = next != null && !startOffline
        if (!networkAllowed) {
            generation += 1
            pendingForceFull = false
            refreshJob?.cancel()
            refreshJob = null
            mutableState.value = mutableState.value.copy(isLoading = false)
        } else if (refreshOnActivate && next != null) startRefreshLocked(next, forceFull = false)
    }

    suspend fun deactivate() = mutex.withLock {
        generation += 1
        pendingForceFull = false
        refreshJob?.cancel()
        refreshJob = null
        identity = null
        networkAllowed = false
        snapshot = GroupClosedRequestsSnapshot()
        mutableState.value = GroupClosedRequestsState(revision = mutableState.value.revision + 1)
    }

    suspend fun refresh(forceFull: Boolean = false) = mutex.withLock {
        val current = identity ?: return@withLock
        if (networkAllowed && isCurrent(current, generation)) startRefreshLocked(current, forceFull)
    }

    /** Joining from a screen never transfers ownership of the refresh task to that screen. */
    suspend fun awaitRefresh() {
        while (true) {
            val job = mutex.withLock { refreshJob } ?: return
            job.join()
            if (mutex.withLock { refreshJob === job }) return
        }
    }

    private fun startRefreshLocked(current: Identity, forceFull: Boolean) {
        if (refreshJob?.isActive == true) {
            pendingForceFull = pendingForceFull || forceFull
            return
        }
        val requestGeneration = generation
        if (forceFull || snapshot.nextPage == null) {
            snapshot = snapshot.copy(
                refreshedIDs = emptySet(),
                nextPage = 1,
                isIncremental = !forceFull && snapshot.updatedAtEpochMillis != null,
            )
        }
        mutableState.value = mutableState.value.copy(isLoading = true, errorMessage = null, requiresAuthorization = false, loadedCount = snapshot.refreshedIDs.size)
        val job = applicationScope.launch(start = CoroutineStart.LAZY) {
            load(current, requestGeneration)
        }
        refreshJob = job
        job.start()
    }

    private suspend fun load(current: Identity, requestGeneration: Long) {
        try {
            while (true) {
                val pass = mutex.withLock {
                    if (!isCurrent(current, requestGeneration)) return
                    snapshot
                }
                val page = pass.nextPage ?: break
                val result = api.groupClosedRequestsPage(current.region, page, current.authKey, PAGE_SIZE, pass.isIncremental)
                mutex.withLock {
                    if (!isCurrent(current, requestGeneration)) return
                    val fresh = result.records.distinctBy(SimpleOneRequest::id)
                    val existing = pass.records.associateBy(SimpleOneRequest::id)
                    val incoming = fresh.associateBy(SimpleOneRequest::id)
                    val newIDs = fresh.map(SimpleOneRequest::id).filter { it !in pass.refreshedIDs }
                    val seen = pass.refreshedIDs + newIDs
                    val merged = pass.records.map { incoming[it.id] ?: it } + fresh.filter { it.id !in existing }
                    val reachedCache = pass.isIncremental && fresh.isNotEmpty() &&
                        result.records.all { existing[it.id] == it } &&
                        result.totalCount != null && result.totalCount == merged.size
                    if (result.hasMore && newIDs.isEmpty() && !reachedCache) throw SimpleOneFailure.InvalidResponse
                    val hasMore = result.hasMore && !reachedCache
                    val records = if (hasMore || reachedCache) merged else merged.filter { it.id in seen }
                    val completed = !hasMore
                    val checkpoint = pass.copy(
                        records = records,
                        refreshedIDs = if (completed) emptySet() else seen,
                        nextPage = if (hasMore) page + 1 else null,
                        totalCount = result.totalCount ?: pass.totalCount,
                        updatedAtEpochMillis = if (completed) nowEpochMillis() else pass.updatedAtEpochMillis,
                    )
                    cache.save(current.cacheKey, checkpoint)
                    if (!isCurrent(current, requestGeneration)) return
                    if (!publish(checkpoint, current, requestGeneration, loadedCount = seen.size, completed = completed)) return
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            mutex.withLock {
                if (isCurrent(current, requestGeneration)) {
                    // The archive never clears credentials. In particular, an old 401 cannot erase a newer login.
                    mutableState.value = mutableState.value.copy(
                        errorMessage = error.message ?: "Не удалось загрузить заявки группы.",
                        requiresAuthorization = error is SimpleOneFailure.Unauthorized,
                    )
                }
            }
        } finally {
            mutex.withLock {
                if (identity == current && generation == requestGeneration) {
                    refreshJob = null
                    mutableState.value = mutableState.value.copy(isLoading = false)
                    if (pendingForceFull && networkAllowed && isCurrent(current, requestGeneration)) {
                        pendingForceFull = false
                        startRefreshLocked(current, forceFull = true)
                    }
                }
            }
        }
    }

    private fun isCurrent(current: Identity, requestGeneration: Long): Boolean =
        identity == current && generation == requestGeneration &&
            sessionStore.read(current.owner)?.authKey == current.authKey

    private suspend fun publish(
        value: GroupClosedRequestsSnapshot,
        current: Identity,
        requestGeneration: Long,
        didLoadCache: Boolean = mutableState.value.didLoadCache,
        loadedCount: Int = if (value.nextPage == null) value.records.size else value.refreshedIDs.size,
        completed: Boolean = false,
    ): Boolean {
        val previous = mutableState.value
        val changed = previous.records != value.records
        val index = if (changed) withContext(preparationDispatcher) {
            prepareGroupClosedRequests(value.records) to groupClosedRequestTypes(value.records)
        } else previous.preparedItems to previous.availableTypes
        if (!isCurrent(current, requestGeneration)) return false
        snapshot = value
        mutableState.value = previous.copy(
            records = if (changed) value.records else previous.records,
            preparedItems = index.first,
            availableTypes = index.second,
            didLoadCache = didLoadCache,
            loadedCount = loadedCount,
            totalCount = value.totalCount,
            updatedAtEpochMillis = value.updatedAtEpochMillis,
            revision = previous.revision + if (changed) 1 else 0,
            completedRevision = previous.completedRevision + if (completed) 1 else 0,
        )
        return true
    }

    companion object {
        private const val PAGE_SIZE = 100
    }
}
