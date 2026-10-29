package ru.lumastack.lumawork.data.tools

import java.io.File
import kotlinx.serialization.Serializable

@Serializable enum class PaxArchiveKind(val title: String, val ftpPath: String) {
    Firmware("Прошивка", PAX_FIRMWARE_FTP_PATH),
    Prolin("Prolin / ОС", "/VTB_TO/TO/Vendor/Verifone_Pax/POSLoader");

    fun ftpPathFor(model: PaxModel): String =
        if (this == Firmware && model == PaxModel.S210) "/VTB_TO/TO/Vendor/Verifone_Pax" else ftpPath
}

@Serializable
enum class PaxModel(val title: String, val archiveFolder: String) {
    D230("PAX D230", "Pax_Prolin_D230"),
    D270("PAX D270", "Pax_Prolin_Nuxiums"),
    Q25("PAX Q25", "Pax_Prolin_TTF"),
    S210("PAX S210", "soft PP pax s210");

    val availableModes: List<PaxInstallMode> get() = if (this == S210)
        listOf(PaxInstallMode.Full, PaxInstallMode.Prolin, PaxInstallMode.MainApp) else PaxInstallMode.entries
}

internal val PaxModel.xcbUsbMenuPath: String get() =
    if (this == PaxModel.D230) "main menu → System Config → XCB Service → USB"
    else "main menu → General → XCB service → USB"

data class PaxFirmwarePackage(
    val directory: File,
    val model: PaxModel,
    val boot: PaxFirmwareAsset? = null,
    val os: PaxFirmwareAsset? = null,
    val application: PaxFirmwareAsset? = null,
    val appData: List<PaxFirmwareAsset> = emptyList(),
    val options: List<PaxFirmwareAsset> = emptyList(),
    val expectedOs: String? = null,
    val expectedMainApp: String? = null,
    val expectedBoot: String? = null,
) {
    val totalBytes: Long get() = (listOfNotNull(boot, os, application) + appData + options).sumOf { it.size }
    val hasProlin: Boolean get() = os != null
    val hasMainApp: Boolean get() = application != null
    val hasBoot: Boolean get() = boot != null
    val hasOs: Boolean get() = os != null
}

/** Only this hash-to-POSInfo relationship was demonstrated by the supplied successful capture. */
internal fun expectedPinpadOs(asset: PaxFirmwareAsset?): String? =
    if (asset?.sha256 == "8c1103db3fb2e8032580f3cdfa6b984674ca05ada01678dc84d30d8fb27d2b4d") "1.0.08.308e43ddR" else null
