package io.github.yuubiz.hirari.feature.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import io.github.yuubiz.hirari.core.model.PageView
import io.github.yuubiz.hirari.core.model.ReaderAction
import io.github.yuubiz.hirari.core.model.Turn
import io.github.yuubiz.hirari.core.ui.BarSemantics
import io.github.yuubiz.hirari.core.ui.blockTouches
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What a screen reader gets from the image reader (docs/spec.md section 6.7). */
@RunWith(RobolectricTestRunner::class)
class AccessibilityTest {
    @get:Rule
    val rule = createComposeRule()

    private val state = readerOf(List(4) { TALL })

    @Test
    fun theControlsMapToTheTurnsOfTheTapZonesAndKeys() {
        assertEquals(ControlEffect.Act(ReaderAction.TurnView(Turn.Forward)), ReaderControl.NextPage.effectIn(state))
        assertEquals(ControlEffect.Act(ReaderAction.TurnView(Turn.Back)), ReaderControl.PreviousPage.effectIn(state))
        assertEquals(ControlEffect.Act(ReaderAction.First), ReaderControl.FirstPage.effectIn(state))
        assertEquals(ControlEffect.Act(ReaderAction.Last), ReaderControl.LastPage.effectIn(state))
        assertEquals(ControlEffect.Menu, ReaderControl.OpenMenu.effectIn(state))
        assertEquals(ControlEffect.Commands, ReaderControl.OpenCommands.effectIn(state))
        assertEquals(ControlEffect.Pages(openedOn = 0), ReaderControl.OpenPages.effectIn(state))
    }

    @Test
    fun anyControlStopsAutoPlayAndDoesNothingElse() {
        val playing = state.reduce(ReaderEvent.AutoPlayStarted(0))
        for (control in ReaderControl.entries) assertEquals(ControlEffect.StopAutoPlay, control.effectIn(playing))
    }

    @Test
    fun aPairReadsItsTwoPagesAndAnEdgeViewItsOne() {
        assertEquals(PageSpan(1, 2, 4), pageSpan(PageView.Two(0, 1), 4))
        assertEquals(PageSpan(4, 4, 4), pageSpan(PageView.One(3), 4))
    }

    @Test
    fun thePageIsOneNodeThatReadsItsNumberAndDoublesTapAsNext() {
        val called = mutableListOf<ReaderControl>()
        rule.setContent { Box(Modifier.size(100.dp).pageAccessibility(state) { called += it }) }
        val node = rule.onNodeWithContentDescription("1 から 2 ページ目、全 4 ページ").fetchSemanticsNode()
        assertEquals(LiveRegionMode.Polite, node.config.getOrNull(SemanticsProperties.LiveRegion))
        assertEquals("次のページ", node.config.getOrNull(SemanticsActions.OnClick)?.label)
        val labels = node.config.getOrNull(SemanticsActions.CustomActions).orEmpty().map { it.label }
        assertEquals(listOf("前のページ", "最初のページ", "最後のページ", "ページパネルを開く", "コマンドバーを開く", "メニューを開く"), labels)
        rule.onNodeWithContentDescription("1 から 2 ページ目、全 4 ページ").performSemanticsAction(SemanticsActions.OnClick)
        node.config.getOrNull(SemanticsActions.CustomActions)!!.first { it.label == "メニューを開く" }.action()
        assertEquals(listOf(ReaderControl.NextPage, ReaderControl.OpenMenu), called)
    }

    @Test
    fun theBarsAreTwoButtonsThatOpenWhatTheyOpen() {
        val called = mutableListOf<String>()
        rule.setContent {
            BarSemantics(60f, "コマンドバー", "ページバー", "コマンドバーを開く", "ページパネルを開く", { called += "commands" }, { called += "pages" })
        }
        rule.onNodeWithContentDescription("コマンドバー").performSemanticsAction(SemanticsActions.OnClick)
        rule.onNodeWithContentDescription("ページバー").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf("commands", "pages"), called)
    }

    @Test
    fun aSheetIsAPaneWhoseControlsStaySeparateAndWhoseScrimIsAButton() {
        var closed = 0
        var picked = 0
        rule.setContent {
            Scrim("メニュー", { closed++ }) {
                Box(Modifier.size(200.dp).blockTouches()) {
                    TextButton(onClick = { picked++ }) { Text("位置を戻して閉じる") }
                }
            }
        }
        val pane = rule.onNodeWithContentDescription("閉じる").fetchSemanticsNode()
        assertTrue(pane.config.getOrNull(SemanticsActions.OnClick) != null)
        rule.onNodeWithText("位置を戻して閉じる").performClick()
        assertEquals(1, picked)
        assertEquals(0, closed)
        rule.onNodeWithContentDescription("閉じる").performClick()
        assertEquals(1, closed)
        assertEquals(2, rule.onAllNodes(hasClickAction()).fetchSemanticsNodes().size)
        rule.onAllNodes(SemanticsMatcher("a pane titled メニュー") { it.config.getOrNull(SemanticsProperties.PaneTitle) == "メニュー" })
            .fetchSemanticsNodes().also { assertEquals(1, it.size) }
        rule.onNode(hasText("位置を戻して閉じる")).assertExists()
    }

    private fun ratio(a: Color, b: Color): Double {
        val (light, dark) = listOf(a.luminance(), b.luminance()).sortedDescending()
        return (light + 0.05) / (dark + 0.05)
    }

    @Test
    fun theSheetTextsReadOnTheSheetOverAWhitePage() {
        val onWhite = SheetBackground.compositeOver(Color.White)
        assertTrue(ratio(SheetText, onWhite) >= 4.5, "white text: ${ratio(SheetText, onWhite)}")
        assertTrue(ratio(SheetDisabled, onWhite) >= 4.9, "secondary text: ${ratio(SheetDisabled, onWhite)}")
        assertTrue(ratio(SheetMark, onWhite) >= 4.5, "the page panel's mark: ${ratio(SheetMark, onWhite)}")
        assertTrue(ratio(Color(0xFF8A8A90), onWhite) < 4.5, "the former secondary color fell short")
    }
}
