package ru.lumastack.lumawork.domain.workschedule

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class WorkScheduleSelectionTest {
    private val journals = listOf(
        WorkScheduleJournal("alushta-9", "1", "Алушта", 2026, 9),
        WorkScheduleJournal("alushta-10", "1", "Алушта", 2026, 10),
        WorkScheduleJournal("yalta-8", "2", "Ялта", 2025, 8),
    )
    @Test fun `initial profile city ignores case but keeps unavailable current month`() {
        val selection = WorkScheduleSelection.initial(journals, " алушта ", LocalDate.of(2026, 11, 1))
        assertEquals(WorkScheduleSelectionState("Алушта", 2026, 11), selection)
        assertNull(WorkScheduleSelection.journal(journals, selection))
        assertEquals("Алушта", WorkScheduleSelection.initial(journals, "Севастополь", LocalDate.of(2026, 11, 1)).cityName)
        assertEquals("", WorkScheduleSelection.initial(emptyList(), "Севастополь", LocalDate.now()).cityName)
    }
    @Test fun `city change selects actual periods latest available month and year`() {
        assertEquals(listOf("Алушта", "Ялта"), WorkScheduleSelection.cities(journals))
        assertEquals(listOf(9,10), WorkScheduleSelection.months(journals, "Алушта", 2026))
        assertEquals(WorkScheduleSelectionState("Ялта", 2025, 8), WorkScheduleSelection.selectCity(journals, WorkScheduleSelectionState("Алушта", 2026, 9), "Ялта"))
    }
    @Test fun `current month scroll begins day before today and historical month at day one`() {
        assertEquals(26, WorkScheduleViewport.initialVisibleDay(journals[0], LocalDate.of(2026, 9, 27)))
        assertEquals(1, WorkScheduleViewport.initialVisibleDay(journals[0], LocalDate.of(2026, 9, 1)))
        assertEquals(1, WorkScheduleViewport.initialVisibleDay(journals[1], LocalDate.of(2026, 9, 27)))
        assertNull(WorkScheduleViewport.currentDay(journals[1], LocalDate.of(2026, 9, 27)))
    }
}
