package io.github.yuubiz.hirari.core.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import io.github.yuubiz.hirari.core.model.AppTheme
import io.github.yuubiz.hirari.core.model.ReaderBackground
import io.github.yuubiz.hirari.core.model.Rgb
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals

/** docs/spec.md section 4.10: 背景色 fills the reader around the pages, and a change reaches an open reader. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReaderBackdropTest {
    @get:Rule
    val rule = createComposeRule()

    private var background by mutableStateOf<ReaderBackground>(ReaderBackground.Theme)
    private var theme by mutableStateOf(ThemeChoice(AppTheme.Light, colorPalette = io.github.yuubiz.hirari.core.model.ColorPalette.Standard))

    private fun show() = rule.setContent {
        HirariTheme(theme) { ReaderBackdrop(background, Modifier.fillMaxSize()) {} }
    }

    private fun painted(): Int {
        val image = rule.onRoot().captureToImage()
        val pixels = IntArray(image.width * image.height)
        image.readPixels(pixels)
        return pixels[pixels.size / 2]
    }

    @Test
    fun eachChoiceIsPaintedWithItsColor() {
        show()
        val expected = listOf(
            ReaderBackground.Black to Color.Black,
            ReaderBackground.DarkGray to Color(0xFF616161),
            ReaderBackground.White to Color.White,
            ReaderBackground.Custom(Rgb(0x123ABC)) to Color(0xFF123ABC),
        )
        for ((choice, color) in expected) {
            background = choice
            rule.waitForIdle()
            assertEquals(color.toArgb(), painted(), "$choice")
        }
    }

    @Test
    fun followingTheThemeTakesTheThemeBackgroundAndChangesWithTheTheme() {
        show()
        rule.waitForIdle()
        assertEquals(lightColorScheme().background.toArgb(), painted())
        theme = theme.copy(appTheme = AppTheme.Dark)
        rule.waitForIdle()
        assertEquals(darkColorScheme().background.toArgb(), painted())
    }

    @Test
    fun textSetOnTheBackgroundReadsOnLightAndDark() {
        assertEquals(Color.White, readableOn(Color(0xFF616161)))
        assertEquals(Color.White, readableOn(Color.Black))
        assertEquals(Color.Black, readableOn(Color.White))
        assertEquals(Color.Black, readableOn(lightColorScheme().background))
        assertEquals(Color.White, readableOn(darkColorScheme().background))
    }
}
