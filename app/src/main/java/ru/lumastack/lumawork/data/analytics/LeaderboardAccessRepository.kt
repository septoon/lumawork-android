package ru.lumastack.lumawork.data.analytics

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import ru.lumastack.lumawork.domain.analytics.LeaderboardCapability

data class LeaderboardAccessState(val owner: String? = null, val enabled: Boolean? = null)

/** Unknown means the original analytics. Network failures retain only this session's confirmed value. */
class LeaderboardAccessRepository(
    private val api: LeaderboardApi,
    private val account: () -> Pair<String, String>?,
    private val onAuthFailure: (StatisticsFailure) -> Unit = {},
) {
    private val mutableState = MutableStateFlow(LeaderboardAccessState())
    val state = mutableState.asStateFlow()
    private val mutex = Mutex()
    private var epoch = 0L
    fun clear() { synchronized(this) { epoch++; mutableState.value = LeaderboardAccessState() } }
    fun disable() { synchronized(this) { epoch++; mutableState.value = LeaderboardAccessState(account()?.first, false) } }
    suspend fun refresh() {
        if (!mutex.tryLock()) return
        try {
            val captured = account() ?: return
            val revision = synchronized(this) {
                if (mutableState.value.owner != captured.first) { epoch++; mutableState.value = LeaderboardAccessState(captured.first) }
                epoch
            }
            val result: LeaderboardCapability = try { api.capability(captured.second) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (account() == captured && error is StatisticsFailure && (error.status == 401 || error.code == "USER_BLOCKED")) onAuthFailure(error)
                return
            }
            synchronized(this) {
                if (account() == captured && epoch == revision) mutableState.value = LeaderboardAccessState(captured.first, result.enabled)
            }
        } finally { mutex.unlock() }
    }
    fun allows(owner: String): Boolean = state.value.let { it.owner == owner && it.enabled == true && account()?.first == owner }
}
