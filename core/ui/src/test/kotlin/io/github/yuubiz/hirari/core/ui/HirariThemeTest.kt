package io.github.yuubiz.hirari.core.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import io.github.yuubiz.hirari.core.model.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

/** docs/spec.md section 4.10: the theme follows the setting and changes live. The Robolectric device is in day mode. */
@RunWith(RobolectricTestRunner::class)
class HirariThemeTest {
    @get:Rule
    val rule = createComposeRule()

    private var choice by mutableStateOf(ThemeChoice(AppTheme.System, colorPalette = io.github.yuubiz.hirari.core.model.ColorPalette.Standard))
    private var background = Color.Unspecified
    private var libraryColors = LibraryColors.Light
    private var dark = false

    private fun show() = rule.setContent {
        HirariTheme(choice) { isDark ->
            background = MaterialTheme.colorScheme.background
            libraryColors = LocalLibraryColors.current
            dark = isDark
        }
    }

    @Test
    fun palettesAndPureBlackChangeLiveAndBlackHasNoEffectInLight() {
        show()
        choice = choice.copy(colorPalette = io.github.yuubiz.hirari.core.model.ColorPalette.Sakura)
        rule.runOnIdle {
            assertEquals(paletteColorScheme(choice.colorPalette, false).background, background)
            assertEquals(LibraryColors.Light, libraryColors)
        }
        val light = paletteColorScheme(choice.colorPalette, false).background
        choice = choice.copy(pureBlack = true)
        rule.runOnIdle { assertEquals(light, background) }
        choice = choice.copy(appTheme = AppTheme.Dark)
        rule.runOnIdle {
            assertEquals(Color.Black, background)
            assertEquals(LibraryColors.Dark, libraryColors)
        }
        choice = choice.copy(pureBlack = false)
        rule.runOnIdle { assertEquals(paletteColorScheme(choice.colorPalette, true).background, background) }
    }

    @Test
    fun theSystemChoiceFollowsTheDevice() {
        show()
        rule.runOnIdle {
            assertEquals(lightColorScheme().background, background)
            assertEquals(LibraryColors.Light, libraryColors)
        }
    }

    @Test
    fun darkAndLightOverrideTheDeviceAndChangeWithoutRecreatingTheScreen() {
        show()
        choice = choice.copy(appTheme = AppTheme.Dark)
        rule.runOnIdle {
            assertEquals(true, dark)
            assertEquals(darkColorScheme().background, background)
            assertEquals(LibraryColors.Dark, libraryColors)
        }
        choice = choice.copy(appTheme = AppTheme.Light)
        rule.runOnIdle {
            assertEquals(false, dark)
            assertEquals(lightColorScheme().background, background)
            assertEquals(LibraryColors.Light, libraryColors)
        }
    }
}
