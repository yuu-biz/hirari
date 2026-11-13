package io.github.yuubiz.hirari.feature.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import io.github.yuubiz.hirari.core.model.BarDisplay
import io.github.yuubiz.hirari.core.model.ReaderBackground
import io.github.yuubiz.hirari.core.model.ReaderCommand
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class ReaderBarsAccessibilityTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun visibleCellsAreButtonsAndInfoAndPageBarsOpenTheirPanels() {
        val config = ReaderConfig(barDisplay = BarDisplay.Always, commandBarItems = listOf(ReaderCommand.Layout, null, ReaderCommand.Trim))
        val state = readerOf(List(4) { TALL }, config, Viewport(411f, 914f, 1f))
        val items = mutableListOf<PanelItem>()
        val controls = mutableListOf<ReaderControl>()
        rule.setContent {
            Box(Modifier.requiredSize(411.dp, 914.dp)) {
                ReaderBars(ReaderBackground.White, "長い本の名前.zip", state, null, { controls += it }, { items += it })
            }
        }
        for (label in listOf("表示", "メニュー", "組み方")) {
            val node = rule.onNodeWithText(label).fetchSemanticsNode()
            assertEquals(Role.Button, node.config.getOrNull(SemanticsProperties.Role))
            rule.onNodeWithText(label).performSemanticsAction(SemanticsActions.OnClick)
        }
        assertEquals(listOf(PanelItem.Command(ReaderCommand.BookSettings), PanelItem.Menu, PanelItem.Command(ReaderCommand.Layout)), items)
        rule.onNodeWithText("閉じる").assertDoesNotExist()
        rule.onNodeWithText("その他").assertDoesNotExist()
        rule.onNodeWithText("長い本の名前.zip", substring = true).performSemanticsAction(SemanticsActions.OnClick)
        // Default overlay positions are Off, yet the visible page bar and the info strip both show the number.
        rule.onNodeWithText("1-2 / 4", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("1-2 / 4").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf(ReaderControl.OpenCommands, ReaderControl.OpenPages), controls)
    }
}
