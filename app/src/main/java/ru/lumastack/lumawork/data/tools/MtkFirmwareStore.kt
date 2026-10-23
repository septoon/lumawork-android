package ru.lumastack.lumawork.data.tools

import android.content.Context
import android.util.AtomicFile
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Only private files owned by this importer are reclaimed after process death. */
internal fun cleanupMtkImports(root: File, referencedPackage: File?) {
    root.listFiles()?.forEach { file ->
        if (file.name.startsWith(".mtk-staging-") || file.name.startsWith(".agent-") || file.name.startsWith(".mtk-backup-") ||
            file.name.startsWith("package-") && file.canonicalFile != referencedPackage?.canonicalFile) {
            check(file.deleteRecursively()) { "Не удалось удалить незавершённый импорт P8." }
        }
    }
}

@Serializable enum class MtkInstallPhase { Idle, Preparing, Prepared, WaitingForUsb, Permission, Handshake, Inspected, LoadingAgent, AgentInspected,
    VerifyingFiles, BackingUp, PreparingStorage, CheckingLayout, WritingPartition, Finalizing, WriteComplete, RecoveryRequired, Failed, Interrupted }
@Serializable data class MtkInstallState(
    val owner: String = "", val operationId: String = "", val phase: MtkInstallPhase = MtkInstallPhase.Idle,
    val packageDirectory: String? = null, val packageHash: String? = null,
    val detail: String = "Выберите исходный Download Agent и комплект прошивки.",
    val fileName: String? = null, val bytes: Long = 0, val totalBytes: Long = 0,
    val usbPermission: Boolean = false, val info: MtkPreloaderInfo? = null, val updatedAt: Long = 0,
    val agentStarted: Boolean = false, val target: MtkTargetInfo? = null,
    val destructiveStarted: Boolean=false, val pendingAction: String?=null, val lastAcknowledgedAction: String?=null,
    // Retained for journals from the initial test builds; new upgrades do not create backups.
    val backupDirectory: String?=null, val bootConfirmedAt: Long?=null,
    val upgradeRequested: Boolean=false, val confirmedActions: List<String> = emptyList(),
) {
    val active: Boolean get() = phase in listOf(MtkInstallPhase.Preparing, MtkInstallPhase.WaitingForUsb, MtkInstallPhase.Permission, MtkInstallPhase.Handshake, MtkInstallPhase.LoadingAgent,
        MtkInstallPhase.VerifyingFiles,MtkInstallPhase.BackingUp,MtkInstallPhase.PreparingStorage,MtkInstallPhase.CheckingLayout,MtkInstallPhase.WritingPartition,MtkInstallPhase.Finalizing)
    // This starts a new inspection; fresh RAM/GPT/BOM gates are enforced again before FORMAT.
    val canWrite: Boolean get() = packageDirectory!=null && packageHash!=null && !active && phase!=MtkInstallPhase.RecoveryRequired
    fun recover(): MtkInstallState = if(active && destructiveStarted) copy(phase=MtkInstallPhase.RecoveryRequired,usbPermission=false,
        detail="Запись была прервана. Требуется проверка и восстановление штатным Agenew. Команды автоматически не повторяются.")
        else if (active) copy(phase = MtkInstallPhase.Interrupted, usbPermission = false, fileName = null,
        detail = if (agentStarted) "Приложение было остановлено. Агент мог остаться в RAM. Отсоедините USB и перезапустите терминал. Запись eMMC не выполнялась."
        else "Приложение было остановлено. Диагностика не возобновлена. Запись eMMC не выполнялась.") else this
}

internal fun MtkInstallState.afterWriteEvent(value: MtkWriteEvent): MtkInstallState {
    if(phase==MtkInstallPhase.WriteComplete) return this
    val destructive=destructiveStarted || value.pendingAction?.let { it.startsWith("format:") || it.startsWith("download:") || it=="start-download-info" || it=="finish" }==true
    return copy(phase=MtkInstallPhase.valueOf(value.phase.name),destructiveStarted=destructive,pendingAction=value.pendingAction,
        lastAcknowledgedAction=value.acknowledgedAction ?: lastAcknowledgedAction,
        confirmedActions=if(value.acknowledgedAction!=null && value.acknowledgedAction !in confirmedActions) confirmedActions+value.acknowledgedAction else confirmedActions,
        fileName=value.fileName,bytes=value.bytes,totalBytes=value.totalBytes,usbPermission=usbPermission && value.phase!=MtkWritePhase.WriteComplete,
        detail=when(value.phase) {
            MtkWritePhase.VerifyingFiles -> "Повторная проверка SHA-256 исходных файлов перед FORMAT…"
            MtkWritePhase.PreparingStorage -> "Firmware Upgrade: подготовка eMMC. Не отключайте USB и питание."
            MtkWritePhase.CheckingLayout -> "Проверка PGPT/SGPT после FORMAT…"
            MtkWritePhase.WritingPartition -> "Запись ${value.fileName}. Не отключайте USB и питание."
            MtkWritePhase.Finalizing -> "Ожидание завершающих подтверждений агента…"
            MtkWritePhase.WriteComplete -> MTK_WRITE_SUCCESS
        })
}

/** Keep the checked package while clearing every fact belonging to the previous USB operation. */
internal fun MtkInstallState.freshOperation(id: String,nextPhase: MtkInstallPhase,upgrade: Boolean,message: String)=copy(
    operationId=id,phase=nextPhase,detail=message,fileName=null,bytes=0,totalBytes=0,usbPermission=false,
    info=null,target=null,agentStarted=false,destructiveStarted=false,pendingAction=null,lastAcknowledgedAction=null,
    backupDirectory=null,bootConfirmedAt=null,upgradeRequested=upgrade,confirmedActions=emptyList())

internal class MtkFirmwareStore(context: Context, private val owner: String) {
    val directory = File(context.noBackupFilesDir, "mtk-firmware/${mtkSha256(owner.toByteArray())}")
        .apply { require(owner.isNotBlank()); check(isDirectory || mkdirs()) }
    val agent get() = File(directory, "agent.bin")
    private val journal get() = AtomicFile(File(directory, "operation.json"))
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    fun packageDirectory(path: String): File {
        val result = File(path).canonicalFile
        require(result.parentFile == directory.canonicalFile && result.name.startsWith("package-")) { "Комплект вне хранилища пользователя." }
        return result
    }
    fun read(): MtkInstallState = synchronized(lock) {
        val file = journal
        val saved = if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists())
            file.openRead().use { json.decodeFromString<MtkInstallState>(it.bufferedReader().readText()) }
        else MtkInstallState(owner = owner)
        require(saved.owner == owner)
        saved.recover().also { if (it != saved) write(it) }
    }
    fun write(value: MtkInstallState) = synchronized(lock) {
        require(value.owner == owner)
        val file = journal
        val stream = file.startWrite()
        try { stream.write(json.encodeToString(value).toByteArray()); file.finishWrite(stream) }
        catch (failure: Throwable) { file.failWrite(stream); throw failure }
    }
    companion object { private val lock = Any() }
}
