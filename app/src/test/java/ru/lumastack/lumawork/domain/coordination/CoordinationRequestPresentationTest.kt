package ru.lumastack.lumawork.domain.coordination

import org.junit.Assert.*
import org.junit.Test
import ru.lumastack.lumawork.domain.simpleone.SimpleOneRequest

class CoordinationRequestPresentationTest {
    @Test fun `native SO state does not substitute missing multicard status`() {
        val request = SimpleOneRequest("1", "REQ1", state = "Назначена", requestType = "install")
        assertEquals("Статус недоступен", CoordinationRequestPresentation(request).status)
        assertEquals("Загружаю статус", CoordinationRequestPresentation(request, loading = true).status)
        assertEquals("Назначена", CoordinationRequestPresentation(request, usesMulticardStatus = false).status)
        assertNull(CoordinationRequestPresentation(request.copy(requestType = "Экспертиза")).status)
    }
    @Test fun `return equipment serial fallback and localized status match iOS`() {
        val request = SimpleOneRequest("1", "REQ1", requestType = "returnEquip", multicardState = "store_equip_issued",
            tableFields = mapOf("Оборудование POS" to "serial"))
        assertEquals("serial", CoordinationRequestPresentation(request).dismantledSerial)
        assertEquals("Оборудование выдано", CoordinationRequestPresentation(request).status)
    }
    @Test fun `duplicate request number and placeholder dates are not rendered as description or date`() {
        val request = SimpleOneRequest("1", "REQ1", shortDescription = "SUTS-12", deadline = "1970-01-01 00:00:00", registeredAt = "0")
        val display = CoordinationRequestPresentation(request)
        assertEquals("SUTS-12", display.primaryNumber); assertEquals("REQ1", display.secondaryNumber)
        assertNull(display.description); assertNull(display.deadline); assertNull(display.registeredAt)
    }
}
