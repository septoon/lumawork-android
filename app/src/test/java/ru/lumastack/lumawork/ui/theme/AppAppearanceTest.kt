package ru.lumastack.lumawork.ui.theme

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppAppearanceTest {
    @Test
    fun `explicit appearance overrides the system`() {
        assertFalse(AppAppearance.Light.usesDarkTheme(systemDark = true))
        assertTrue(AppAppearance.Dark.usesDarkTheme(systemDark = false))
    }

    @Test
    fun `system appearance follows either system setting`() {
        assertTrue(AppAppearance.System.usesDarkTheme(systemDark = true))
        assertFalse(AppAppearance.System.usesDarkTheme(systemDark = false))
    }
}
