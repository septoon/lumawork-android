package ru.lumastack.lumawork.domain.backpack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackpackModelsTest {
    @Test
    fun `groups models sums quantity and sorts newest first`() {
        val groups = backpackGroups(
            listOf(
                item("1", "Pax D200", "SN-1", "10.08.2026", 100L, 1),
                item("2", "PAX D200", "SN-2", "12.08.2026", 300L, 2),
                item("3", "Feitian F20", "SN-3", "11.08.2026", 200L, 1),
            ),
        )

        assertEquals(listOf("PAX D200", "Feitian F20"), groups.map(BackpackItemGroup::name))
        assertEquals(3, groups.first().quantity)
        assertEquals(listOf("SN-2", "SN-1"), groups.first().items.map(BackpackItem::serialNumber))
    }

    @Test
    fun `search is substring based across name serial and date`() {
        val groups = backpackGroups(
            listOf(
                item("1", "Переносной Unitodi MF 960", "ABC-123", "12.08.2026 10:30:00", 1L, 1),
                item("2", "Pax D200", "XYZ-500", "10.08.2026 09:00:00", 2L, 1),
            ),
        )

        assertEquals("Переносной Unitodi MF 960", filterBackpackGroups(groups, "nitod").single().name)
        assertEquals("Переносной Unitodi MF 960", filterBackpackGroups(groups, "c-12").single().name)
        assertEquals("Переносной Unitodi MF 960", filterBackpackGroups(groups, "12.08").single().name)
    }

    @Test
    fun `photo matching supports exact alias and model fingerprint`() {
        val catalog = BackpackPhotoCatalog(
            items = listOf(
                BackpackPhoto(
                    referenceName = "Переносной Unitodi MF 960",
                    aliases = listOf("Переносной POS-терминал MoreFun UniTodi 960 AL"),
                    url = "portable.webp",
                ),
                BackpackPhoto(referenceName = "Pax D200", url = "d200.webp"),
            ),
        )

        assertEquals("portable.webp", catalog.photoFor("Переносной POS-терминал MoreFun UniTodi 960 AL")?.url)
        assertEquals("d200.webp", catalog.photoFor("Терминал PAX D 200")?.url)
        assertTrue(catalog.photoFor("Неизвестное оборудование") == null)
    }
}

private fun item(
    id: String,
    name: String,
    serial: String,
    receivedAt: String,
    receivedAtEpochMillis: Long,
    quantity: Int,
) = BackpackItem(
    id = id,
    name = name,
    serialNumber = serial,
    responsible = "Инженер",
    receivedAt = receivedAt,
    receivedAtEpochMillis = receivedAtEpochMillis,
    quantity = quantity,
)
