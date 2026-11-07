package ru.lumastack.lumawork.data.analytics

import java.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.lumastack.lumawork.domain.analytics.*

data class LeaderboardState(
    val filter: LeaderboardFilter = LeaderboardFilter(), val response: LeaderboardResponse? = null,
    val isLoading: Boolean = false, val isPaging: Boolean = false, val isSaved: Boolean = false,
    val savedAt: String? = null, val error: String? = null, val cities: List<LeaderboardCity> = listOf(LeaderboardCity.All),
    val citySelectionRequired: Boolean = false,
)

class LeaderboardRepository(
    private val api: LeaderboardApi, private val cache: FileLeaderboardCache,
    val access: LeaderboardAccessRepository, private val identity: () -> StatisticsIdentity?,
    private val scope: CoroutineScope, private val now: () -> Instant = Instant::now,
    private val onAuthFailure: (StatisticsFailure) -> Unit = {},
) {
    private val mutableState = MutableStateFlow(LeaderboardState())
    val state = mutableState.asStateFlow()
    private val generation = java.util.concurrent.atomic.AtomicLong(0)
    private var requestJob: Job? = null
    private var pageJob: Job? = null
    private val mutex = Mutex()
    private var loadedIdentity: StatisticsIdentity? = null

    fun select(filter: LeaderboardFilter): Job = refresh(filter)
    fun refresh(filter: LeaderboardFilter = state.value.filter): Job {
        requestJob?.cancel(); pageJob?.cancel()
        val revision = generation.incrementAndGet()
        val captured = identity()
        val retained = state.value.response?.takeIf { state.value.filter == filter && loadedIdentity == captured && it.periodKey == filter.period.key(now()) }
        mutableState.value = state.value.copy(filter = filter, response = retained, isLoading = true, isPaging = false, error = null, citySelectionRequired = false)
        return scope.launch {
            if (captured == null || !canUse(captured, revision)) { if (generation.get() == revision) mutableState.update { it.copy(isLoading = false) }; return@launch }
            try {
                mutex.withLock {
                    val saved = if (retained == null) cache.read(captured, filter, now()) else null
                    if (!canUse(captured, revision)) return@launch
                    loadedIdentity = captured
                    if (saved != null) mutableState.update { it.copy(response = saved.response, isSaved = true, savedAt = saved.savedAt, cities = saved.response.availableCities) }
                }
                val response = api.leaderboard(captured.token, filter, 0)
                response.validate(filter)
                mutex.withLock {
                    if (!canUse(captured, revision)) return@launch
                    // Persist only a coherent first page; live pagination is runtime state.
                    cache.write(captured, filter, response, now())
                    if (!canUse(captured, revision)) return@launch
                    mutableState.update { it.copy(response = response, isLoading = false, isSaved = false, savedAt = now().toString(), cities = response.availableCities, error = null) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { failure(error, captured, revision, paging = false) }
        }.also { requestJob = it }
    }
    fun loadNext(): Job {
        val current = state.value
        val offset = current.response?.participantsOnly()?.nextOffset
        if (offset == null || current.isLoading || current.isPaging || current.citySelectionRequired) return scope.launch { }
        val captured = identity() ?: return scope.launch { }
        val revision = generation.get()
        mutableState.update { it.copy(isPaging = true, error = null) }
        return scope.launch {
            try {
                val page = api.leaderboard(captured.token, current.filter, offset)
                page.validate(current.filter, offset)
                mutex.withLock {
                    if (!canUse(captured, revision)) return@launch
                    val response = state.value.response ?: return@launch
                    if (response.periodKey != page.periodKey) { refresh(); return@launch }
                    mutableState.update { it.copy(response = page.copy(items = (response.items + page.items).distinctBy(LeaderboardEntry::userId)), isPaging = false, isSaved = false) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { failure(error, captured, revision, paging = true) }
        }.also { pageJob = it }
    }
    suspend fun clear() {
        generation.incrementAndGet(); requestJob?.cancel(); pageJob?.cancel()
        mutableState.value = LeaderboardState(); loadedIdentity = null
        withContext(NonCancellable) { mutex.withLock { cache.clear() } }
    }
    private fun canUse(captured: StatisticsIdentity, revision: Long) = identity() == captured && generation.get() == revision && access.allows(captured.owner)
    private suspend fun failure(error: Exception, captured: StatisticsIdentity, revision: Long, paging: Boolean) {
        if (!canUse(captured, revision)) return
        if (error is StatisticsFailure && error.code == "LEADERBOARD_DISABLED") { access.disable(); clear(); return }
        if (error is StatisticsFailure && (error.status == 401 || error.code == "USER_BLOCKED")) onAuthFailure(error)
        mutableState.update { it.copy(isLoading = false, isPaging = false,
            isSaved = it.response != null, error = error.message ?: "Не удалось загрузить рейтинг",
            cities = if (error is StatisticsFailure && error.code == "CITY_NOT_AVAILABLE" && error.availableCities.isNotEmpty()) error.availableCities else it.cities,
            citySelectionRequired = error is StatisticsFailure && error.code == "CITY_NOT_AVAILABLE") }
    }
}
