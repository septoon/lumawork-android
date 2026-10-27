package ru.lumastack.lumawork.domain.coordination

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.lumastack.lumawork.domain.simpleone.SimpleOneRequest

class CoordinationModelsTest {
    @Test
    fun `engineers are grouped by normalized name with unassigned first`() {
        val requests = listOf(
            request("1", "Иван Инженеров"),
            request("2", "иван инженеров"),
            request("3", "Пётр Петров"),
            request("4", ""),
        )

        val engineers = coordinationEngineers(requests)

        assertTrue(engineers.first().isUnassigned)
        assertEquals(3, engineers.size)
        assertEquals(2, engineers.first { it.name == "Иван Инженеров" }.requestCount)
    }

    @Test fun `engineer identity is unchanged when details append SimpleOne login`() {
        val listed = request("1", "Иван Инженеров")
        val detailed = listed.copy(assignedUser = "Иван Инженеров (Ivanov.II)", assignedUserId = "123")
        assertEquals(listed.coordinationEngineerKey(), detailed.coordinationEngineerKey())
        assertEquals(1, coordinationEngineers(listOf(listed, detailed.copy(sysId = "2"))).size)
        assertTrue(request("3", "Пётр Петров (Petrov.PP)").coordinationEngineerKey() != listed.coordinationEngineerKey())
    }

    @Test
    fun `return equipment accepts system and Russian values`() {
        assertTrue(isReturnEquipmentRequest("returnEquip"))
        assertTrue(isReturnEquipmentRequest("return_equip"))
        assertTrue(isReturnEquipmentRequest("Возврат ТО"))
        assertFalse(isReturnEquipmentRequest("install"))
    }

    @Test
    fun `region ids match iOS contract`() {
        assertEquals("171872459801458049", CoordinationRegion.Alushta.assignmentGroupId)
        assertEquals("171878330004108924", CoordinationRegion.Alushta.companyLocationId)
        assertEquals(9, CoordinationRegion.entries.size)
    }

    private fun request(id: String, assignedUser: String) = SimpleOneRequest(
        sysId = id,
        number = "REQ$id",
        assignedUser = assignedUser,
    )
}
