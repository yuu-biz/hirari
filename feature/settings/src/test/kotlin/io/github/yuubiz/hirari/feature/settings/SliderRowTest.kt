package io.github.yuubiz.hirari.feature.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performSemanticsAction
import io.github.yuubiz.hirari.core.data.GlobalSettings
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class SliderRowTest {
    @get:Rule
    val rule = createComposeRule()

    private val textSize = SettingsTable.filterIsInstance<SliderSetting<*>>().single { it.key == "textSizeSp" }
    private val tick37 = (GlobalSettings.TEXT_SIZE_SP.toList()).indexOf(37)

    private var settings by mutableStateOf(GlobalSettings(textSizeSp = 20))
    private var saveFailures by mutableIntStateOf(0)
    private val saved = mutableListOf<GlobalSettings>()

    private fun show() = rule.setContent {
        SettingRow(
            textSize,
            settings,
            saveFailures,
            onChange = { transform -> saved += transform(settings) },
            onOpen = {},
        )
    }

    private fun slider() = rule.onNodeWithContentDescription(textSize.label)

    private fun drag(position: Float) {
        slider().performSemanticsAction(SemanticsActions.SetProgress) { it(position) }
    }

    private fun shows(text: String) = SemanticsMatcher("state is $text") { it.config.getOrNull(SemanticsProperties.StateDescription) == text }

    @Test
    fun `a position a hair under a tick saves that tick`() {
        show()
        drag(Math.nextDown(tick37.toFloat()))
        assertEquals(listOf(37), saved.map { it.textSizeSp })
        slider().assert(shows("37 sp"))
    }

    @Test
    fun `after a save that failed the slider shows the stored value again`() {
        show()
        drag(tick37.toFloat())
        slider().assert(shows("37 sp"))
        saveFailures++
        rule.waitForIdle()
        slider().assert(shows("20 sp"))
    }

    @Test
    fun `a saved value stays shown`() {
        show()
        drag(tick37.toFloat())
        settings = saved.single()
        rule.waitForIdle()
        slider().assert(shows("37 sp"))
    }
}
