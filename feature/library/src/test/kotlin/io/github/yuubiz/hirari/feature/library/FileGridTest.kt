package io.github.yuubiz.hirari.feature.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import io.github.yuubiz.hirari.core.model.CoverAspect
import io.github.yuubiz.hirari.core.model.LibrarySort
import io.github.yuubiz.hirari.core.model.LibraryTextSize
import io.github.yuubiz.hirari.core.model.LibraryView
import io.github.yuubiz.hirari.core.model.ThumbSize
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The grid of docs/spec.md section 4.3 laid out with real text metrics. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w480dp-h900dp")
class FileGridTest {
    @get:Rule
    val rule = createComposeRule()

    private val fixture = Fixture()

    private fun row(name: String): FileRow {
        val entry = FolderEntry(name, documentId = name, directory = false, size = 1L, modified = Instant.EPOCH)
        return FileRow(requireNotNull(entry.toItem(showDotFiles = false)), path = name, filterKey = name)
    }

    private fun shown(rows: List<FileRow>, look: ListLook = ListLook(), view: LibraryView = LibraryView.Grid) = FolderContent.Shown(
        rows, rows.size, "", view, look, LibrarySort.Name, foldersFirst = true,
        CoverLayout(fixture.covers, fixture.covers.sizePx(look.thumbSize)), folderCoverPath = null,
    )

    private fun volumes(count: Int) = (1..count).map { "[堀越耕平] 僕のヒーローアカデミア 第%02d巻.zip".format(it) }

    private fun showGrid(rows: List<FileRow>, look: ListLook, width: Int = 360) {
        rule.setContent {
            MaterialTheme { Box(Modifier.size(width.dp, 800.dp)) { FileList(shown(rows, look), reveal = null, onOpen = {}, onLongPress = {}) } }
        }
    }

    private fun columnCount() = rule.onAllNodesWithClick().map { it.boundsInRoot.left }.distinct().size

    @Test
    fun `favorites keep their name and spoken mark in all three views`() {
        var view by mutableStateOf(LibraryView.Grid)
        rule.setContent {
            MaterialTheme {
                FileList(shown(listOf(row("1.cbz").copy(favorite = true)), view = view), reveal = null, onOpen = {}, onLongPress = {})
            }
        }
        for (next in LibraryView.entries) {
            view = next
            rule.onNodeWithText("1.cbz").assertHasClickAction().assert(
                SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "ZIP、お気に入り"),
            )
        }
    }

    @Test
    fun `a cell grows by a line for each name line and for the author line`() {
        val heights = firstItemHeight(
            LibraryView.Grid,
            ListLook(nameLines = 1, noteLine = false), ListLook(nameLines = 1),
            ListLook(nameLines = 2, noteLine = false), ListLook(nameLines = 3, noteLine = false),
            ListLook(nameLines = 2), ListLook(nameLines = 3),
        )
        val (one, oneWithNote, two, three) = heights
        assertTrue(oneWithNote > one, "the author line takes no height")
        val line = two - one
        assertTrue(line > 0f, "a second name line takes no height")
        assertEquals(line, three - two, 0.5f)
        assertEquals(heights[4], heights[5], 0.5f, "the author line lets the name take a third line")
    }

    private fun firstItemHeight(view: LibraryView, vararg looks: ListLook): List<Float> {
        var look by mutableStateOf(looks.first())
        rule.setContent {
            MaterialTheme { Box(Modifier.size(360.dp, 800.dp)) { FileList(shown(volumes(4).map(::row), look, view), reveal = null, onOpen = {}, onLongPress = {}) } }
        }
        return looks.map {
            look = it
            rule.waitForIdle()
            rule.onAllNodesWithClick().first().boundsInRoot.height
        }
    }

    @Test
    fun `a square box makes a grid cell shorter by the height the book box adds`() {
        val (book, square) = firstItemHeight(LibraryView.Grid, ListLook(), ListLook(aspect = CoverAspect.Square))
        val coverWidth = 360f / 4 - 12
        assertEquals(coverWidth / 0.7f - coverWidth, book - square, 1f)
    }

    @Test
    fun `a square box makes a thumbnail list row no taller than a book box`() {
        val (book, square) = firstItemHeight(LibraryView.ThumbnailList, ListLook(thumbSize = ThumbSize.Large), ListLook(thumbSize = ThumbSize.Large, aspect = CoverAspect.Square))
        assertTrue(square < book, "square $square, book $book")
    }

    private fun assertGrowsWithTextSize(view: LibraryView) {
        val (small, medium, large) = firstItemHeight(view, *LibraryTextSize.entries.map { ListLook(textSize = it) }.toTypedArray())
        assertTrue(small < medium && medium < large, "$small, $medium, $large")
    }

    @Test
    fun `a larger text size makes grid cells taller`() = assertGrowsWithTextSize(LibraryView.Grid)

    @Test
    fun `a larger text size makes list rows taller`() = assertGrowsWithTextSize(LibraryView.List)

    @Test
    fun `a portrait window shows the portrait column count`() {
        showGrid(volumes(12).map(::row), ListLook(portraitColumns = 3, landscapeColumns = 6))
        assertEquals(3, columnCount())
    }

    @Test
    @Config(qualifiers = "w900dp-h480dp")
    fun `a landscape window shows the landscape column count`() {
        showGrid(volumes(12).map(::row), ListLook(portraitColumns = 3, landscapeColumns = 6), width = 900)
        assertEquals(6, columnCount())
    }

    @Test
    @Config(qualifiers = "w900dp-h480dp")
    fun `an automatic landscape window fits cells of the thumbnail size`() {
        showGrid(volumes(12).map(::row), ListLook(portraitColumns = 3), width = 900)
        assertEquals(10, columnCount())
    }

    @Test
    fun `a 360 dp phone shows 4 columns, and each cell reads as the whole file name`() {
        val names = (1..8).map { "[堀越耕平] 僕のヒーローアカデミア 第%02d巻.zip".format(it) }
        rule.setContent {
            MaterialTheme { Box(Modifier.size(360.dp, 800.dp)) { FileList(shown(names.map(::row)), reveal = null, onOpen = {}, onLongPress = {}) } }
        }
        val cells = rule.onAllNodesWithClick()
        val lefts = cells.map { it.boundsInRoot.left }.distinct()
        assertEquals(4, lefts.size, "column lefts $lefts")
        rule.onNodeWithText(names.first()).assertHasClickAction()
    }

    @Test
    fun `a long title keeps its volume number within two lines`() {
        var fitted = ""
        var lines = 0
        var overflow = true
        rule.setContent {
            val measurer = rememberTextMeasurer()
            val style = MaterialTheme.typography.bodySmall.copy(textAlign = TextAlign.Center)
            val width = 78 * 2
            val title = "僕のヒーローアカデミア とても長い副題がここに入ります そしてさらに続く 第03巻"
            fitted = TitleFitter(measurer, style, width, lines = 2).fit(title)
            val layout = measurer.measure(fitted, style, maxLines = 2, constraints = Constraints(maxWidth = width))
            lines = layout.lineCount
            overflow = layout.hasVisualOverflow
        }
        rule.waitForIdle()
        assertTrue(fitted.endsWith("第03巻"), fitted)
        assertTrue("…" in fitted, fitted)
        assertTrue(fitted.startsWith("僕の"), fitted)
        assertTrue(lines <= 2, "$lines lines")
        assertFalse(overflow)
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithClick() =
        onAllNodes(hasClickAction()).fetchSemanticsNodes()
}
