package io.github.yuubiz.hirari.core.ui

import io.github.yuubiz.hirari.core.model.AppTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ThemeChoiceTest {
    @Test
    fun `the system theme follows the device and the other two ignore it`() {
        for (systemDark in listOf(false, true)) {
            assertEquals(systemDark, ThemeChoice(AppTheme.System).isDark(systemDark))
            assertFalse(ThemeChoice(AppTheme.Light).isDark(systemDark))
            assertTrue(ThemeChoice(AppTheme.Dark).isDark(systemDark))
        }
    }

    @Test
    fun `wallpaper colors need the setting and Android 12`() {
        assertTrue(ThemeChoice(dynamicColor = true).usesWallpaperColors(31))
        assertFalse(ThemeChoice(dynamicColor = true).usesWallpaperColors(30))
        assertFalse(ThemeChoice(dynamicColor = false).usesWallpaperColors(36))
    }
}
