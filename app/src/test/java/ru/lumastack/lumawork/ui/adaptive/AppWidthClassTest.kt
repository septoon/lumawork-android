package ru.lumastack.lumawork.ui.adaptive

import org.junit.Assert.assertEquals
import org.junit.Test

class AppWidthClassTest {
    @Test
    fun `phone widths are compact`() {
        assertEquals(AppWidthClass.Compact, appWidthClass(320))
        assertEquals(AppWidthClass.Compact, appWidthClass(599))
    }

    @Test
    fun `portrait tablet widths are medium`() {
        assertEquals(AppWidthClass.Medium, appWidthClass(600))
        assertEquals(AppWidthClass.Medium, appWidthClass(839))
    }

    @Test
    fun `wide windows are expanded`() {
        assertEquals(AppWidthClass.Expanded, appWidthClass(840))
        assertEquals(AppWidthClass.Expanded, appWidthClass(1200))
    }

    @Test
    fun `navigation changes independently from content width class`() {
        assertEquals(AppNavigationLayout.Drawer, appNavigationLayout(599))
        assertEquals(AppNavigationLayout.Rail, appNavigationLayout(600))
        assertEquals(AppNavigationLayout.Rail, appNavigationLayout(1099))
        assertEquals(AppNavigationLayout.Sidebar, appNavigationLayout(1100))
    }
}
