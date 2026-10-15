package ru.lumastack.lumawork.data.wiki

import android.content.Context
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import ru.lumastack.lumawork.domain.wiki.WikiArticle
import ru.lumastack.lumawork.domain.wiki.WikiSearchSnapshot

interface WikiCache {
    suspend fun readSearch(ownerUserId: String): WikiSearchSnapshot?
    suspend fun saveSearch(ownerUserId: String, snapshot: WikiSearchSnapshot)
    suspend fun readArticle(articleId: String): WikiArticle?
    suspend fun saveArticle(article: WikiArticle)
}

class FileWikiCache(context: Context, private val json: Json) : WikiCache {
    private val directory = File(context.noBackupFilesDir, "wiki")

    override suspend fun readSearch(ownerUserId: String): WikiSearchSnapshot? = read(searchFile(ownerUserId))
    override suspend fun saveSearch(ownerUserId: String, snapshot: WikiSearchSnapshot) = write(searchFile(ownerUserId), snapshot)
    override suspend fun readArticle(articleId: String): WikiArticle? = read(articleFile(articleId))
    override suspend fun saveArticle(article: WikiArticle) = write(articleFile(article.id), article)

    private suspend inline fun <reified Value> read(file: File): Value? = withContext(Dispatchers.IO) {
        if (!file.isFile) return@withContext null
        runCatching { json.decodeFromString<Value>(file.readText()) }.getOrNull()
    }

    private suspend inline fun <reified Value> write(file: File, value: Value) = withContext(Dispatchers.IO) {
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, "${file.name}.tmp")
        val encoded = json.encodeToString(value)
        temporary.writeText(encoded)
        if (!temporary.renameTo(file)) {
            file.writeText(encoded)
            temporary.delete()
        }
    }

    private fun searchFile(ownerUserId: String) = File(directory, "search-${ownerUserId.sha256()}.json")
    private fun articleFile(articleId: String) = File(directory, "article-${articleId.sha256()}.json")
    private fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(toByteArray()).joinToString("") { "%02x".format(it) }
}
