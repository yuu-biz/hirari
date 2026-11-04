package io.github.yuubiz.hirari.feature.reader

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.dp
import io.github.yuubiz.hirari.core.model.PageShape
import io.github.yuubiz.hirari.core.model.ReadingDirection
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The strip and the slider of the page panel (docs/spec.md section 4.8, ページバー). */
@RunWith(RobolectricTestRunner::class)
class PagePanelTest {
    @get:Rule
    val rule = createComposeRule()

    private val shapes = List(50) { PageShape.Known(1000, 1414) }
    private val head = mutableIntStateOf(20)
    private val moved = mutableListOf<Int>()
    private val picked = mutableListOf<Int>()

    private fun panel(direction: ReadingDirection = ReadingDirection.LeftToRight) = rule.setContent {
        Column(Modifier.width(400.dp)) {
            PagePanelContent(
                shapes = shapes,
                head = head.intValue,
                current = setOf(head.intValue),
                direction = direction,
                cellHeight = 160.dp,
                oneRow = false,
                thumbnails = StripThumbnails({ null }, { null }),
                goTo = { moved += it },
                onPick = { picked += it },
            ) {}
        }
    }

    private fun cell(page: Int) = rule.onNodeWithContentDescription("${page + 1} ページ")

    private val SemanticsNodeInteraction.middleX: Float get() = fetchSemanticsNode().boundsInRoot.center.x

    private fun assertInTheMiddle(page: Int) {
        val strip = rule.onNodeWithTag(PAGE_STRIP_TAG).middleX
        assertTrue(abs(cell(page).middleX - strip) < 1f, "page ${page + 1} is at ${cell(page).middleX}, the strip's middle at $strip")
    }

    private fun sliderSays(): String? =
        rule.onNodeWithContentDescription("ページ").fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription)

    @Test
    fun thePanelOpensWithThePageOnScreenInTheMiddle() {
        panel()
        assertInTheMiddle(20)
        assertEquals("21 ページ目、全 50 ページ", sliderSays())
    }

    @Test
    fun aRightToLeftBookRunsTheStripFromTheRight() {
        panel(ReadingDirection.RightToLeft)
        assertInTheMiddle(20)
        assertTrue(cell(21).middleX < cell(20).middleX, "the later page is to the left")
    }

    @Test
    fun aLeftToRightBookRunsTheStripFromTheLeft() {
        panel(ReadingDirection.LeftToRight)
        assertTrue(cell(21).middleX > cell(20).middleX, "the later page is to the right")
    }

    @Test
    fun scrollingTheStripMovesTheSliderAndLeavesTheReaderWhereItIs() {
        panel()
        rule.onNodeWithTag(PAGE_STRIP_TAG).performScrollToIndex(30)
        rule.waitForIdle()
        assertInTheMiddle(30)
        assertEquals("31 ページ目、全 50 ページ", sliderSays())
        assertEquals(emptyList(), moved)
        assertEquals(emptyList(), picked)
    }

    @Test
    fun theStripFollowsTheReaderToItsNewPage() {
        panel()
        head.intValue = 40
        rule.waitForIdle()
        assertInTheMiddle(40)
        assertEquals("41 ページ目、全 50 ページ", sliderSays())
    }

    @Test
    fun aTapOnACellPicksItsPage() {
        panel()
        cell(21).performClick()
        assertEquals(listOf(21), picked)
    }
}
