package ru.lumastack.lumawork.data.tools

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable enum class PaxInstallPhase { Idle, Ready, Running, Complete, Failed, Interrupted }
@Serializable data class PaxInstallState(
    val owner: String = "", val phase: PaxInstallPhase = PaxInstallPhase.Idle,
    val bundleDirectory: String? = null, val step: PaxFirmwareStep? = null,
    val detail: String = "", val fileName: String? = null, val bytes: Long = 0, val totalBytes: Long = 0,
    val critical: Boolean = false, val updatedAt: Long = 0,
    val operationId: String = "",
    val stepNumber: Int = 0, val stepCount: Int = 0,
) {
    val active: Boolean get() = phase == PaxInstallPhase.Running
}

/** Separate install journal: FTP/history refresh cannot overwrite critical installation state. No serial or raw replies. */
class PaxFirmwareStore(context: Context) {
    private val root = File(context.noBackupFilesDir, "pax-firmware")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    fun ownerDirectory(owner: String): File {
        require(owner.isNotBlank())
        val key = MessageDigest.getInstance("SHA-256").digest(owner.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
        return File(root, key).apply { check(isDirectory || mkdirs()) }
    }
    fun checkedBundleDirectory(owner: String, path: String): File {
        val base = ownerDirectory(owner).canonicalFile
        val file = File(path).canonicalFile
        require(file.parentFile == base && file.name.startsWith("bundle-")) { "Комплект находится вне хранилища текущего пользователя." }
        return file
    }
    fun read(owner: String): PaxInstallState = synchronized(lock) {
        val file = AtomicFile(File(ownerDirectory(owner), "installation.json"))
        val value = if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists())
            file.openRead().use { json.decodeFromString<PaxInstallState>(it.bufferedReader().readText()) }
        else PaxInstallState(owner = owner)
        require(value.owner == owner)
        if (value.active && !(PaxFirmwareRuntime.state.value.active && PaxFirmwareRuntime.state.value.owner == owner))
            value.copy(phase = PaxInstallPhase.Interrupted, detail = "Приложение было остановлено. Результат установки неизвестен; проверьте терминал. Автоматического повтора нет.")
        else value
    }
    fun write(state: PaxInstallState) = synchronized(lock) {
        val file = AtomicFile(File(ownerDirectory(state.owner), "installation.json"))
        val stream = file.startWrite()
        try { stream.write(json.encodeToString(state).toByteArray()); file.finishWrite(stream) }
        catch (failure: Throwable) { file.failWrite(stream); throw failure }
    }
    companion object { private val lock = Any() }
}

object PaxFirmwareRuntime {
    internal val mutable = MutableStateFlow(PaxInstallState())
    val state = mutable.asStateFlow()
}
