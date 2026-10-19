package ru.lumastack.lumawork.data.assistant

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class FileAssistantCache(context: Context) : AssistantCache {
    private val root = File(context.noBackupFilesDir, "assistant-v1")
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private fun file(owner: String): AtomicFile {
        require(owner.isNotBlank())
        val key = MessageDigest.getInstance("SHA-256").digest(owner.toByteArray()).joinToString("") { "%02x".format(it) }
        return AtomicFile(File(root, "$key.json"))
    }
    override suspend fun read(owner: String): AssistantSavedState? = withContext(Dispatchers.IO) { lock.withLock {
        currentCoroutineContext().ensureActive()
        val file = file(owner)
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) null
        else file.openRead().use { json.decodeFromString<AssistantSavedState>(it.bufferedReader().readText()) }
    } }
    override suspend fun write(owner: String, state: AssistantSavedState) = withContext(Dispatchers.IO) { lock.withLock {
        currentCoroutineContext().ensureActive()
        check(root.isDirectory || root.mkdirs())
        val retained = (listOf(state.conversationId) + state.messages.keys.toList().asReversed()).distinct().take(30).toSet()
        val bounded = state.copy(messages = state.messages.filterKeys(retained::contains).mapValues { it.value.takeLast(200) })
        val file = file(owner); val stream = file.startWrite()
        try { stream.write(json.encodeToString(bounded).toByteArray()); currentCoroutineContext().ensureActive(); file.finishWrite(stream) }
        catch (error: Exception) { file.failWrite(stream); throw error }
    } }
    companion object { private val lock = Mutex() }
}
