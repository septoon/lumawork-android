package ru.lumastack.lumawork.domain.requests

import org.junit.Assert.*
import org.junit.Test
import ru.lumastack.lumawork.domain.simpleone.SimpleOneRequest
import ru.lumastack.lumawork.domain.simpleone.SimpleOneRequestSource

class RequestDetailsDataTest {
    @Test fun `closed request hides SLA even when archived deadlines are present`() {
        val request = SimpleOneRequest("1", "REQ1", source = SimpleOneRequestSource.Closed,
            registeredAt = "23.10.2026 10:00", deadline = "23.10.2026 14:00",
            tableFields = mapOf("Время создания в МК" to "23.10.2026 10:00", "Предельный срок СУТС" to "23.10.2026 14:00"))
        val now = java.time.LocalDateTime.of(2026, 10, 23, 15, 0)
        assertNull(RequestDetailsData(request).sla(now))
        assertNotNull(RequestDetailsData(request.copy(source = SimpleOneRequestSource.Active)).sla(now))
    }

    @Test fun `installation keeps iOS missing model placeholder`() {
        val data = RequestDetailsData(SimpleOneRequest("1", "REQ1"))
        assertEquals("Информация отсутствует", data.sections.first { it.id == "install" }.fields.single().value)
    }

    @Test fun `detail header uses exported dates and iOS SLA text with bounded progress`() {
        val request = SimpleOneRequest("1", "REQ1", registeredAt = "20.10.2026 01:00", deadline = "20.10.2026 02:00",
            tableFields = mapOf("Время создания в МК" to "23.10.2026 10:00", "Предельный срок СУТС" to "23.10.2026 14:00"))
        val data = RequestDetailsData(request)
        assertEquals("23 окт., 14:00", data.deadlineText)
        val now = java.time.LocalDateTime.of(2026, 10, 23, 12, 30)
        assertEquals(RequestDetailSLA(.625f, "Осталось 1 ч. 30 м.", false), data.sla(now))
        assertEquals(RequestDetailSLA(1f, "Просрочено на 0 ч. 30 м.", true), data.sla(now.plusHours(2)))
        assertEquals(0f, data.sla(now.minusHours(4))!!.progress)
        assertNull(RequestDetailsData(request.copy(tableFields = emptyMap(), deadline = request.registeredAt)).sla(now))
    }

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
    @Test fun `return equipment excludes personal comments while installation supports them`() {
        assertFalse(SimpleOneRequest("1", "R", requestType = "returnEquip").supportsClientPersonalComments())
        assertFalse(SimpleOneRequest("1", "R", requestType = "return_equip").supportsClientPersonalComments())
        assertFalse(SimpleOneRequest("1", "R", tableFields = mapOf("Тип заявки" to "Возврат ТО")).supportsClientPersonalComments())
        assertTrue(SimpleOneRequest("2", "I", requestType = "install").supportsClientPersonalComments())
    }
}
