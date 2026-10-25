package ru.lumastack.lumawork.domain.requests

import org.junit.Assert.*
import org.junit.Test
import ru.lumastack.lumawork.domain.simpleone.SimpleOneRequest

class TerminalRequestHistoryTest {
    @Test fun `history matches whole terminal preserves zeros excludes current and sorts parsed dates`() {
        val records = listOf(
            request("current", "00123", "2026-10-25 10:00:00"),
            request("older", "00123", "30.09.2026 12:00"),
            request("newer", "00123", "2026-10-01 10:00:00"),
            request("newer", "00123", "2026-10-01 10:00:00"),
            request("substring", "001234", "2026-10-24 10:00:00"),
            request("zeros", "123", "2026-10-24 10:00:00"),
            request("multiple", "RR009; 00123", "2026-10-02 10:00:00"),
            request("undated", "00123", "1970-01-01 00:00:00"),
        )
        assertEquals(listOf("multiple", "newer", "older", "undated"), terminalRequestHistory(records, "00123", "current").map { it.id })
        assertEquals("RR009", normalizeHistoryTerminalId(" rr 009\n"))
        assertEquals("", normalizeHistoryTerminalId("00123^ORsys_idISNOTEMPTY"))
        assertTrue(terminalRequestHistory(records, "").isEmpty())
    }

    @Test fun `history closure differs from visit-only analytics and empty type stays empty`() {
        val request = request("test", "Y0310530", "").copy(tableFields = mapOf("Тип заявки" to "reservationSP"))
        for (code in listOf("Решено", "Решено без выезда", "Решено с выездом. Без использования ТМЦ")) {
            assertEquals("Выполнена", terminalHistoryStatus(request.copy(closureCode = code)))
        }
        assertEquals("Отказ", terminalHistoryStatus(request.copy(closureCode = "Отказ ТСП от обслуживания")))
        assertEquals("Нет данных", terminalHistoryStatus(request))
        assertEquals("Не указан", terminalHistoryType(request))
        assertEquals("Сервисная", terminalHistoryType(request.copy(requestType = "serviceStd")))
        assertEquals("Экспертиза", terminalHistoryType(request.copy(requestType = "expertise")))
    }

    @Test fun `return and expertise filters are independent and show all by default`() {
        val records = listOf("serviceStd", "returnEquip", "expertise").map { request(it, "T1", "").copy(requestType = it) }
        assertEquals(3, terminalRequestHistory(records, "T1").size)
        assertEquals(setOf("serviceStd", "expertise"), terminalRequestHistory(records, "T1", showsReturnEquipment = false).map { it.id }.toSet())
        assertEquals(setOf("serviceStd", "returnEquip"), terminalRequestHistory(records, "T1", showsExpertise = false).map { it.id }.toSet())
        assertEquals(listOf("serviceStd"), terminalRequestHistory(records, "T1", showsReturnEquipment = false, showsExpertise = false).map { it.id })
    }

    private fun request(id: String, terminal: String, date: String) = SimpleOneRequest(sysId = id, number = "REQ-$id", terminalId = terminal, resolvedAt = date)
}
