package ru.lumastack.lumawork.domain.auto

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VehicleDocumentsTest {
    @Test
    fun `vehicle document metadata survives cache round trip and unknown kinds become other`() {
        val json = Json { ignoreUnknownKeys = true }
        val vehicle = json.decodeFromString<Vehicle>("""{"id":"v","documents":[{"id":"d","kind":"FUTURE_KIND","fileName":"ПТС.pdf","mimeType":"application/pdf","sizeBytes":123,"createdAt":"2026-10-17T00:00:00Z"}]}""")
        val document = vehicle.documents!!.single()
        assertEquals(VehicleDocumentKind.Other, document.kind)
        assertEquals("ПТС.pdf", document.fileName)
        assertEquals(123, document.sizeBytes)
        assertEquals(vehicle, json.decodeFromString<Vehicle>(json.encodeToString(Vehicle.serializer(), vehicle)))
        assertNull(json.decodeFromString<Vehicle>("""{"id":"v","documents":null}""").documents)
        assertNull(json.decodeFromString<Vehicle>("""{"id":"v"}""").documents)
    }

    @Test
    fun `document allows exactly 20 MiB rejects larger empty and unsupported selections`() {
        input(ByteArray(VehicleDocumentInput.MAX_BYTES))
        assertEquals("Документ не должен превышать 20 МБ.", runCatching { input(ByteArray(VehicleDocumentInput.MAX_BYTES + 1)) }.exceptionOrNull()?.message)
        assertEquals("Выбранный документ пуст.", runCatching { input(byteArrayOf()) }.exceptionOrNull()?.message)
        assertTrue(runCatching { VehicleDocumentInput(byteArrayOf(1), "bad.exe", "application/octet-stream", VehicleDocumentKind.Other) }.isFailure)
    }

    @Test
    fun `mime normalization and filename kind suggestion match existing iOS rules`() {
        assertEquals("image/jpeg", normalizedVehicleDocumentMime("image/jpg", "image"))
        assertEquals("application/pdf", normalizedVehicleDocumentMime("application/octet-stream", "Полис.PDF"))
        assertEquals("image/heif", normalizedVehicleDocumentMime(null, "photo.HEIF"))
        assertEquals(VehicleDocumentKind.Osago, VehicleDocumentKind.suggested("Полис 2026.pdf"))
        assertEquals(VehicleDocumentKind.Sts, VehicleDocumentKind.suggested("sts.jpg"))
        assertEquals(VehicleDocumentKind.Pts, VehicleDocumentKind.suggested("ПТС.pdf"))
        assertEquals(VehicleDocumentKind.DiagnosticCard, VehicleDocumentKind.suggested("то-2026.pdf"))
        assertEquals(VehicleDocumentKind.PurchaseContract, VehicleDocumentKind.suggested("дкп.pdf"))
        assertEquals(VehicleDocumentKind.Service, VehicleDocumentKind.suggested("заказ-наряд.pdf"))
    }

    @Test
    fun `display names cannot escape scoped preview directory`() {
        val name = safeVehicleDocumentName("../../folder\\secret\u0000.pdf")
        assertTrue('/' !in name && '\\' !in name && '\u0000' !in name)
        assertEquals("Документ", safeVehicleDocumentName(".."))
        assertEquals(180, safeVehicleDocumentName("a".repeat(500)).length)
    }

    private fun input(bytes: ByteArray) = VehicleDocumentInput(bytes, "document.pdf", "application/pdf", VehicleDocumentKind.Other)
}
