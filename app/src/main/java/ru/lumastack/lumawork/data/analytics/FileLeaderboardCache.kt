package ru.lumastack.lumawork.data.analytics

import java.io.File
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import ru.lumastack.lumawork.domain.analytics.*

@Serializable data class SavedLeaderboard(val key: String, val savedAt: String, val response: LeaderboardResponse)
class FileLeaderboardCache(private val directory: File, private val json: Json) {
    private fun key(identity: StatisticsIdentity, filter: LeaderboardFilter, at: Instant): String = json.encodeToString(
        listOf(identity.owner, identity.fingerprint, filter.period.name, filter.period.key(at), filter.category.name, filter.city, "1", "1"))
    suspend fun read(identity: StatisticsIdentity, filter: LeaderboardFilter, now: Instant): SavedLeaderboard? = withContext(Dispatchers.IO) {
        val key = key(identity, filter, now)
        val file = File(directory, "${analyticsFileKey(key)}.json")
        if (!file.isFile || file.length() > 2 * 1024 * 1024) return@withContext null
        runCatching {
            json.decodeFromString<SavedLeaderboard>(file.readText()).also {
                require(it.key == key && it.response.periodKey == filter.period.key(now))
                Instant.parse(it.savedAt); it.response.validate(filter)
            }
        }.getOrNull()
    }
    suspend fun write(identity: StatisticsIdentity, filter: LeaderboardFilter, response: LeaderboardResponse, savedAt: Instant) {
        response.validate(filter)
        val responseInstant = Instant.parse(response.serverNow)
        val key = key(identity, filter, responseInstant)
        val text = withContext(Dispatchers.Default) { json.encodeToString(SavedLeaderboard(key, savedAt.toString(), response)) }
        withContext(Dispatchers.IO) { atomicAnalyticsWrite(File(directory, "${analyticsFileKey(key)}.json"), text) }
    }
    suspend fun clear() = withContext(Dispatchers.IO) { directory.listFiles()?.forEach { check(it.delete()) }; Unit }
}
