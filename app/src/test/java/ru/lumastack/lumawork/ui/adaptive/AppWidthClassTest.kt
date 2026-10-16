package ru.lumastack.lumawork.ui.adaptive

import org.junit.Assert.assertEquals
import org.junit.Test

class AppWidthClassTest {
    @Test
    fun `short windows keep forms in a single column`() {
        assertEquals(AppWidthClass.Compact, appContentWidthClass(1000, 479))
        assertEquals(AppWidthClass.Expanded, appContentWidthClass(1000, 480))
    }

    @Test
    fun `larger text reserves enough space for both panes`() {
        assertEquals(AppWidthClass.Medium, appContentWidthClass(900, 800, 1.3f))
        assertEquals(AppWidthClass.Expanded, appContentWidthClass(1120, 800, 1.3f))
        assertEquals(AppWidthClass.Compact, appContentWidthClass(700, 800, 1.5f))
    }

    @Test
    fun `content thresholds apply after navigation has taken its width`() {
        assertEquals(AppWidthClass.Medium, appContentWidthClass(800 - 88, 1200))
        assertEquals(AppWidthClass.Expanded, appContentWidthClass(1280 - 272, 800))
    }

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
