package io.github.yuubiz.hirari.core.ui

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The contrast of the custom colors (docs/spec.md section 6.7), as WCAG 2.x measures it: (lighter + 0.05) / (darker +
 * 0.05). The file list draws on the theme's background, which the baseline schemes and the extremes of the dynamic
 * ones (pure black and white) bound.
 */
class ContrastTest {
    private fun ratio(a: Color, b: Color): Double {
        val (light, dark) = listOf(a.luminance(), b.luminance()).sortedDescending()
        return (light + 0.05) / (dark + 0.05)
    }

    private class Theme(val name: String, val colors: LibraryColors, val surfaces: List<Color>)

    private val themes = listOf(
        Theme("dark", LibraryColors.Dark, listOf(darkColorScheme().background, darkColorScheme().surface, Color.Black)),
        Theme("light", LibraryColors.Light, listOf(lightColorScheme().background, lightColorScheme().surface, Color.White)),
    )

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
        val below = themes.flatMap { theme ->
            theme.colors.names().mapNotNull { (name, color) ->
                "${theme.name} $name".takeIf { ratio(color, theme.colors.itemPressed) < 3.0 }
            }
        }.toSet()
        assertEquals(belowThreeWhilePressed, below)
    }
}
