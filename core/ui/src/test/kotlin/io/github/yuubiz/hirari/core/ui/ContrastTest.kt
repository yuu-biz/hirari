package io.github.yuubiz.hirari.core.ui

import com.materialkolor.hct.Hct
import io.github.yuubiz.hirari.core.model.ColorPalette
import io.github.yuubiz.hirari.core.model.Rgb
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The contrast of the custom colors (docs/spec.md section 6.7), as WCAG 2.x measures it: (lighter + 0.05) / (darker +
 * 0.05). Covers every preset, custom seeds around the hue circle, and pure black in both modes.
 */
class ContrastTest {
    private fun ratio(a: Color, b: Color): Double {
        val (light, dark) = listOf(a.luminance(), b.luminance()).sortedDescending()
        return (light + 0.05) / (dark + 0.05)
    }

    private class Theme(val name: String, val colors: LibraryColors, val surfaces: List<Color>)

    private val palettes = ColorPalette.presets + (0 until 360 step 30).map { hue ->
        ColorPalette.Custom(Rgb(Hct.from(hue.toDouble(), 80.0, 50.0).toInt() and 0xFFFFFF))
    } + listOf(0x000000, 0xFFFFFF, 0x808080).map { ColorPalette.Custom(Rgb(it)) }

    private val themes = listOf(false, true).flatMap { dark ->
        palettes.flatMap { palette ->
            listOf(false, true).map { black ->
                val scheme = paletteColorScheme(palette, dark).withPureBlack(dark && black)
                Theme("$palette dark=$dark black=$black", if (dark) LibraryColors.Dark else LibraryColors.Light,
                    listOf(scheme.background, scheme.surface, if (dark) Color.Black else Color.White))
            }
        }
    }

    private fun LibraryColors.names() = mapOf(
        "stateUnread" to stateUnread,
        "stateReading" to stateReading,
        "stateRead" to stateRead,
        "stateFolder" to stateFolder,
        "stateImage" to stateImage,
        "itemDetail" to itemDetail,
    )

    @Test
    fun `every name color reads on the surfaces at 4_5 to 1`() {
        val failures = themes.flatMap { theme ->
            theme.colors.names().flatMap { (name, color) ->
                theme.surfaces.mapNotNull { surface ->
                    ratio(color, surface).takeIf { it < 4.5 }?.let { "${theme.name} $name on ${surface.value.toString(16)}: $it" }
                }
            }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    /**
     * 既読 in the dark theme is the spec's gray on the spec's blue: 2.1 to 1. The pressed background is under the finger
     * for a moment, and 既読 is also told by its check and its pale cover. If the colors change so this passes, the
     * exception goes.
     */
    private val belowThreeWhilePressed = setOf("dark stateRead")

    @Test
    fun `a pressed row keeps every name color at 3 to 1 but the known one`() {
        val below = listOf(Theme("dark", LibraryColors.Dark, emptyList()), Theme("light", LibraryColors.Light, emptyList())).flatMap { theme ->
            theme.colors.names().mapNotNull { (name, color) ->
                "${theme.name} $name".takeIf { ratio(color, theme.colors.itemPressed) < 3.0 }
            }
        }.toSet()
        assertEquals(belowThreeWhilePressed, below)
    }
}
