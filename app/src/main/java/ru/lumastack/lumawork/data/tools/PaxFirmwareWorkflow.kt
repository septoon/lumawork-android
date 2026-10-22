package ru.lumastack.lumawork.data.tools

@kotlinx.serialization.Serializable
enum class PaxInstallMode(val title: String) {
    Full("Полный цикл"), RemoveMainApp("Удалить MAINAPP"), Prolin("Загрузить Prolin / ОС"), MainApp("Загрузить MAINAPP"),
}
@kotlinx.serialization.Serializable
enum class PaxFirmwareStep(val title: String) {
    Reading("Проверка устройства"), Removing("Удаление MAINAPP"), BootUpload("Передача загрузчика"), BootInstall("Установка загрузчика"),
    OsUpload("Передача ОС"), OsInstall("Установка ОС"), Reboot("Перезагрузка PAX"), Reconnect("Подключите PAX после перезагрузки"),
    CheckingOs("Проверка ОС и загрузчика"), RemovingAgain("Подготовка MAINAPP"), AppUpload("Передача приложения"), AppInstall("Установка приложения"),
    AppData("Установка файлов приложения"), Options("Установка дополнительных пакетов"), Verifying("Проверка результата"), Complete("Операция завершена"),
}
/** Persisted command identifiers remain compatible; presentation follows the selected protocol family. */
fun PaxInstallMode.titleFor(model: PaxModel): String = if (model == PaxModel.S210) when (this) {
    PaxInstallMode.Full -> "OS + APP"
    PaxInstallMode.Prolin -> "OS"
    PaxInstallMode.MainApp -> "APP"
    PaxInstallMode.RemoveMainApp -> "Удаление недоступно"
} else when (this) {
    PaxInstallMode.Full -> "Полный цикл"
    PaxInstallMode.RemoveMainApp -> "Удалить MAINAPP"
    PaxInstallMode.Prolin -> "Prolin"
    PaxInstallMode.MainApp -> "MAINAPP"
}
fun PaxFirmwareStep.titleFor(model: PaxModel): String = if (model != PaxModel.S210) title else when (this) {
    PaxFirmwareStep.OsUpload -> "Передача OS"
    PaxFirmwareStep.OsInstall -> "Запись OS"
    PaxFirmwareStep.AppUpload -> "Передача APP"
    PaxFirmwareStep.AppInstall -> "Запись APP"
    PaxFirmwareStep.Reading -> "Проверка S210"
    PaxFirmwareStep.Reconnect -> "Ожидание S210"
    PaxFirmwareStep.Reboot -> "Перезагрузка S210"
    PaxFirmwareStep.CheckingOs -> "Проверка OS"
    PaxFirmwareStep.Verifying -> "Проверка результата"
    PaxFirmwareStep.Complete -> "Операция завершена"
    else -> "Операция S210"
}
internal interface PaxFirmwarePort : AutoCloseable {
    suspend fun passport(): PaxPassport
    suspend fun upload(asset: PaxFirmwareAsset): String
    suspend fun removeMainApp()
    suspend fun installBoot(remote: String)
    suspend fun installOs(remote: String)
    suspend fun installMainApp(remote: String)
    suspend fun installAppData(remote: String)
    suspend fun installOption(remote: String)
    suspend fun reboot()
}
internal interface PaxFirmwareHost {
    suspend fun open(key: String): PaxFirmwarePort
    suspend fun reconnect(previousKey: String, fingerprint: String): PaxFirmwarePort
    fun armReboot() {}
}

/** Checkpoints cover confirmed operations, never offsets in a remote temporary file.
 * A new session rechecks identity and reuploads the current unconfirmed file after explicit user confirmation. */
internal class PaxFirmwareWorkflow(
    private val host: PaxFirmwareHost,
    private val event: (PaxFirmwareStep, String?) -> Unit,
    private val checkpoint: (List<String>, String?) -> Unit,
) {
    constructor(host: PaxFirmwareHost, event: (PaxFirmwareStep, String?) -> Unit) : this(host, event, { _, _ -> })
    suspend fun execute(bundle: PaxFirmwareBundle, key: String, expectedFingerprint: String): PaxPassport =
        execute(bundle.asPackage(), PaxInstallMode.Full, key, expectedFingerprint)

    suspend fun execute(bundle: PaxFirmwarePackage?, mode: PaxInstallMode, key: String, expectedFingerprint: String,
        model: PaxModel = bundle?.model ?: PaxModel.D270, completed: List<String> = emptyList(), pending: String? = null,
    ): PaxPassport {
        paxRequire(model != PaxModel.S210, "Для S210 используется отдельный протокол пинпада.")
        paxRequire(expectedFingerprint.isNotBlank(), "Сначала прочитайте паспорт конкретного PAX.")
        if (mode != PaxInstallMode.RemoveMainApp) paxRequire(bundle?.model == model, "Выберите комплект этой модели.")
        val withOs = mode in listOf(PaxInstallMode.Full, PaxInstallMode.Prolin)
        val withApp = mode in listOf(PaxInstallMode.Full, PaxInstallMode.MainApp)
        if (withOs) paxRequire(bundle?.os != null && bundle.expectedOs != null, "В папке этой модели нет ОС с метаданными версии. Выберите полный комплект Prolin.")
        if (withOs && bundle?.boot != null) paxRequire(bundle.expectedBoot != null, "В метаданных загрузчика нет версии. Запись не начата.")
        if (withApp) paxRequire(bundle?.application != null && bundle.expectedMainApp != null, "Нет пакета MAINAPP с подтверждённой версией appinfo.")
        val ids = buildList {
            if (mode in listOf(PaxInstallMode.Full, PaxInstallMode.RemoveMainApp)) add("remove")
            if (withOs) { if (bundle?.boot != null) add("boot"); addAll(listOf("os", "reboot", "reconnect")) }
            if (withApp) {
                if (mode == PaxInstallMode.Full) add("remove-again")
                add("app"); bundle!!.appData.indices.forEach { add("data:$it") }; bundle.options.indices.forEach { add("option:$it") }
            }
            add("verify")
        }
        paxRequire(completed == ids.take(completed.size), "Журнал этапов не соответствует выбранной операции.")
        paxRequire(pending == null || pending == ids.getOrNull(completed.size), "Текущий этап не соответствует журналу.")
        val done = completed.toMutableList()
        event(PaxFirmwareStep.Reading, null)
        var port: PaxFirmwarePort? = null
        try {
            port = host.open(key)
            var current = port.passport()
            checkDevice(current, model, expectedFingerprint)
            // A persisted reconnect must not let a replacement/downgraded device bypass the OS gate.
            if (withOs && "reconnect" in done) checkOs(current, requireNotNull(bundle))
            if (withApp && "app" in done) paxRequire(current.mainApp == bundle?.expectedMainApp, "MAINAPP изменился после остановки. Установка файлов приложения заблокирована.")
            if ("verify" in done) {
                if (withOs) checkOs(current, requireNotNull(bundle))
                if (withApp) paxRequire(current.mainApp == bundle?.expectedMainApp, "Версия MAINAPP изменилась после остановки.")
                if (mode == PaxInstallMode.RemoveMainApp) paxRequire(current.mainApp == null, "MAINAPP установлен после остановки.")
            }
            for (id in ids.drop(done.size)) {
                checkpoint(done.toList(), id) // Durable intent before a command can mutate the terminal.
                when (id) {
                    "remove", "remove-again" -> {
                        event(if (id == "remove") PaxFirmwareStep.Removing else PaxFirmwareStep.RemovingAgain, null)
                        port!!.removeMainApp()
                        if (id == "remove") {
                            current = port.passport()
                            paxRequire(current.mainApp == null, "Удаление MAINAPP не подтверждено.")
                        }
                    }
                    "boot" -> install(port!!, requireNotNull(bundle?.boot), PaxFirmwareStep.BootUpload, PaxFirmwareStep.BootInstall) { remote -> port!!.installBoot(remote) }
                    "os" -> install(port!!, requireNotNull(bundle?.os), PaxFirmwareStep.OsUpload, PaxFirmwareStep.OsInstall) { remote -> port!!.installOs(remote) }
                    "reboot" -> {
                        event(PaxFirmwareStep.Reboot, null)
                        host.armReboot()
                        try { port!!.reboot() } catch (_: PaxConnectionLostException) {
                            // Reboot may drop USB before its final ACK. No retry: the fresh passport is the next gate.
                        }
                        port!!.close(); port = null
                    }
                    "reconnect" -> {
                        event(PaxFirmwareStep.Reconnect, null)
                        port?.close(); port = null
                        port = host.reconnect(key, expectedFingerprint)
                        event(PaxFirmwareStep.CheckingOs, null)
                        current = port.passport()
                        checkDevice(current, model, expectedFingerprint)
                        checkOs(current, requireNotNull(bundle))
                    }
                    "app" -> install(port!!, requireNotNull(bundle?.application), PaxFirmwareStep.AppUpload, PaxFirmwareStep.AppInstall) { remote -> port!!.installMainApp(remote) }
                    "verify" -> {
                        event(PaxFirmwareStep.Verifying, null)
                        current = port!!.passport()
                        checkDevice(current, model, expectedFingerprint)
                        if (withOs) checkOs(current, requireNotNull(bundle))
                        if (withApp) paxRequire(current.mainApp == bundle?.expectedMainApp, "Версия MAINAPP после установки не подтверждена.")
                        if (mode == PaxInstallMode.RemoveMainApp) paxRequire(current.mainApp == null, "MAINAPP всё ещё установлен.")
                    }
                    else -> {
                        val data = id.startsWith("data:")
                        val index = id.substringAfter(':').toInt()
                        val asset = if (data) bundle!!.appData[index] else bundle!!.options[index]
                        event(if (data) PaxFirmwareStep.AppData else PaxFirmwareStep.Options, asset.name)
                        val remote = port!!.upload(asset)
                        if (data) port.installAppData(remote) else port.installOption(remote)
                    }
                }
                done += id
                checkpoint(done.toList(), null) // Only after terminal confirmation (reboot is a requested transition).
            }
            event(PaxFirmwareStep.Complete, null)
            return current
        } finally { port?.close() }
    }

    private suspend fun install(port: PaxFirmwarePort, asset: PaxFirmwareAsset, upload: PaxFirmwareStep, write: PaxFirmwareStep,
        apply: suspend (String) -> Unit,
    ) {
        event(upload, asset.name)
        val remote = port.upload(asset)
        event(write, asset.name)
        apply(remote)
    }
    private fun checkOs(passport: PaxPassport, bundle: PaxFirmwarePackage) {
        paxRequire(paxVersionMatches(passport.os, bundle.expectedOs) &&
            (bundle.boot == null || paxVersionMatches(passport.boot, bundle.expectedBoot)),
            "ОС или загрузчик после перезапуска не совпали с комплектом. Следующий этап заблокирован.")
    }
    private fun checkDevice(passport: PaxPassport, model: PaxModel, fingerprint: String) {
        paxRequire(passport.model == model.name && passport.target == "Inpas", "Модель или платформа PAX не соответствует выбранному комплекту.")
        paxRequire(passport.fingerprint == fingerprint, "Подключён другой PAX. Продолжение заблокировано.")
    }
}

/** RELEASE.TXT uses -r13278 while devinfo can report .13278R or omit the build. */
internal fun paxVersionMatches(actual: String?, expected: String?): Boolean {
    if (actual == null || expected == null) return false
    if (actual == expected) return true
    fun version(value: String) = Regex("([0-9]+(?:\\.[0-9]+){2,4})(?:-[rR]([0-9]+))?").find(value)
    val received = version(actual) ?: return false
    val released = version(expected) ?: return false
    val receivedCore = received.groupValues[1]; val releasedCore = released.groupValues[1]
    val receivedBuild = received.groups[2]?.value; val releasedBuild = released.groups[2]?.value
    if (receivedCore == releasedCore) return receivedBuild == null || releasedBuild == null || receivedBuild == releasedBuild
    return (receivedBuild == null && releasedBuild != null && receivedCore == "$releasedCore.$releasedBuild") ||
        (releasedBuild == null && receivedBuild != null && releasedCore == "$receivedCore.$receivedBuild")
}

internal fun PaxFirmwareBundle.asPackage() = PaxFirmwarePackage(directory, PaxModel.D270, boot, os, application,
    appData, options, expectedOs, expectedMainApp, expectedBoot)
internal fun readPaxPackage(directory: java.io.File, model: PaxModel): PaxFirmwarePackage =
    if (java.io.File(directory, ".pax-import.json").isFile) PaxArchiveCatalog.read(directory, model)
    else {
        require(model == PaxModel.D270) { "Старый комплект предназначен только для D270." }
        PaxFirmwareBundleReader.read(directory).asPackage()
    }
