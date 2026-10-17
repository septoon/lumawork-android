package ru.lumastack.lumawork.domain.requests

import org.junit.Assert.*
import org.junit.Test
import ru.lumastack.lumawork.domain.simpleone.SimpleOneRequest

class RequestDetailsDataTest {
    @Test fun `iOS detail sections parse embedded information and only show dismantle for matching types`() {
        val request = SimpleOneRequest("1", "REQ1", requestType = "replacement", customer = "OOO Клиент",
            description = "ИНН ТСП: 1234567890<br>Тип терминала: fiscal_pos<br>Модель устанавливаемого ТО: Касса<br>Серийный номер демонтируемого ТО: 000011",
            tableFields = mapOf("Оборудование POS" to "000022", "Решение" to "Готово"))
        val data = RequestDetailsData(request)
        assertEquals("1234567890", data.tin)
        assertTrue(data.showsChatQuestions)
        assertEquals(listOf("task", "resolution", "warehouse", "client", "install", "dismantle", "equipment"), data.sections.map { it.id })
        assertTrue(data.managerMessage.contains("ООО Клиент"))
        assertTrue(data.kaAndPinMessage.contains("S/N: 000022"))
        assertEquals("1234567890", request.clientCommentTIN())
        assertFalse(RequestDetailsData(request.copy(requestType = "install")).sections.any { it.id == "dismantle" })
    }
    @Test fun `return equipment never matches personal comments`() {
        assertFalse(SimpleOneRequest("1", "R", requestType = "returnEquip").supportsClientPersonalComments())
    }
}
