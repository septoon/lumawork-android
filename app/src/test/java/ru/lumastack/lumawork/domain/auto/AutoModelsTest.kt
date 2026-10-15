package ru.lumastack.lumawork.domain.auto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.lumastack.lumawork.domain.model.UserProfile

class AutoModelsTest {
    @Test
    fun `plate accepts latin input and formats Russian number`() {
        val result = VehicleInputValidation.formatPlate("a123bc82")

        assertEquals("А123ВС 82", result)
        assertNull(VehicleInputValidation.plateError(result))
    }

    @Test
    fun `vin maps Cyrillic lookalikes and removes forbidden characters`() {
        assertEquals("XTA123", VehicleInputValidation.normalizeVin("ХТАIОQ123"))
    }

    @Test
    fun `legacy profile fills only missing typed vehicle fields`() {
        val vehicle = Vehicle(id = "v1", make = "Lada", sts = "typed")
        val profile = UserProfile(
            vehicleModel = "Рабочая",
            vehicleSts = "legacy",
            vehiclePts = "82 РУ 074120",
            initialMileageKm = "42 000 км",
        )

        val result = vehicle.fillMissing(profile)

        assertEquals("typed", result.sts)
        assertEquals("82 РУ 074120", result.pts)
        assertEquals(42_000, result.currentMileageKm)
        assertEquals("Рабочая", result.customName)
    }

    @Test
    fun `maintenance derives total when server omits it`() {
        val record = MaintenanceRecord(
            date = "2026-08-12",
            procedure = "ТО",
            mileage = 42_000,
            parts = listOf(MaintenancePart("Масло", 1_000.0)),
            workCost = 500.0,
        )

        assertEquals(1_500.0, record.resolvedTotalCost ?: 0.0, 0.0)
    }
}
