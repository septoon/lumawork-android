package ru.lumastack.lumawork.data.analytics

import org.junit.Assert.*
import org.junit.Test
import ru.lumastack.lumawork.domain.analytics.*
import ru.lumastack.lumawork.domain.simpleone.*

class WorkStatisticsSourceIndexTest {
    private fun row(id: String = "id1", number: String = "REQ1", date: String = "31.10.2026 23:00", type: String = "portable_pos") =
        SimpleOneRequest(id, number, requestType = "install", closureCode = "Решено с выездом",
            additionalInformation = "Тип устанавливаемого ТО: $type",
            rankingDates = RankingDates(RankingDateContext.MoscowDisplay, completedAt = date))
    @Test fun baselineDeduplicationReplacementAndTombstone() {
        val old = WorkSourceIndex.baseline("owner", "so", "session", listOf(row(), row()), "2026-11-07T09:00:00Z")
        assertEquals(1, old.aggregate().first.single().metrics.closedCount)
        val change = ClosedRequestsSyncChange("id1", "REQ1", ClosedRequestsSyncCursor("v2", "id1"), row(date = "01.11.2026 10:00", type = "stat_pos"))
        val updated = old.apply(listOf(change))
        assertEquals("2026-11", updated.aggregate().first.single().month)
        assertEquals(1, updated.aggregate().first.single().metrics.byCategory.getValue("stationary").getValue("install"))
        assertEquals(updated, updated.apply(listOf(change)))
        assertTrue(updated.apply(listOf(change.copy(request = null))).aggregate().first.isEmpty())
        assertTrue(WorkSourceIndex.baseline("owner", "so", "session", emptyList(), "2026-11-07T09:00:00Z").aggregate().first.isEmpty())
    }
    @Test fun conflictingNumberOrSysIdIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { WorkSourceIndex.baseline("a", "s", "f", listOf(row(), row(id = "id2")), "2026-11-07T09:00:00Z") }
        assertThrows(IllegalArgumentException::class.java) { WorkSourceIndex.baseline("a", "s", "f", listOf(row(), row(number = "REQ2")), "2026-11-07T09:00:00Z") }
    }
    @Test fun xlsxIdentityCanBeUpgradedAndThenExcluded() {
        val index = WorkSourceIndex.baseline("a", "s", "f", listOf(row(id = "")), "2026-11-07T09:00:00Z")
        val changed = index.apply(listOf(ClosedRequestsSyncChange("id1", "REQ1", ClosedRequestsSyncCursor("v", "id1"), row())))
        assertEquals(1, changed.entries.size)
        assertTrue(changed.apply(listOf(ClosedRequestsSyncChange("id1", "REQ1", ClosedRequestsSyncCursor("v2", "id1"), null))).entries.isEmpty())
    }
    @Test fun dirtyOrOtherIdentityRequiresAuthoritativeBaseline() {
        val i = WorkSourceIndex.baseline("a", "s", "f", listOf(row()), "2026-11-07T09:00:00Z")
        assertFalse(WorkStatisticsDiskState(index = i).requiresBaseline("a", "s", "f", 1794042000000))
        assertTrue(WorkStatisticsDiskState(index = i, dirty = true).requiresBaseline("a", "s", "f", 1794042000000))
        assertTrue(WorkStatisticsDiskState(index = i).requiresBaseline("b", "s", "f", 1794042000000))
        assertTrue(WorkStatisticsDiskState(index = i).requiresBaseline("a", "other", "f", 1794042000000))
    }
}
