package ru.lumastack.lumawork.domain.auto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class VehicleDraftValidationTest {
    private val valid = VehicleDraft(licensePlate = "А123ВС", make = "Lada", model = "Vesta")

    @Test
    fun `vehicle can be saved without region VIN or optional details`() {
        assertNull(VehicleDraftValidation.error(valid))
    }

    @Test
    fun `edit checks number VIN and required identity before saving`() {
        assertNotNull(VehicleDraftValidation.error(valid.copy(licensePlate = "А12")))
        assertNotNull(VehicleDraftValidation.error(valid.copy(vin = "XTA123")))
        assertEquals("Укажите марку и модель.", VehicleDraftValidation.error(valid.copy(make = " \n")))
        assertEquals("Укажите марку и модель.", VehicleDraftValidation.error(valid.copy(model = "\t")))
    }

    @Test
    fun `identity step allows draft before optional fields are filled`() {
        assertNull(VehicleDraftValidation.identityError(VehicleDraft(make = "Lada", model = "Vesta")))
        assertNotNull(VehicleDraftValidation.identityError(VehicleDraft(make = "Lada")))
    }

    @Test
    fun `VIN length follows actual iOS nonstandard vehicle allowance`() {
        assertNull(VehicleDraftValidation.error(valid.copy(vin = "X".repeat(11))))
        assertNull(VehicleDraftValidation.error(valid.copy(vin = "X".repeat(24))))
        assertNotNull(VehicleDraftValidation.error(valid.copy(vin = "X".repeat(25))))
    }
}
