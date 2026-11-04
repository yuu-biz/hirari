package io.github.yuubiz.hirari.feature.reader

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.dp
import io.github.yuubiz.hirari.core.model.PageShape
import io.github.yuubiz.hirari.core.model.LayoutMode
import io.github.yuubiz.hirari.core.model.ReaderAction
import io.github.yuubiz.hirari.core.model.ReadingDirection
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The strip and the slider of the page panel (docs/spec.md section 4.8, ページバー). */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h800dp")
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

    private val events = mutableListOf<ReaderEvent>()
    private var closed = 0
    private val reader = mutableStateOf(readerOf(shapes).reduce(ReaderEvent.GoTo(20)))

    private fun shiftPanel(mode: LayoutMode = LayoutMode.Spread, width: Int = 360, height: Int = 800) {
        val config = reader.value.config
        reader.value = reader.value.reduce(ReaderEvent.ConfigChanged(config.copy(layout = config.layout.copy(mode = mode))))
            .reduce(ReaderEvent.ViewportChanged(Viewport(width.toFloat(), height.toFloat(), 1f)))
        rule.setContent {
            Scrim("ページ", { closed++ }) {
                Column(Modifier.width(width.dp)) {
                    val state = reader.value
                    PagePanelContent(
                        shapes = state.pagination.shapes,
                        head = state.current.pages.min(),
                        current = state.current.pages,
                        direction = state.config.direction,
                        cellHeight = 160.dp,
                        oneRow = width >= 600,
                        thumbnails = StripThumbnails({ null }, { null }),
                        goTo = { events += ReaderEvent.GoTo(it) },
                        onPick = { closed++ },
                    ) {
                        PagePanelControls(state, 20) {
                            events += it
                            reader.value = reader.value.reduce(it)
                        }
                    }
                }
            }
        }
    }

    private fun shift(forward: Boolean) = rule.onNodeWithContentDescription(if (forward) "組ずらし（後）" else "組ずらし（前）")

    @Test
    fun spreadShiftsFitBesideTheExistingControlsOnANarrowScreen() {
        shiftPanel()
        shift(false).assertIsDisplayed().assertIsEnabled()
        shift(true).assertIsDisplayed().assertIsEnabled()
        val field = rule.onNodeWithContentDescription("ページ番号").fetchSemanticsNode().boundsInRoot
        val undo = rule.onNodeWithText("取り消す").fetchSemanticsNode().boundsInRoot
        val back = shift(false).fetchSemanticsNode().boundsInRoot
        val forward = shift(true).fetchSemanticsNode().boundsInRoot
        val strip = rule.onNodeWithTag(PAGE_STRIP_TAG).fetchSemanticsNode().boundsInRoot
        assertTrue(field.right <= undo.left && undo.right <= back.left && back.right <= forward.left)
        assertTrue(field.left >= strip.left && forward.right <= strip.right)
        assertTrue(abs(back.center.y - field.center.y) < 1f)
    }

    @Test
    fun singlePageLayoutDisablesBothShifts() {
        shiftPanel(LayoutMode.Single)
        shift(false).assertIsNotEnabled()
        shift(true).assertIsNotEnabled()
    }

    @Test
    fun autoInPortraitDisablesBothShifts() {
        shiftPanel(LayoutMode.Auto)
        shift(false).assertIsNotEnabled()
        shift(true).assertIsNotEnabled()
    }

    @Test
    @Config(qualifiers = "w600dp-h360dp")
    fun autoInLandscapeEnablesBothShifts() {
        shiftPanel(LayoutMode.Auto, width = 600, height = 360)
        shift(false).assertIsEnabled()
        shift(true).assertIsEnabled()
    }

    @Test
    fun eitherShiftActsAtOnceAndKeepsThePanelOpenWithTheNewViewCentred() {
        shiftPanel()
        shift(true).performClick()
        rule.waitForIdle()
        assertEquals(ReaderAction.Shift(true), (events.single() as ReaderEvent.Act).action)
        assertEquals(listOf(21, 22), reader.value.current.pages)
        assertInTheMiddle(21)
        shift(false).performClick()
        rule.waitForIdle()
        assertEquals(ReaderAction.Shift(false), (events.last() as ReaderEvent.Act).action)
        assertEquals(listOf(20, 21), reader.value.current.pages)
        assertInTheMiddle(20)
        shift(true).assertIsDisplayed()
        assertEquals(0, closed)
    }

    @Test
    @Config(qualifiers = "w600dp-h800dp")
    fun widePanelsKeepTheSliderAndAllControlsOnOneRow() {
        shiftPanel(width = 600)
        val slider = rule.onNodeWithContentDescription("ページ").fetchSemanticsNode().boundsInRoot
        val forward = shift(true).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(slider.width > 0)
        assertTrue(abs(slider.center.y - forward.center.y) < 1f)
    }
}
