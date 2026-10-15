package ru.lumastack.lumawork.ui.analytics

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test
import ru.lumastack.lumawork.domain.simpleone.ClosedSimpleOneArchive
import ru.lumastack.lumawork.domain.simpleone.SimpleOneRequest
import ru.lumastack.lumawork.domain.simpleone.SimpleOneRequestSource
import ru.lumastack.lumawork.domain.simpleone.SimpleOneSnapshot
import ru.lumastack.lumawork.ui.simpleone.SimpleOneUiState

class AnalyticsTest {
    @Test fun `analytics counts deadlines statuses months and groups`() {
        val state = SimpleOneUiState(
            snapshot = SimpleOneSnapshot(activeRequests = listOf(
                SimpleOneRequest("1", "SO1", state = "В работе", deadline = "2026-08-11 10:00:00", assignmentGroup = "Крым"),
                SimpleOneRequest("2", "SO2", state = "В ожидании", deadline = "2026-08-12 18:00:00", assignmentGroup = "Крым"),
            )),
            closedArchive = ClosedSimpleOneArchive(requests = listOf(
                SimpleOneRequest("3", "SO3", source = SimpleOneRequestSource.Closed, closedAt = "12.07.2026 15:00:00", assignmentGroup = "Юг"),
            )),
        )

        val value = buildRequestAnalytics(state, LocalDate.of(2026, 8, 12))

        assertEquals(2, value.active)
        assertEquals(1, value.closed)
        assertEquals(1, value.overdue)
        assertEquals(1, value.dueToday)
        assertEquals("2026-07" to 1, value.byMonth.single())
        assertEquals("Крым" to 2, value.topGroups.first())
    }
}
