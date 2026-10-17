package ru.lumastack.lumawork.domain.fuel

import java.time.YearMonth
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

const val FUEL_IMPORT_MAX_FILES = 20
const val FUEL_IMPORT_MAX_FILE_BYTES = 6 * 1024 * 1024
const val FUEL_IMPORT_MAX_TOTAL_BYTES = 7 * 1024 * 1024

fun validateFuelImportSelection(files: List<Pair<String, Long>>): String? = when {
    files.isEmpty() -> "Выберите XLSX-отчёты."
    files.size > FUEL_IMPORT_MAX_FILES -> "Можно выбрать не больше 20 XLSX-отчётов."
    files.any { !it.first.endsWith(".xlsx", ignoreCase = true) } -> "Поддерживаются только файлы XLSX."
    files.any { it.first.isBlank() || it.first.length > 240 } -> "Имя XLSX должно быть не длиннее 240 символов."
    files.any { it.second <= 0 || it.second > FUEL_IMPORT_MAX_FILE_BYTES } -> "Размер каждого XLSX должен быть не больше 6 МБ."
    files.sumOf { it.second } > FUEL_IMPORT_MAX_TOTAL_BYTES -> "Общий размер выбранных отчётов должен быть не больше 7 МБ."
    else -> null
}

@Serializable
data class FuelImportUpload(val fileName: String, val dataBase64: String)

@Serializable
enum class FuelImportStatus(val title: String) {
    @SerialName("ready") Ready("Готов к импорту"),
    @SerialName("warning") Warning("Есть предупреждения"),
    @SerialName("duplicate") Duplicate("Уже импортирован"),
    @SerialName("conflict") Conflict("Конфликт с историей"),
    @SerialName("replaceable") Replaceable("Можно заменить прошлый импорт"),
    @SerialName("error") Error("Файл не удалось обработать"),
}

@Serializable
data class FuelImportEntry(val row: Int, val date: String? = null, val fuelType: String? = null, val liters: Double? = null, val cost: Double? = null) {
    val isIncomplete: Boolean get() = date == null || fuelType == null || liters == null || cost == null
}

@Serializable
data class FuelImportTypeTotal(val fuelType: String, val liters: Double, val cost: Double)

@Serializable
data class FuelImportPreviewItem(
    val id: String,
    val fileName: String,
    val fileHash: String,
    val status: FuelImportStatus,
    val period: String? = null,
    val entries: List<FuelImportEntry> = emptyList(),
    val totalsByFuelType: List<FuelImportTypeTotal> = emptyList(),
    val totalLiters: Double = 0.0,
    val totalCost: Double = 0.0,
    val mileage: Double? = null,
    val carModel: String? = null,
    val fuelNorm: Double? = null,
    val worksheets: List<String> = emptyList(),
    val reportFormatVersion: Int = 0,
    val importerVersion: String = "",
    val warnings: List<String> = emptyList(),
    val errors: List<String> = emptyList(),
    val existingImportId: String? = null,
) {
    fun correction() = FuelImportCorrection(fileHash, period, entries.map { FuelImportEntryCorrection(it.row, it.date.orEmpty(), it.fuelType.orEmpty(), it.liters?.toString().orEmpty(), it.cost?.toString().orEmpty()) })
    fun canImport(correction: FuelImportCorrection, replacementIds: Set<String>): Boolean =
        correction.monthEndDate != null && (status == FuelImportStatus.Ready || status == FuelImportStatus.Warning ||
            (status == FuelImportStatus.Replaceable && existingImportId != null && existingImportId in replacementIds))
}

// Editable strings stay out of API DTOs so comma decimals and blank optional fields are handled explicitly.
data class FuelImportEntryCorrection(val row: Int, val date: String = "", val fuelType: String = "", val liters: String = "", val cost: String = "") {
    fun payload() = FuelImportEntryPayload(row, date.trim().ifBlank { null }, fuelType.trim().ifBlank { null }, liters.importNumber(), cost.importNumber())
}

data class FuelImportCorrection(val fileHash: String, val period: String?, val entries: List<FuelImportEntryCorrection>) {
    val monthEndDate: String? get() = period?.takeIf { Regex("\\d{4}-(0[1-9]|1[0-2])").matches(it) }
        ?.let { runCatching { YearMonth.parse(it).atEndOfMonth().toString() }.getOrNull() }
    val totalLiters: Double get() = entries.sumOf { it.liters.importNumber() ?: 0.0 }
    val totalCost: Double get() = entries.sumOf { it.cost.importNumber() ?: 0.0 }
    fun payload() = FuelImportCorrectionPayload(fileHash, period, entries.map { it.payload() })
    fun effectiveEntries(item: FuelImportPreviewItem): List<FuelImportEntry> = item.entries.map { original ->
        val corrected = entries.firstOrNull { it.row == original.row }?.payload()
        original.copy(date = corrected?.date ?: original.date, fuelType = corrected?.fuelType ?: original.fuelType,
            liters = corrected?.liters ?: original.liters, cost = corrected?.cost ?: original.cost)
    }
    fun validationMessage(): String? {
        if (monthEndDate == null) return "Укажите месяц в формате ГГГГ-ММ."
        if (entries.size > 100) return "В отчёте должно быть не больше 100 строк заправок."
        for (entry in entries) {
            if (entry.date.isNotBlank() && runCatching { java.time.LocalDate.parse(entry.date.trim()) }.isFailure) return "Строка ${entry.row}: укажите дату YYYY-MM-DD."
            if (entry.fuelType.trim().length > 80) return "Строка ${entry.row}: вид топлива должен быть не длиннее 80 символов."
            if (entry.liters.isNotBlank() && entry.liters.importNumber()?.let { it in 0.0..10_000.0 } != true) return "Строка ${entry.row}: укажите литры от 0 до 10 000."
            if (entry.cost.isNotBlank() && entry.cost.importNumber()?.let { it in 0.0..10_000_000.0 } != true) return "Строка ${entry.row}: укажите сумму от 0 до 10 000 000."
        }
        return null
    }
}

@Serializable
data class FuelImportEntryPayload(val row: Int, val date: String? = null, val fuelType: String? = null, val liters: Double? = null, val cost: Double? = null)
@Serializable
data class FuelImportCorrectionPayload(val fileHash: String, val period: String? = null, val entries: List<FuelImportEntryPayload>)

@Serializable
data class FuelImportCommitResponse(
    val importedMonths: Int,
    val skippedMonths: Int,
    val importedRefuels: Int,
    val totalLiters: Double,
    val totalCost: Double,
    val results: List<FuelImportCommitResult> = emptyList(),
) {
    val notice: String get() = "История топлива восстановлена. Импортировано месяцев: $importedMonths, заправок: $importedRefuels." +
        if (skippedMonths > 0) " Пропущено: $skippedMonths." else ""
}
@Serializable
data class FuelImportCommitResult(val fileName: String, val period: String? = null, val status: String, val message: String, val refuelCount: Int = 0)

private fun String.importNumber(): Double? = trim().replace(',', '.').toDoubleOrNull()?.takeIf(Double::isFinite)
