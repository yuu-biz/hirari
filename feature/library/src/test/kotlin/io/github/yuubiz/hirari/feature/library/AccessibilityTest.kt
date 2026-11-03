package io.github.yuubiz.hirari.feature.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import io.github.yuubiz.hirari.core.ui.GlyphButton
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What a screen reader gets from the library's controls (docs/spec.md section 6.7). */
@RunWith(RobolectricTestRunner::class)
class AccessibilityTest {
    @get:Rule
    val rule = createComposeRule()

    private fun row(name: String, directory: Boolean = false, mark: ReadMark? = null): FileRow {
        val entry = FolderEntry(name, documentId = name, directory = directory, size = 1L, modified = Instant.EPOCH)
        return FileRow(requireNotNull(entry.toItem(showDotFiles = false)), path = name, filterKey = name, mark = mark)
    }

    private fun stateOf(row: FileRow): String? {
        rule.setContent {
            Box(Modifier.rowPress({}, {}).rowState(row).size(80.dp)) { Text(row.item.entry.name) }
        }
        return rule.onNode(hasClickAction()).fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription)
    }

    @Test
    fun `a book in progress reads its kind and how far it is`() {
        assertEquals("ZIP、読みかけ 35%", stateOf(row("a.zip", mark = ReadMark.Reading(35))))
    }

    @Test
    fun `a read book and an unread text read their marks in words`() {
        assertEquals("ZIP、既読", stateOf(row("a.cbz", mark = ReadMark.Read)))
    }

    @Test
    fun `a text reads as a text and a folder as a folder`() {
        assertEquals("テキスト、未読", stateOf(row("a.txt", mark = ReadMark.Unread)))
    }

    @Test
    fun `a folder says it is a folder`() {
        assertEquals("フォルダ", stateOf(row("series", directory = true)))
    }

    @Test
    fun `a single image has nothing to add to its name`() {
        assertEquals(null, stateOf(row("a.png")))
    }

    @Test
    fun `a row with a state keeps its own text, so the screen reader reads the title`() {
        rule.setContent {
            Box(Modifier.rowPress({}, {}).rowState(row("a.zip", mark = ReadMark.Reading(35))).size(80.dp)) { Text("title") }
        }
        val config = rule.onNode(hasClickAction()).fetchSemanticsNode().config
        assertEquals(listOf("title"), config.getOrNull(SemanticsProperties.Text)?.map { it.text })
        assertEquals(null, config.getOrNull(SemanticsProperties.ContentDescription))
    }

    @Test
    fun `a row lists its long press as an action named for a screen reader`() {
        rule.setContent { Box(Modifier.rowPress({}, {}).size(80.dp)) { Text("x") } }
        val label = rule.onNodeWithText("x").fetchSemanticsNode().config.getOrNull(SemanticsActions.OnLongClick)?.label
        assertEquals("メニューを開く", label)
    }

    @Test
    fun `a glyph button is read by its label and not by its glyph`() {
        var clicked = 0
        rule.setContent { GlyphButton("←", "戻る", { clicked++ }) }
        rule.onNodeWithContentDescription("戻る").assertHasClickAction().performClick()
        assertEquals(1, clicked)
        assertTrue(rule.onAllNodes(hasText("←")).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `a sort menu names itself, tells its choice, and marks the chosen item`() {
        var chosen: LibrarySortForTest = LibrarySortForTest.B
        rule.setContent {
            ChoiceMenu("並べ替え", chosen, LibrarySortForTest.entries, { it.name }, { chosen = it })
        }
        rule.onNodeWithContentDescription("並べ替え").assert(
            SemanticsMatcher("state is B") { it.config.getOrNull(SemanticsProperties.StateDescription) == "B" },
        ).performClick()
        val selected = rule.onAllNodes(SemanticsMatcher("a chosen item") { it.config.getOrNull(SemanticsProperties.Role) == Role.RadioButton })
        assertEquals(listOf(false, true), selected.fetchSemanticsNodes().map { it.config.getOrNull(SemanticsProperties.Selected) }.sortedBy { it })
    }

    @Test
    fun `the actions of a long press are buttons`() {
        rule.setContent {
            RowSheet(row("a.zip", mark = ReadMark.Unread), hasShortcut = false, folderCoverChosen = false, canChooseCover = false) {}
        }
        val buttons = rule.onAllNodes(SemanticsMatcher("a button") { it.config.getOrNull(SemanticsProperties.Role) == Role.Button })
        assertTrue(buttons.fetchSemanticsNodes().size >= 3)
        rule.onNode(SemanticsMatcher("heading") { it.config.getOrNull(SemanticsProperties.Heading) != null }).assert(hasText("a.zip"))
    }

    @Test
    fun `a title is read as a heading`() {
        rule.setContent { SheetActions("title") { Text("body") } }
        rule.onNode(SemanticsMatcher("heading") { it.config.getOrNull(SemanticsProperties.Heading) != null }).assert(hasText("title"))
    }

    @Test
    fun `a frame step moves the frame inside the page`() {
        val frame = CropFrame(0.2f, 0.2f, 0.6f, 0.6f)
        assertEquals(CropFrame(0.25f, 0.2f, 0.65f, 0.6f).left, frame.stepped(FrameStep.Right).left, 1e-6f)
        val larger = frame.stepped(FrameStep.Larger)
        assertTrue(larger.width > frame.width && larger.height > frame.height)
        val smaller = frame.stepped(FrameStep.Smaller)
        assertTrue(smaller.width < frame.width && smaller.height < frame.height)
        assertEquals(0f, CropFrame(0f, 0f, 0.5f, 0.5f).stepped(FrameStep.Left).left)
        val tiny = CropFrame(0.4f, 0.4f, 0.4f + CropFrame.MIN, 0.4f + CropFrame.MIN)
        assertEquals(CropFrame.MIN, tiny.stepped(FrameStep.Smaller).width, 1e-6f)
    }

    @Suppress("unused")
    private enum class LibrarySortForTest { A, B }
}
