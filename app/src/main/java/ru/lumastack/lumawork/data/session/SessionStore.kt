package ru.lumastack.lumawork.data.session

import android.annotation.SuppressLint
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import androidx.core.content.edit
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import ru.lumastack.lumawork.domain.model.AppSession

interface SessionStore {
    fun read(): AppSession?
    fun save(session: AppSession)
    fun clear()
}

interface RememberedEmailStore {
    fun read(): String
    fun save(email: String)
}

class EncryptedSessionStore(
    context: Context,
    private val json: Json,
) : SessionStore {
    private val encryptedValueStore = EncryptedValueStore(
        context = context,
        preferencesName = PREFERENCES_NAME,
        valueKey = SESSION_KEY,
        keyAlias = KEY_ALIAS,
    )

    override fun read(): AppSession? {
        val value = encryptedValueStore.read() ?: return null
        return runCatching { json.decodeFromString<AppSession>(value) }
            .getOrElse {
                clear()
                null
            }
    }

    override fun save(session: AppSession) {
        encryptedValueStore.save(json.encodeToString(session))
    }

    override fun clear() {
        encryptedValueStore.clear()
    }

    companion object {
        private const val PREFERENCES_NAME = "LumaWork.secure.session"
        private const val SESSION_KEY = "auth"
        private const val KEY_ALIAS = "LumaWork.AppSession"
    }
}

internal class EncryptedValueStore(
    context: Context,
    preferencesName: String,
    private val valueKey: String,
    private val keyAlias: String,
) {
    private val preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)

    @Synchronized
    fun read(): String? {
        val value = preferences.getString(valueKey, null) ?: return null
        return runCatching {
            val parts = value.split(':', limit = 2)
            require(parts.size == 2)
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val encrypted = Base64.decode(parts[1], Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            }
            cipher.doFinal(encrypted).decodeToString()
        }.getOrElse {
            clear()
            null
        }
    }

    @Synchronized
    @SuppressLint("UseKtx")
    fun save(value: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, secretKey())
        }
        val encrypted = cipher.doFinal(value.encodeToByteArray())
        val stored = listOf(cipher.iv, encrypted)
            .joinToString(":") { Base64.encodeToString(it, Base64.NO_WRAP) }
        check(preferences.edit().putString(valueKey, stored).commit()) {
            "Не удалось сохранить защищённые данные."
        }
    }

    fun clear() {
        preferences.edit { remove(valueKey) }
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        (keyStore.getKey(keyAlias, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
    }
}

class SharedPreferencesEmailStore(context: Context) : RememberedEmailStore {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun read(): String = preferences.getString(EMAIL_KEY, "").orEmpty()

    override fun save(email: String) {
        preferences.edit { putString(EMAIL_KEY, email) }
    }

    companion object {
        private const val PREFERENCES_NAME = "LumaWork.preferences"
        private const val EMAIL_KEY = "auth.email"
    }
}
