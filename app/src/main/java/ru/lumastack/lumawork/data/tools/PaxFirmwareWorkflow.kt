package ru.lumastack.lumawork.data.tools

@kotlinx.serialization.Serializable
enum class PaxFirmwareStep(val title: String) {
    Reading("Проверка терминала"), Removing("Удаление MAINAPP"), BootUpload("Передача загрузчика"), BootInstall("Установка загрузчика"),
    OsUpload("Передача Nuxiums"), OsInstall("Установка Nuxiums"), Reboot("Перезагрузка PAX"), Reconnect("Ожидание PAX после перезагрузки"),
    CheckingOs("Проверка ОС и загрузчика"), RemovingAgain("Подготовка MAINAPP"), AppUpload("Передача MAINAPP"), AppInstall("Установка MAINAPP"),
    AppData("Установка файлов приложения"), Options("Установка дополнительных пакетов"), Verifying("Проверка результата"), Complete("Установка подтверждена"),
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
}
internal class PaxFirmwareWorkflow(private val host: PaxFirmwareHost, private val event: (PaxFirmwareStep, String?) -> Unit) {
    suspend fun execute(bundle: PaxFirmwareBundle, key: String, expectedFingerprint: String): PaxPassport {
        paxRequire(expectedFingerprint.isNotBlank(), "Сначала прочитайте паспорт конкретного PAX.")
        event(PaxFirmwareStep.Reading, null)
        host.open(key).use { port ->
            checkDevice(port.passport(), expectedFingerprint)
            event(PaxFirmwareStep.Removing, null)
            port.removeMainApp()
            paxRequire(port.passport().mainApp == null, "Удаление MAINAPP не подтверждено. Установка остановлена.")
            event(PaxFirmwareStep.BootUpload, bundle.boot.name)
            val boot = port.upload(bundle.boot)
            event(PaxFirmwareStep.BootInstall, null)
            port.installBoot(boot)
            event(PaxFirmwareStep.OsUpload, bundle.os.name)
            val os = port.upload(bundle.os)
            event(PaxFirmwareStep.OsInstall, null)
            port.installOs(os)
            event(PaxFirmwareStep.Reboot, null)
            port.reboot()
        }
        event(PaxFirmwareStep.Reconnect, null)
        val result = host.reconnect(key, expectedFingerprint).use { port ->
            event(PaxFirmwareStep.CheckingOs, null)
            val current = port.passport()
            checkDevice(current, expectedFingerprint)
            paxRequire(current.os == bundle.expectedOs && current.boot == bundle.expectedBoot,
                "Версия ОС или загрузчика после перезагрузки не совпала с комплектом. MAINAPP не устанавливается.")
            event(PaxFirmwareStep.RemovingAgain, null)
            port.removeMainApp()
            event(PaxFirmwareStep.AppUpload, bundle.application.name)
            val app = port.upload(bundle.application)
            event(PaxFirmwareStep.AppInstall, null)
            port.installMainApp(app)
            for (file in bundle.appData) {
                event(PaxFirmwareStep.AppData, file.name)
                port.installAppData(port.upload(file))
            }
            for (file in bundle.options) {
                event(PaxFirmwareStep.Options, file.name)
                port.installOption(port.upload(file))
            }
            event(PaxFirmwareStep.Verifying, null)
            port.passport().also { passport ->
                checkDevice(passport, expectedFingerprint)
                paxRequire(passport.os == bundle.expectedOs && passport.boot == bundle.expectedBoot && passport.mainApp == bundle.expectedMainApp,
                    "Версии после установки не подтверждены. Проверьте терминал штатным инструментом; автоматического повтора нет.")
            }
        }
        event(PaxFirmwareStep.Complete, null)
        return result
    }

    private fun checkDevice(passport: PaxPassport, fingerprint: String) {
        paxRequire(passport.model == "D270" && passport.os?.startsWith("Nuxiums") == true && passport.target == "Inpas",
            "Этот комплект предназначен для PAX D270 / Nuxiums / Inpas. Данные терминала не совпадают.")
        paxRequire(passport.fingerprint == fingerprint, "Подключён другой терминал или его идентичность не подтверждена. Установка остановлена.")
    }
}
