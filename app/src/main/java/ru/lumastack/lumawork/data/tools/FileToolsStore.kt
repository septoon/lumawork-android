package ru.lumastack.lumawork.data.tools

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class FileToolsStore(context: Context) : ToolsStore {
    private val directory = File(context.noBackupFilesDir, "terminal-tools")
    private val json = Json { ignoreUnknownKeys = true }
    private fun file(owner: String): AtomicFile {
        require(owner.isNotBlank())
        val key = MessageDigest.getInstance("SHA-256").digest(owner.toByteArray()).joinToString("") { "%02x".format(it) }
        return AtomicFile(File(directory, "$key.json"))
    }
    override suspend fun read(owner: String): ToolsSnapshot = withContext(Dispatchers.IO) {
        val file = file(owner)
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) ToolsSnapshot()
        else file.openRead().use { json.decodeFromString<ToolsSnapshot>(it.bufferedReader().readText()) }
    }
    override suspend fun write(owner: String, snapshot: ToolsSnapshot) = withContext(Dispatchers.IO) {
        check(directory.isDirectory || directory.mkdirs()) { "Хранилище инструментов недоступно." }
        val file = file(owner)
        val output = file.startWrite()
        try { output.write(json.encodeToString(snapshot).toByteArray()); file.finishWrite(output) }
        catch (error: Exception) { file.failWrite(output); throw error }
    }
}
