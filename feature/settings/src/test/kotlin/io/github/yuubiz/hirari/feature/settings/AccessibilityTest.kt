package io.github.yuubiz.hirari.feature.settings

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.yuubiz.hirari.core.data.GlobalSettings
import io.github.yuubiz.hirari.core.model.ReaderCommand
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What a screen reader gets from the settings screen (docs/spec.md section 6.7). */
@RunWith(RobolectricTestRunner::class)
class AccessibilityTest {
    @get:Rule
    val rule = createComposeRule()

    private fun hasState(text: String) = SemanticsMatcher("state is $text") { it.config.getOrNull(SemanticsProperties.StateDescription) == text }

    @Test
    fun `a slider is read once, with its name and its value`() {
        val textSize = SettingsTable.filterIsInstance<SliderSetting<*>>().single { it.key == "textSizeSp" }
        rule.setContent { SettingRow(textSize, GlobalSettings(textSizeSp = 20), saveFailures = 0, onChange = {}, onOpen = {}) }
        rule.onNodeWithContentDescription(textSize.label).assert(hasState("20 sp"))
        assertEquals(0, rule.onAllNodes(hasText(textSize.label)).fetchSemanticsNodes().size)
        assertEquals(0, rule.onAllNodes(hasText("20 sp")).fetchSemanticsNodes().size)
    }

    @Test
    fun `a license says whether its text is shown`() {
        val library = LicensedLibrary(id = "a:b", name = "B", version = "1", licenses = listOf("MIT"), texts = listOf("full text"))
        rule.setContent { LicenseItem(library, listOf("full text")) }
        rule.onNodeWithText("B 1").assert(hasState("全文は閉じています"))
        rule.onNodeWithText("B 1").performClick()
        rule.onNodeWithText("B 1").assert(hasState("全文を表示中"))
        rule.onNodeWithText("B 1").assert(SemanticsMatcher("is a button") { it.config.getOrNull(SemanticsProperties.Role) == Role.Button })
    }

    @Test
    fun `the command list is made of labelled buttons and not of arrows`() {
        val setting = SettingsTable.filterIsInstance<OrderedSetSetting>().single()
        val settings = GlobalSettings(commandBarItems = listOf(ReaderCommand.Layout, ReaderCommand.Trim))
        rule.setContent { CommandsDialog(setting, settings, onChange = {}, onClose = {}) }
        val layout = setting.labelOf(ReaderCommand.Layout)
        rule.onNodeWithContentDescription(setting.labelOf(ReaderCommand.Layout) + " を下へ").assertExists()
        rule.onNodeWithContentDescription("$layout を外す").assertExists()
        assertEquals(0, rule.onAllNodes(hasText("↑") or hasText("×") or hasText("＋")).fetchSemanticsNodes().size)
        val add = setting.labelOf(setting.choices.map { it.value }.first { it !in settings.commandBarItems })
        rule.onNodeWithContentDescription("$add を加える").assert(SemanticsMatcher("is a button") { it.config.getOrNull(SemanticsProperties.Role) == Role.Button })
        assertTrue(rule.onAllNodes(SemanticsMatcher("a heading") { it.config.getOrNull(SemanticsProperties.Heading) != null }).fetchSemanticsNodes().size >= 2)
    }
}
