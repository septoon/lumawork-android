package ru.lumastack.lumawork.data.personal

import android.content.Context
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class FilePersonalStateCache(context: Context, private val json: Json, collection: String) : PersonalStateCache {
    private val directory = File(context.noBackupFilesDir, "personal-state/$collection")
    private fun file(owner: String) = File(directory, MessageDigest.getInstance("SHA-256").digest(owner.toByteArray()).joinToString("") { "%02x".format(it) } + ".json")
    override suspend fun read(owner: String): PersonalState? = withContext(Dispatchers.IO) {
        val source = file(owner)
        if (!source.isFile) null else json.decodeFromString<PersonalState>(source.readText())
    }
    override suspend fun save(owner: String, state: PersonalState) = withContext(Dispatchers.IO) {
        val target = file(owner); directory.mkdirs()
        val temporary = File(directory, "${target.name}.tmp")
        temporary.writeText(json.encodeToString(state))
        if (!temporary.renameTo(target)) { temporary.delete(); throw IOException("Не удалось сохранить настройки.") }
    }
}

class OkHttpPersonalStateApi(private val origin: String, json: Json, private val client: OkHttpClient) : PersonalStateApi {
    private val json = Json(json) { explicitNulls = true }
    override suspend fun sync(token: String, collection: String, seed: List<PersonalEntry>, changes: List<PersonalEntry>): List<PersonalEntry> = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("${origin.trimEnd('/')}/api/v2/personal-state/$collection/sync")
            .header("Authorization", "Bearer $token").header("Cache-Control", "no-cache")
            .post(json.encodeToString(Payload(seed, changes)).toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Синхронизация: HTTP ${response.code}")
            json.decodeFromString<SyncResponse>(response.body.string()).entries
        }
    }
    @Serializable private data class Payload(val seed: List<PersonalEntry>, val changes: List<PersonalEntry>)
    @Serializable private data class SyncResponse(val entries: List<PersonalEntry>)
}
