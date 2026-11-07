package ru.lumastack.lumawork.domain.analytics

import java.time.Instant
import java.time.YearMonth
import kotlinx.serialization.Serializable

@Serializable enum class LeaderboardPeriod(val title: String) {
    all("За всё время"), year("За текущий год"), month("За текущий месяц");
    fun key(at: Instant = Instant.now()): String {
        val date = at.atZone(workStatisticsZone)
        return when (this) { all -> "all"; year -> date.year.toString(); month -> YearMonth.from(date).toString() }
    }
}
@Serializable enum class LeaderboardCategory(val title: String) {
    all("Все"), portable("Переносные"), stationary("Стационарные"), integration("Интеграции"), fiscal("ККТ")
}
@Serializable data class LeaderboardCity(val key: String, val title: String) {
    companion object { val All = LeaderboardCity("all", "Все") }
}
@Serializable data class LeaderboardFilter(val period: LeaderboardPeriod = LeaderboardPeriod.all,
    val category: LeaderboardCategory = LeaderboardCategory.all, val city: String = "all")
@Serializable data class LeaderboardEntry(
    val userId: String, val displayName: String, val avatarUrl: String?, val rank: Int?, val value: Int?,
    val completedWithVisitCount: Int?, val categoryCounts: Map<String, Int>, val hasData: Boolean,
    val sourceSyncedAt: String?, val receivedAt: String?, val isCurrentUser: Boolean,
) {
    fun validate() {
        require(userId.isNotBlank() && displayName.isNotBlank())
        require(categoryCounts.keys == workCategories.toSet() && categoryCounts.values.all { it >= 0 })
        require(rank == null || rank > 0)
        require(if (hasData) value != null && value >= 0 && completedWithVisitCount != null && completedWithVisitCount >= 0 else value == null && rank == null && completedWithVisitCount == null)
        require((value ?: 0) > 0 || rank == null)
        sourceSyncedAt?.let(Instant::parse); receivedAt?.let(Instant::parse)
    }
}
@Serializable data class LeaderboardResponse(
    val period: LeaderboardPeriod, val category: LeaderboardCategory, val city: LeaderboardCity,
    val availableCities: List<LeaderboardCity>, val currentUserInScope: Boolean,
    val timezone: String, val serverNow: String, val periodKey: String, val periodStart: String?, val periodEndExclusive: String?,
    val generatedAt: String, val rulesVersion: Int, val registeredUsersCount: Int, val usersWithDataCount: Int,
    val totalCount: Int, val items: List<LeaderboardEntry>, val me: LeaderboardEntry?, val nextOffset: Int?,
) {
    /** Presentation projection, also applied to legacy cache responses. Keep wire counters and offsets intact. */
    fun participantsOnly(): LeaderboardResponse {
        val own = me?.takeIf { it.hasData }
        return copy(items = items.filter { it.hasData }, me = own, currentUserInScope = own != null,
            // The API sorts uploaded users (including real zeros) before users without a snapshot.
            nextOffset = nextOffset?.takeIf { it < usersWithDataCount })
    }

    fun validate(filter: LeaderboardFilter, offset: Int = 0) {
        require(period == filter.period && category == filter.category && city.key == filter.city && timezone == "Europe/Moscow" && rulesVersion == 1)
        require(periodKey == period.key(Instant.parse(serverNow)))
        Instant.parse(generatedAt)
        require(registeredUsersCount >= 0 && usersWithDataCount in 0..registeredUsersCount && totalCount == registeredUsersCount)
        require(availableCities.firstOrNull() == LeaderboardCity.All && availableCities.map { it.key }.distinct().size == availableCities.size)
        require(availableCities.all { it.key.isNotBlank() && it.title.isNotBlank() } && city in availableCities)
        require(items.map { it.userId }.distinct().size == items.size && items.size <= 100)
        require(currentUserInScope == (me != null) && (me == null || me.isCurrentUser))
        require(nextOffset == null || nextOffset > offset)
        items.forEach { it.validate() }; me?.validate()
    }
}
@Serializable data class LeaderboardCapability(val enabled: Boolean, val updatedAt: String?)
