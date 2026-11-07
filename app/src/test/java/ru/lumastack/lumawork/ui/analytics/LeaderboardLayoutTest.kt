package ru.lumastack.lumawork.ui.analytics

import org.junit.Assert.*
import org.junit.Test
import ru.lumastack.lumawork.ui.adaptive.AppWidthClass

class LeaderboardLayoutTest {
    @Test fun tabletUsesSidePanelOnlyWithEnoughTextSpace() {
        assertFalse(leaderboardUsesSidePanel(AppWidthClass.Compact, 412f, 1f))
        assertFalse(leaderboardUsesSidePanel(AppWidthClass.Medium, 720f, 1f))
        assertTrue(leaderboardUsesSidePanel(AppWidthClass.Expanded, 1100f, 1f))
        assertFalse(leaderboardUsesSidePanel(AppWidthClass.Expanded, 1100f, 1.6f))
    }
}
