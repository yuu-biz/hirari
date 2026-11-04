package io.github.yuubiz.hirari.feature.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import io.github.yuubiz.hirari.core.model.LibrarySort
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

    private fun shown(rows: List<FileRow>) = FolderContent.Shown(
        rows, rows.size, "", LibraryView.Grid, ThumbSize.Medium, LibrarySort.Name, foldersFirst = true,
        CoverLayout(fixture.covers, fixture.covers.sizePx(ThumbSize.Medium)), folderCoverPath = null,
    )

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
            fitted = TitleFitter(measurer, style, width).fit(title)
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
