package ru.lumastack.lumawork.data.simpleone

import android.content.Context
import androidx.core.content.edit
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import ru.lumastack.lumawork.data.session.EncryptedValueStore

@Serializable
data class StoredSimpleOneSession(
    val ownerUserId: String,
    val authKey: String,
    val username: String,
)

interface SimpleOneSessionStore {
    fun read(ownerUserId: String): StoredSimpleOneSession?
    fun save(session: StoredSimpleOneSession)
    fun clear(ownerUserId: String)
    fun readUsername(ownerUserId: String): String
    fun saveUsername(ownerUserId: String, username: String)
}

class EncryptedSimpleOneSessionStore(
    context: Context,
    private val json: Json,
) : SimpleOneSessionStore {
    private val encryptedStore = EncryptedValueStore(
        context = context,
        preferencesName = SECURE_PREFERENCES,
        valueKey = SESSION_KEY,
        keyAlias = KEY_ALIAS,
    )
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    override fun read(ownerUserId: String): StoredSimpleOneSession? = encryptedStore.read()?.let { raw ->
        runCatching { json.decodeFromString<StoredSimpleOneSession>(raw) }
            .getOrNull()
            ?.takeIf { it.ownerUserId == ownerUserId && it.authKey.isNotBlank() }
    }

    override fun save(session: StoredSimpleOneSession) {
        encryptedStore.save(json.encodeToString(session))
        saveUsername(session.ownerUserId, session.username)
    }

    override fun clear(ownerUserId: String) {
        if (read(ownerUserId) != null) encryptedStore.clear()
    }

    override fun readUsername(ownerUserId: String): String =
        preferences.getString(usernameKey(ownerUserId), "").orEmpty()

    override fun saveUsername(ownerUserId: String, username: String) {
        preferences.edit { putString(usernameKey(ownerUserId), username.trim()) }
    }

    private fun usernameKey(ownerUserId: String): String = "username_${ownerUserId.sha256()}"

    private fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(toByteArray())
        .joinToString("") { "%02x".format(it) }

    companion object {
        private const val SECURE_PREFERENCES = "LumaWork.secure.simpleone"
        private const val PREFERENCES = "LumaWork.simpleone"
        private const val SESSION_KEY = "auth"
        private const val KEY_ALIAS = "LumaWork.SimpleOneSession"
    }
}
