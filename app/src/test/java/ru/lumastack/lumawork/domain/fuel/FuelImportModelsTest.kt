package ru.lumastack.lumawork.domain.fuel

import org.junit.Assert.*
import org.junit.Test

class FuelImportModelsTest {
    @Test fun `replacement requires explicit matching import id and safe statuses`() {
        val ready = item(FuelImportStatus.Ready)
        assertTrue(ready.canImport(ready.correction(), emptySet()))
        val replacement = item(FuelImportStatus.Replaceable).copy(existingImportId = "old")
        assertFalse(replacement.canImport(replacement.correction(), emptySet()))
        assertFalse(replacement.canImport(replacement.correction(), setOf("another")))
        assertTrue(replacement.canImport(replacement.correction(), setOf("old")))
        listOf(FuelImportStatus.Error, FuelImportStatus.Duplicate, FuelImportStatus.Conflict).forEach { status ->
            val blocked = item(status)
            assertFalse(blocked.canImport(blocked.correction(), setOf("old")))
        }
    }

    @Test fun `unknown month requires correction and missing date falls back to month end`() {
        val missing = item(FuelImportStatus.Warning).copy(period = null)
        assertFalse(missing.canImport(missing.correction(), emptySet()))
        val correction = missing.correction().copy(period = "2024-02")
        assertTrue(missing.canImport(correction, emptySet()))
        assertEquals("2024-02-29", correction.monthEndDate)
        assertFalse(missing.canImport(correction.copy(period = "2024-13"), emptySet()))
    }

    @Test fun `correction accepts comma decimals omits empty and invalid numbers`() {
        val correction = FuelImportEntryCorrection(7, " 2026-04-15 ", " АИ-95 ", "12,5", "")
        assertEquals(12.5, correction.payload().liters!!, 0.0)
        assertEquals("АИ-95", correction.payload().fuelType)
        assertNull(correction.payload().cost)
        assertNull(correction.copy(liters = "NaN").payload().liters)
        assertNull(correction.copy(liters = "Infinity").payload().liters)
    }

    @Test fun `blank corrections retain original data exactly as backend and totals follow edited types`() {
        val original = item(FuelImportStatus.Warning).copy(entries = listOf(FuelImportEntry(9, "2026-04-12", "АИ-95", 20.0, 1500.0)))
        val correction = original.correction().copy(entries = listOf(FuelImportEntryCorrection(9, fuelType = "ДТ", liters = "12,5")))
        val effective = correction.effectiveEntries(original).single()
        assertEquals("2026-04-12", effective.date)
        assertEquals("ДТ", effective.fuelType)
        assertEquals(12.5, effective.liters!!, 0.0)
        assertEquals(1500.0, effective.cost!!, 0.0)
    }

    @Test fun `bounded upload input rejects total file and count limits`() {
        assertNull(validateFuelImportSelection(listOf("report.XLSX" to 100L)))
        assertNotNull(validateFuelImportSelection(listOf("report.xls" to 100L)))
        assertNotNull(validateFuelImportSelection(listOf("report.xlsx" to 0L)))
        assertNotNull(validateFuelImportSelection(listOf("report.xlsx" to (FUEL_IMPORT_MAX_FILE_BYTES + 1L))))
        assertNotNull(validateFuelImportSelection(List(21) { "report.xlsx" to 1L }))
        assertNotNull(validateFuelImportSelection(List(2) { "report.xlsx" to 4L * 1024 * 1024 }))
    }

    private fun item(status: FuelImportStatus) = FuelImportPreviewItem("id", "report.xlsx", "a".repeat(64), status, "2026-04")
}
