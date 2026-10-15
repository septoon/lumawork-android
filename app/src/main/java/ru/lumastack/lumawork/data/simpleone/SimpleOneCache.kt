package ru.lumastack.lumawork.data.simpleone

import android.content.Context
import androidx.core.content.edit
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import ru.lumastack.lumawork.domain.simpleone.SimpleOneSnapshot

interface SimpleOneCache {
    suspend fun read(ownerUserId: String): SimpleOneSnapshot?
    suspend fun save(ownerUserId: String, snapshot: SimpleOneSnapshot)
}

class SharedPreferencesSimpleOneCache(
    context: Context,
    private val json: Json,
) : SimpleOneCache {
    private val preferences = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    override suspend fun read(ownerUserId: String): SimpleOneSnapshot? = withContext(Dispatchers.IO) {
        preferences.getString(key(ownerUserId), null)?.let { raw ->
            runCatching { json.decodeFromString<SimpleOneSnapshot>(raw) }.getOrNull()
        }
    }

    override suspend fun save(ownerUserId: String, snapshot: SimpleOneSnapshot) = withContext(Dispatchers.IO) {
        val bounded = snapshot.copy(activeRequests = snapshot.activeRequests.take(MAX_CACHED_REQUESTS))
        preferences.edit { putString(key(ownerUserId), json.encodeToString(bounded)) }
    }

    private fun key(ownerUserId: String): String = "snapshot_${ownerUserId.sha256()}"

    private fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(toByteArray())
        .joinToString("") { "%02x".format(it) }

    companion object {
        private const val FILE_NAME = "simpleone_cache"
        private const val MAX_CACHED_REQUESTS = 250
    }
}
