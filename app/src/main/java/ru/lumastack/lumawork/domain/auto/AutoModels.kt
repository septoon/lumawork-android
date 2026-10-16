package ru.lumastack.lumawork.domain.auto

import kotlinx.serialization.Serializable
import ru.lumastack.lumawork.domain.model.UserProfile

@Serializable
data class VehicleGsmSettings(
    val fuelNorm: Double = 0.0,
    val fuelType: String = "",
    val defaultStartOdometer: Int = 0,
    val reportStartMonth: String = "2026-04",
)

@Serializable
data class Vehicle(
    val id: String,
    val make: String? = null,
    val model: String? = null,
    val generation: String? = null,
    val year: Int? = null,
    val bodyType: String? = null,
    val colorName: String? = null,
    val customName: String? = null,
    val licensePlate: String? = null,
    val vin: String? = null,
    val sts: String? = null,
    val pts: String? = null,
    val engineVolumeCm3: Int? = null,
    val enginePowerHp: Int? = null,
    val currentMileageKm: Int? = null,
    val isPrimary: Boolean = false,
    val imageUrl: String? = null,
    val imageStatus: String = "",
    val gsmSettings: VehicleGsmSettings? = null,
    val documents: List<VehicleDocument>? = null,
) {
    val displayName: String
        get() = customName.clean()
            ?: listOf(make.clean(), model.clean()).filterNotNull().joinToString(" ").ifBlank { null }
            ?: "Автомобиль"

    val modelLine: String
        get() = listOf(make.clean(), model.clean(), generation.clean()).filterNotNull().joinToString(" ")

    fun fillMissing(profile: UserProfile?): Vehicle {
        if (profile == null) return this
        return copy(
            customName = customName.clean() ?: profile.vehicleModel.clean(),
            licensePlate = licensePlate.clean() ?: profile.vehiclePlate.clean(),
            vin = vin.clean() ?: profile.vehicleVin.clean(),
            sts = sts.clean() ?: profile.vehicleSts.clean(),
            pts = pts.clean() ?: profile.vehiclePts.clean(),
            colorName = colorName.clean() ?: profile.vehicleColor.clean(),
            engineVolumeCm3 = engineVolumeCm3 ?: profile.engineVolumeCm3.number(),
            enginePowerHp = enginePowerHp ?: profile.enginePowerHp.number(),
            currentMileageKm = currentMileageKm ?: profile.initialMileageKm.number(),
        )
    }
}

@Serializable
data class VehicleDraft(
    val licensePlate: String = "",
    val vin: String = "",
    val sts: String = "",
    val pts: String = "",
    val make: String = "",
    val model: String = "",
    val generation: String = "",
    val year: String = "",
    val bodyType: String = "",
    val colorName: String = "",
    val currentMileageKm: String = "",
    val engineVolumeCm3: String = "",
    val enginePowerHp: String = "",
    val customName: String = "",
    val isPrimary: Boolean = false,
) {
    constructor(vehicle: Vehicle) : this(
        licensePlate = vehicle.licensePlate.orEmpty(),
        vin = vehicle.vin.orEmpty(),
        sts = vehicle.sts.orEmpty(),
        pts = vehicle.pts.orEmpty(),
        make = vehicle.make.orEmpty(),
        model = vehicle.model.orEmpty(),
        generation = vehicle.generation.orEmpty(),
        year = vehicle.year?.toString().orEmpty(),
        bodyType = vehicle.bodyType.orEmpty(),
        colorName = vehicle.colorName.orEmpty(),
        currentMileageKm = vehicle.currentMileageKm?.toString().orEmpty(),
        engineVolumeCm3 = vehicle.engineVolumeCm3?.toString().orEmpty(),
        enginePowerHp = vehicle.enginePowerHp?.toString().orEmpty(),
        customName = vehicle.customName.orEmpty(),
        isPrimary = vehicle.isPrimary,
    )
}

@Serializable
data class MaintenancePart(val name: String, val cost: Double)

@Serializable
data class MaintenanceRecord(
    val id: String? = null,
    val vehicleId: String? = null,
    val date: String,
    val procedure: String,
    val mileage: Int,
    val parts: List<MaintenancePart> = emptyList(),
    val workCost: Double? = null,
    val totalCost: Double? = null,
) {
    val stableId: String
        get() = id ?: "$date|$procedure|$mileage|${parts.joinToString { it.name }}|$workCost"

    val resolvedTotalCost: Double?
        get() = totalCost ?: if (parts.isEmpty() && workCost == null) null else parts.sumOf(MaintenancePart::cost) + (workCost ?: 0.0)
}

@Serializable
data class MaintenanceDraft(
    val vehicleId: String? = null,
    val date: String = "",
    val procedure: String = "",
    val mileage: String = "",
    val parts: List<MaintenancePartDraft> = emptyList(),
    val workCost: String = "",
) {
    constructor(record: MaintenanceRecord, fallbackVehicleId: String?) : this(
        vehicleId = record.vehicleId ?: fallbackVehicleId,
        date = record.date,
        procedure = record.procedure,
        mileage = record.mileage.toString(),
        parts = record.parts.map { MaintenancePartDraft(it.name, it.cost.toCleanString()) },
        workCost = record.workCost?.toCleanString().orEmpty(),
    )
}

@Serializable
data class MaintenancePartDraft(val name: String = "", val cost: String = "")

@Serializable
data class AutoSnapshot(
    val vehicles: List<Vehicle> = emptyList(),
    val maintenance: List<MaintenanceRecord> = emptyList(),
    val selectedVehicleId: String? = null,
    val updatedAtEpochMillis: Long? = null,
)

object VehicleInputValidation {
    private val latinToCyrillic = mapOf(
        'A' to 'А', 'B' to 'В', 'E' to 'Е', 'K' to 'К', 'M' to 'М', 'H' to 'Н',
        'O' to 'О', 'P' to 'Р', 'C' to 'С', 'T' to 'Т', 'Y' to 'У', 'X' to 'Х',
    )
    private val cyrillicToLatin = latinToCyrillic.entries.associate { (latin, cyrillic) -> cyrillic to latin }
    private val plateLetters = "АВЕКМНОРСТУХ".toSet()
    private val vinCharacters = "ABCDEFGHJKLMNPRSTUVWXYZ0123456789".toSet()

    fun formatPlate(value: String): String {
        val normalized = value.uppercase().mapNotNull { character ->
            latinToCyrillic[character] ?: character.takeIf { it.isDigit() || it in plateLetters }
        }
        val masked = buildList {
            normalized.forEach { character ->
                if (size >= 9) return@forEach
                val expectsLetter = size == 0 || size == 4 || size == 5
                if ((expectsLetter && character in plateLetters) || (!expectsLetter && character.isDigit())) add(character)
            }
        }.joinToString("")
        return if (masked.length > 6) "${masked.take(6)} ${masked.drop(6)}" else masked
    }

    fun plateError(value: String): String? {
        val compact = value.replace(" ", "")
        if (compact.isEmpty()) return "Введите государственный номер."
        return if (Regex("^[АВЕКМНОРСТУХ]\\d{3}[АВЕКМНОРСТУХ]{2}(\\d{2,3})?$").matches(compact)) null
        else "Пример: А123ВС 82 или А123ВС 777."
    }

    fun normalizeVin(value: String): String = value.uppercase().mapNotNull { character ->
        (cyrillicToLatin[character] ?: character).takeIf { it in vinCharacters }
    }.take(24).joinToString("")

    fun vinError(value: String): String? = when {
        value.isEmpty() -> null
        value.length !in 11..24 -> "VIN обычно содержит 17 символов; допустимо от 11 до 24."
        else -> null
    }
}

private fun String?.clean(): String? = this?.trim()?.ifBlank { null }
private fun String?.number(): Int? = clean()?.filter(Char::isDigit)?.toIntOrNull()
private fun Double.toCleanString(): String = if (this % 1.0 == 0.0) toInt().toString() else toString()
