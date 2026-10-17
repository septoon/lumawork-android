package ru.lumastack.lumawork.domain.profile

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class WorkDocumentsTest {
    @Test fun `category order and suggestions match iOS including precedence`() {
        assertEquals(listOf("EMPLOYMENT_CONTRACT", "CERTIFICATE", "REGULATION", "INSTRUCTION", "APPLICATION", "QUALIFICATION", "OTHER"), WorkDocumentCategory.entries.map { it.wireValue })
        assertEquals(WorkDocumentCategory.EmploymentContract, WorkDocumentCategory.suggested("Трудовой договор.pdf"))
        assertEquals(WorkDocumentCategory.Certificate, WorkDocumentCategory.suggested("Медицинская справка.pdf"))
        assertEquals(WorkDocumentCategory.Qualification, WorkDocumentCategory.suggested("Сертификат обучения.pdf"))
        assertEquals(WorkDocumentCategory.Other, Json.decodeFromString<WorkDocumentCategory>("\"FUTURE_CATEGORY\""))
    }
    @Test fun `office selections normalize provider mime without losing category or title`() {
        val selection = WorkDocumentInput(byteArrayOf(1), "Обучение.docx", "application/octet-stream")
        assertEquals("application/vnd.openxmlformats-officedocument.wordprocessingml.document", selection.mimeType)
        assertEquals("Обучение", selection.title)
        assertEquals(WorkDocumentCategory.Qualification, selection.category)
        assertEquals("image/jpeg", normalizedWorkDocumentMime("image/jpg", "a.jpg"))
        assertFalse(safeWorkDocumentName("../../secret\\a.txt").contains('/'))
        assertFalse(safeWorkDocumentName("../../secret\\a.txt").contains('\\'))
    }
    @Test fun `selection rejects empty unsupported and over limit data`() {
        for (data in listOf(byteArrayOf(), ByteArray(20 * 1024 * 1024 + 1))) {
            assertTrue(runCatching { WorkDocumentInput(data, "a.pdf", "application/pdf") }.isFailure)
        }
        assertTrue(runCatching { WorkDocumentInput(byteArrayOf(1), "a.exe", "application/octet-stream") }.isFailure)
        assertEquals(20 * 1024 * 1024, WorkDocumentInput.MAX_BYTES)
        assertTrue(WorkDocumentInput.ALLOWED_MIME_TYPES.containsAll(listOf("text/rtf", "application/rtf", "text/plain", "application/vnd.ms-excel", "application/vnd.ms-powerpoint")))
    }
}
