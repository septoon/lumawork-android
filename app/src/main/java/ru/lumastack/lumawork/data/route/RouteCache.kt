package ru.lumastack.lumawork.data.route

import android.content.Context
import java.security.MessageDigest
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import ru.lumastack.lumawork.domain.route.RouteWorkType
import ru.lumastack.lumawork.domain.route.RouteDayRecord

interface RouteCache {
    suspend fun read(userId: String, date: String, workType: RouteWorkType = RouteWorkType.Pos): RouteDayRecord?
    suspend fun readQueued(userId: String): List<RouteDayRecord> = emptyList()
    suspend fun readMonth(userId: String, monthKey: String): List<RouteDayRecord> = emptyList()
    suspend fun hasOdometerSetting(userId: String, monthKey: String): Boolean = readOdometer(userId, monthKey) != null
    suspend fun readOdometer(userId: String, monthKey: String): Int? = readMonth(userId, monthKey).firstNotNullOfOrNull { it.periodStartOdometer }
    suspend fun saveOdometer(userId: String, monthKey: String, value: Int?) {}
    suspend fun save(userId: String, record: RouteDayRecord)
}

class SharedPreferencesRouteCache(
    context: Context,
    private val json: Json,
) : RouteCache {
    private val preferences = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    override suspend fun read(userId: String, date: String, workType: RouteWorkType): RouteDayRecord? = withContext(Dispatchers.IO) {
        preferences.getString(key(userId, workType.storageKey(date)), null)?.let { raw ->
            runCatching { json.decodeFromString<RouteDayRecord>(raw) }.getOrNull()
        }
    }

    override suspend fun readQueued(userId: String): List<RouteDayRecord> = withContext(Dispatchers.IO) {
        val prefix = "day_${userId.sha256()}_"
        preferences.all.asSequence().filter { (key, _) -> key.startsWith(prefix) }
            .mapNotNull { (_, value) -> (value as? String)?.let { raw -> runCatching { json.decodeFromString<RouteDayRecord>(raw) }.getOrNull() } }
            .filter { it.queuedForSend }.toList()
    }

    override suspend fun readMonth(userId: String, monthKey: String): List<RouteDayRecord> = withContext(Dispatchers.IO) {
        val userHash = userId.sha256()
        preferences.all.asSequence().filter { (key, _) -> key.startsWith("day_${userHash}_${monthKey}-") }
            .mapNotNull { (_, value) -> (value as? String)?.let { raw -> runCatching { json.decodeFromString<RouteDayRecord>(raw) }.getOrNull() } }
            .toList()
    }

    override suspend fun hasOdometerSetting(userId: String, monthKey: String): Boolean = withContext(Dispatchers.IO) {
        preferences.contains("odometer_${userId.sha256()}_$monthKey") || readMonth(userId, monthKey).any { it.periodStartOdometer != null }
    }

    override suspend fun readOdometer(userId: String, monthKey: String): Int? = withContext(Dispatchers.IO) {
        val name = "odometer_${userId.sha256()}_$monthKey"
        if (preferences.contains(name)) preferences.getInt(name, -1).takeIf { it >= 0 }
        else readMonth(userId, monthKey).firstNotNullOfOrNull { it.periodStartOdometer }
    }

    override suspend fun saveOdometer(userId: String, monthKey: String, value: Int?) = withContext(Dispatchers.IO) {
        preferences.edit { putInt("odometer_${userId.sha256()}_$monthKey", value ?: -1) }
    }

    override suspend fun save(userId: String, record: RouteDayRecord) = withContext(Dispatchers.IO) {
        preferences.edit {
            putString(key(userId, record.workType.storageKey(record.date)), json.encodeToString(record))
        }
    }

    private fun key(userId: String, date: String): String = "day_${userId.sha256()}_$date"

    private fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(toByteArray())
        .joinToString("") { "%02x".format(it) }

    companion object {
        private const val FILE_NAME = "route_cache"
    }
}
