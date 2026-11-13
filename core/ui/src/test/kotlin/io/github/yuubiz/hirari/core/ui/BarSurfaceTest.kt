package io.github.yuubiz.hirari.core.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** docs/spec.md section 4.8, バーを出す表示: the bar surface stands apart from the background and its text still reads. */
class BarSurfaceTest {
    private val backgrounds = (0..255).map { Color(it, it, it) } + listOf(
        Color(0xFF616161), Color(0xFF123ABC), Color(0xFFFFEB3B), Color(0xFF00FF00), Color(0xFFFF0000),
        Color(0xFF7F7F7F), Color(0xFFB4B4B4), Color(0xFF0A0A1E), Color(0xFFFFF8E1),
    )

    private fun contrast(a: Color, b: Color): Float {
        val (light, dark) = listOf(a.luminance(), b.luminance()).sortedDescending()
        return (light + 0.05f) / (dark + 0.05f)
    }

    @Test
    fun textOnTheSurfaceReadsOnEveryBackground() {
        for (background in backgrounds) {
            val ratio = contrast(readableOn(background), barSurface(background))
            assertTrue(ratio >= 4.5f, "text on the bar over $background has contrast $ratio")
        }
    }

    @Test
    fun theSurfaceDiffersFromTheBackground() {
        for (background in backgrounds) assertNotEquals(background, barSurface(background), "the bar over $background")
    }

    @Test
    fun darkBackgroundsDarkenAndNearBlackLightens() {
        assertTrue(barSurface(Color(0xFF616161)).luminance() < Color(0xFF616161).luminance())
        assertTrue(barSurface(Color.White).luminance() < 1f)
        assertTrue(barSurface(Color.Black).luminance() > 0f)
    }
}
