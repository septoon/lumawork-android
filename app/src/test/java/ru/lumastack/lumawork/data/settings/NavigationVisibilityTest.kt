package ru.lumastack.lumawork.data.settings

import org.junit.Assert.*
import org.junit.Test

class NavigationVisibilityTest {
    @Test fun `hiding last accessible screen is rejected without changing saved visibility`() {
        val settings = NavigationVisibility(setOf("Home", "Fuel"))
        val available = listOf("Home", "Fuel", "Requests")
        assertEquals(listOf("Requests"), settings.visible(available))
        assertFalse(settings.canDisable("Requests", available))
        assertSame(settings, settings.setEnabled("Requests", false, available))
        assertEquals(setOf("Home", "Fuel"), settings.setEnabled("Requests", false, available).disabled)
    }
    @Test fun `role change recovers first allowed screen and user can enable another`() {
        val settings = NavigationVisibility(setOf("Home", "Fuel", "Requests"))
        assertEquals(listOf("Admin"), settings.visible(listOf("Home", "Fuel", "Requests", "Admin")))
        val standard = listOf("Home", "Fuel", "Requests")
        assertEquals(listOf("Home"), settings.visible(standard))
        assertEquals(listOf("Fuel"), settings.setEnabled("Fuel", true, standard).visible(standard))
        assertSame(settings, settings.setEnabled("Admin", true, standard))
    }
    @Test fun `hiding a section preserves order and re enabling restores it in place`() {
        val all = listOf("Home", "Requests", "Fuel")
        val hidden = NavigationVisibility().setEnabled("Requests", false, all)
        assertEquals(listOf("Home", "Fuel"), hidden.visible(all))
        assertEquals(all, hidden.setEnabled("Requests", true, all).visible(all))
    }
}
