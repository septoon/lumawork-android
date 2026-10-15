package ru.lumastack.lumawork.data.route

import android.content.Context
import java.security.MessageDigest
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import ru.lumastack.lumawork.domain.route.RouteDayRecord

interface RouteCache {
    suspend fun read(userId: String, date: String): RouteDayRecord?
    suspend fun readMonth(userId: String, monthKey: String): List<RouteDayRecord> = emptyList()
    suspend fun save(userId: String, record: RouteDayRecord)
}

class SharedPreferencesRouteCache(
    context: Context,
    private val json: Json,
) : RouteCache {
    private val preferences = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    override suspend fun read(userId: String, date: String): RouteDayRecord? = withContext(Dispatchers.IO) {
        preferences.getString(key(userId, date), null)?.let { raw ->
            runCatching { json.decodeFromString<RouteDayRecord>(raw) }.getOrNull()
        }
    }

    override suspend fun readMonth(userId: String, monthKey: String): List<RouteDayRecord> = withContext(Dispatchers.IO) {
        val userHash = userId.sha256()
        val prefixes = listOf("mileage_${userHash}_${monthKey}", "day_${userHash}_${monthKey}")
        preferences.all.asSequence().filter { (key, _) -> prefixes.any(key::startsWith) }
            .mapNotNull { (_, value) -> (value as? String)?.let { raw -> runCatching { json.decodeFromString<RouteDayRecord>(raw) }.getOrNull() } }
            .groupBy(RouteDayRecord::date)
            .mapNotNull { (_, records) -> records.firstOrNull { it.distanceKm != null } }
            .toList()
    }

    override suspend fun save(userId: String, record: RouteDayRecord) = withContext(Dispatchers.IO) {
        preferences.edit {
            putString(key(userId, record.date), json.encodeToString(record))
            if (record.distanceKm != null) {
                putString(mileageKey(userId, record.date), json.encodeToString(record))
            }
        }
    }

    private fun key(userId: String, date: String): String = "day_${userId.sha256()}_$date"
    private fun mileageKey(userId: String, date: String): String = "mileage_${userId.sha256()}_$date"

    private fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(toByteArray())
        .joinToString("") { "%02x".format(it) }

    companion object {
        private const val FILE_NAME = "route_cache"
    }
}
