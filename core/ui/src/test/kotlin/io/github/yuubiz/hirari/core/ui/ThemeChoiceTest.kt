package io.github.yuubiz.hirari.core.ui

import androidx.compose.ui.graphics.Color
import com.materialkolor.hct.Hct
import com.materialkolor.scheme.SchemeNeutral
import com.materialkolor.scheme.SchemeTonalSpot
import io.github.yuubiz.hirari.core.model.ColorPalette
import io.github.yuubiz.hirari.core.model.Rgb
import io.github.yuubiz.hirari.core.model.AppTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ThemeChoiceTest {
    @Test
    fun `each preset uses its seed and neutral is reserved for ink`() {
        val seeds = listOf(ColorPalette.Indigo to 0x2E4A7D, ColorPalette.Bamboo to 0x4E8A4B,
            ColorPalette.Sakura to 0xC2607A, ColorPalette.Orange to 0xD9822B, ColorPalette.Ink to 0x5F6368,
            ColorPalette.Custom(Rgb(0xFF0000)) to 0xFF0000)
        for ((palette, seed) in seeds) for (dark in listOf(false, true)) {
            val hct = Hct.fromInt(seed or 0xFF000000.toInt())
            val expected = if (palette == ColorPalette.Ink) SchemeNeutral(hct, dark, 0.0) else SchemeTonalSpot(hct, dark, 0.0)
            val scheme = paletteColorScheme(palette, dark)
            assertEquals(Color(expected.primary), scheme.primary, "$palette dark=$dark")
            assertEquals(Color(expected.onSurface), scheme.onSurface)
            assertEquals(Color(expected.surfaceContainerHighest), scheme.surfaceContainerHighest)
        }
    }

    @Test
    fun `pure black changes every background surface role and keeps foregrounds`() {
        for (palette in ColorPalette.presets) {
            val base = paletteColorScheme(palette, true)
            assertEquals(base, base.withPureBlack(false))
            val black = base.withPureBlack(true)
            for (surface in listOf(black.background, black.surface, black.surfaceVariant, black.surfaceDim,
                black.surfaceBright, black.surfaceContainerLowest, black.surfaceContainerLow, black.surfaceContainer,
                black.surfaceContainerHigh, black.surfaceContainerHighest, black.surfaceTint)) {
                assertEquals(Color.Black, surface)
            }
            assertEquals(base.onSurface, black.onSurface)
            assertEquals(base.onBackground, black.onBackground)
            assertEquals(base.primary, black.primary)
        }
    }

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
        assertTrue(ThemeChoice(colorPalette = io.github.yuubiz.hirari.core.model.ColorPalette.Wallpaper).usesWallpaperColors(31))
        assertFalse(ThemeChoice(colorPalette = io.github.yuubiz.hirari.core.model.ColorPalette.Wallpaper).usesWallpaperColors(30))
        assertFalse(ThemeChoice(colorPalette = io.github.yuubiz.hirari.core.model.ColorPalette.Standard).usesWallpaperColors(36))
    }
}
