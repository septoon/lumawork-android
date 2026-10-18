package ru.lumastack.lumawork.data.notifications

import android.content.Context
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

interface NotificationsCache {
    suspend fun read(owner: String): NotificationStorage?
    suspend fun save(owner: String, value: NotificationStorage)
}

class SharedPreferencesNotificationsCache(context: Context) : NotificationsCache {
    private val preferences = context.getSharedPreferences("request_notifications", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    override suspend fun read(owner: String): NotificationStorage? = withContext(Dispatchers.IO) {
        preferences.getString(key(owner), null)?.let { runCatching { json.decodeFromString<NotificationStorage>(it) }.getOrNull() }
    }
    override suspend fun save(owner: String, value: NotificationStorage): Unit = withContext(Dispatchers.IO) {
        if (!preferences.edit().putString(key(owner), json.encodeToString(value)).commit()) {
            throw NotificationsFailure("Не удалось сохранить настройки уведомлений на устройстве.")
        }
    }
    private fun key(owner: String) = MessageDigest.getInstance("SHA-256").digest(owner.toByteArray()).joinToString("") { "%02x".format(it) }
}
