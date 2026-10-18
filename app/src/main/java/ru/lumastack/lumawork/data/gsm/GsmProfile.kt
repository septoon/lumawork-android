package ru.lumastack.lumawork.data.gsm

import kotlinx.serialization.Serializable
import ru.lumastack.lumawork.domain.auto.Vehicle

/** Separate from AppUser.profile: this is the report generator's /gsm/profile contract. */
@Serializable
data class GsmProfile(
    val vehicleId: String? = null,
    val posProjectId: String? = null,
    val armProjectId: String? = null,
    val budgetCode: String = "",
    val employeeFullName: String = "",
    val employeeShortName: String = "",
    val employeeReportSignName: String = "",
    val authorizedFullName: String = "",
    val authorizedShortName: String = "",
    val employeeJobTitle: String = "",
    val employeeCompany: String = "",
    val employeeAddress: String = "",
    val employeePhone: String = "",
    val employeeTitleCompany: String = "",
    val employeeAddressPhone: String = "",
    val driverLicenseNumber: String = "",
    val fuelCardNumber: String = "",
    val carModel: String = "",
    val licensePlate: String = "",
    val fuelNorm: Double = 0.0,
    val fuelType: String = "",
    val fuelTypes: List<String> = emptyList(),
    val defaultStartOdometer: Int = 0,
    val reportStartMonth: String = "2026-04",
    val projectName: String = "",
) {
    fun toggleFuelType(value: String): GsmProfile {
        val next = if (value in fuelTypes) {
            if (fuelTypes.size <= 1) return this
            fuelTypes - value
        } else fuelTypes + value
        return copy(fuelTypes = next, fuelType = next.firstOrNull().orEmpty())
    }

    /** Invoked only after the vehicle confirmation dialog, independent of Auto carousel selection. */
    fun withVehicle(vehicle: Vehicle): GsmProfile {
        val settings = vehicle.gsmSettings
        val types = if (fuelTypes.isEmpty() && !settings?.fuelType.isNullOrEmpty()) listOf(settings!!.fuelType) else fuelTypes
        return copy(vehicleId = vehicle.id, carModel = vehicle.modelLine, licensePlate = vehicle.licensePlate.orEmpty(),
            fuelNorm = settings?.fuelNorm ?: fuelNorm, reportStartMonth = settings?.reportStartMonth ?: reportStartMonth,
            fuelTypes = types, fuelType = if (fuelTypes.isEmpty() && types.isNotEmpty()) types.first() else fuelType)
    }

    fun normalizedForSave(): GsmProfile {
        if (!GsmPhoneFormatter.isComplete(employeePhone)) throw GsmProfileFailure.Validation("Введите телефон полностью: +7(XXX)XXX-XX-XX.")
        if (!fuelNorm.isFinite()) throw GsmProfileFailure.Validation("Введите норму топлива числом.")
        return copy(employeePhone = GsmPhoneFormatter.format(employeePhone))
    }
}

@Serializable data class GsmProjectOption(val id: String, val name: String, val budgetCode: String)
data class GsmProfileLoadResult(val profile: GsmProfile, val availableFuelTypes: List<String> = emptyList())

@Serializable
data class GsmProfileSnapshot(
    val profile: GsmProfile = GsmProfile(),
    val projects: List<GsmProjectOption> = emptyList(),
    val availableFuelTypes: List<String> = emptyList(),
    val updatedAtEpochMillis: Long? = null,
) {
    val fuelOptions: List<String> get() = availableFuelTypes.ifEmpty { FALLBACK_FUEL_TYPES }

    companion object {
        private val FALLBACK_FUEL_TYPES = listOf("АИ-92", "АИ-92 Фирменное", "АИ-95", "АИ-95 Фирменное", "АИ-98", "АИ-100", "ДТ", "ДТ Зимнее")
    }
}

/** Cache/network results advance the baseline, but must never replace user edits. */
@Serializable
data class GsmProfileEditor(
    val snapshot: GsmProfileSnapshot = GsmProfileSnapshot(),
    val draft: GsmProfile = snapshot.profile,
    val revision: Long = 0,
    val isEdited: Boolean = false,
) {
    fun edit(transform: (GsmProfile) -> GsmProfile): GsmProfileEditor = copy(draft = transform(draft), revision = revision + 1L, isEdited = true)

    fun loaded(value: GsmProfileSnapshot): GsmProfileEditor = copy(snapshot = value, draft = if (isEdited) draft else value.profile)

    fun saved(value: GsmProfileSnapshot, savedRevision: Long): GsmProfileEditor =
        if (revision == savedRevision) copy(snapshot = value, draft = value.profile, isEdited = false)
        else copy(snapshot = value)
}

object GsmPhoneFormatter {
    data class EditResult(val text: String, val cursorOffset: Int)
    private const val LIMIT = 10

    fun format(value: String): String = mask(nationalDigits(value))
    fun isComplete(value: String): Boolean = nationalDigits(value).length == LIMIT

    fun edit(value: String, start: Int, end: Int, replacement: String): EditResult {
        val from = start.coerceIn(0, value.length)
        val to = end.coerceIn(from, value.length)
        val offsets = value.indices.filter { value[it] in '0'..'9' && !(value.startsWith("+7") && it == 1) }
        val digits = offsets.map { value[it] }.toMutableList()
        var lower = offsets.count { it < from }
        val upper = offsets.count { it < to }
        var inserted = replacement.filter { it in '0'..'9' }
        if (replacement.isEmpty() && to > from && lower == upper && lower > 0) lower--
        if (lower == 0 && upper == digits.size) {
            inserted = nationalDigits(replacement)
            if (from == 0 && to == value.length && replacement in listOf("7", "8")) return EditResult("+7", 2)
        }
        if (replacement.isNotEmpty() && inserted.isEmpty()) return EditResult(value, to)
        inserted = inserted.take((LIMIT - (digits.size - (upper - lower))).coerceAtLeast(0))
        digits.subList(lower, upper).clear()
        digits.addAll(lower, inserted.toList())
        val formatted = mask(digits.joinToString(""))
        val cursorIndex = lower + inserted.length
        val formattedOffsets = formatted.indices.filter { it > 1 && formatted[it] in '0'..'9' }
        return EditResult(formatted, formattedOffsets.getOrNull(cursorIndex) ?: formatted.length)
    }

    private fun nationalDigits(value: String): String {
        var digits = value.filter { it in '0'..'9' }
        if ((value.trim().startsWith("+7") && digits.startsWith("7")) ||
            (digits.length > LIMIT && digits.firstOrNull() in listOf('7', '8'))) digits = digits.drop(1)
        return digits.take(LIMIT)
    }

    private fun mask(digits: String): String {
        if (digits.isEmpty()) return ""
        return buildString {
            append("+7("); append(digits.take(3))
            if (digits.length >= 3) append(")")
            if (digits.length > 3) append(digits.drop(3).take(3))
            if (digits.length > 6) { append("-"); append(digits.drop(6).take(2)) }
            if (digits.length > 8) { append("-"); append(digits.drop(8).take(2)) }
        }
    }
}
