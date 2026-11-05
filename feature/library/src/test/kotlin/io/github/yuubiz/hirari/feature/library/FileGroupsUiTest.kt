package io.github.yuubiz.hirari.feature.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.dp
import io.github.yuubiz.hirari.core.data.GlobalSettings
import io.github.yuubiz.hirari.core.model.LibraryGrouping
import io.github.yuubiz.hirari.core.model.LibrarySection
import io.github.yuubiz.hirari.core.model.LibrarySort
import io.github.yuubiz.hirari.core.model.LibraryView
import io.github.yuubiz.hirari.core.model.LocationId
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w480dp-h900dp")
class FileGroupsUiTest {
    @get:Rule val rule = createComposeRule()
    private val fixture = Fixture()

    private fun content(rows: List<FileRow>, view: LibraryView, section: LibrarySection = LibrarySection.ReadState): FolderContent.Shown {
        val look = GlobalSettings(librarySection = section).listLook()
        return FolderContent.Shown(rows, rows.size, "", view, look, LibrarySort.Name, false,
            CoverLayout(fixture.covers, fixture.covers.sizePx(look.thumbSize)), null)
    }
    private fun rows(names: List<String>) = names.map { FolderEntry(it, it, false, 1, null) }.toRows("", Ordering(LibrarySort.Name, false, false))

    @Test fun `grid section folds and unfolds and survives recreation`() = folds(LibraryView.Grid)
    @Test fun `thumbnail list section folds and unfolds and survives recreation`() = folds(LibraryView.ThumbnailList)
    @Test fun `list section folds and unfolds and survives recreation`() = folds(LibraryView.List)

    private fun folds(view: LibraryView) {
        val restoration = StateRestorationTester(rule)
        val shown = content(rows(listOf("a.zip", "b.zip", "read.zip")).map { it.copy(mark = if (it.item.entry.name == "read.zip") ReadMark.Read else ReadMark.Unread) }, view)
        val opened = mutableListOf<String>()
        restoration.setContent { MaterialTheme { Box(Modifier.size(400.dp, 800.dp)) {
            FileList(shown, null, onOpen = { opened += it.path }, onLongPress = {}, onUp = {})
        } } }
        val header = rule.onNodeWithContentDescription("未読、2 件、折りたたみ")
        assertEquals(rule.onNode(hasScrollToIndexAction()).fetchSemanticsNode().boundsInRoot.width, header.fetchSemanticsNode().boundsInRoot.width, 0.5f)
        rule.onNodeWithText("a.zip").assertIsDisplayed()
        header.performClick()
        rule.onNodeWithText("a.zip").assertDoesNotExist()
        rule.onNodeWithText("b.zip").assertDoesNotExist()
        rule.onNodeWithText("read.zip").assertIsDisplayed()
        restoration.emulateSavedInstanceStateRestore()
        rule.onNodeWithText("a.zip").assertDoesNotExist()
        rule.onNodeWithContentDescription("未読、2 件、展開").performClick()
        rule.onNodeWithText("b.zip").assertIsDisplayed().performClick()
        assertEquals(listOf("b.zip"), opened)
    }

    @Test
    fun `section changes and restoration retain the scrolled item key`() {
        val restoration = StateRestorationTester(rule)
        val source = rows((1..70).map { "book$it.zip" })
        var shown by mutableStateOf(content(source, LibraryView.List, LibrarySection.None))
        restoration.setContent { MaterialTheme { Box(Modifier.size(400.dp, 600.dp)) { FileList(shown, null, {}, {}) } } }
        rule.onNode(hasScrollToIndexAction()).performScrollToIndex(39)
        rule.onNodeWithText("book40.zip").assertIsDisplayed()
        rule.runOnIdle { shown = shown.copy(look = shown.look.copy(section = LibrarySection.ReadState)) }
        rule.onNodeWithText("book40.zip").assertIsDisplayed()
        restoration.emulateSavedInstanceStateRestore()
        rule.onNodeWithText("book40.zip").assertIsDisplayed()
    }

    @Test fun `list restoration after removing the anchored section uses the next shown entry`() = removedSection(LibraryView.List)
    @Test fun `grid restoration after removing the anchored section uses the next shown entry`() = removedSection(LibraryView.Grid)

    private fun removedSection(view: LibraryView) {
        val source = rows((1..90).map { "book$it.zip" }).mapIndexed { index, row -> row.copy(mark = when {
            index < 30 -> ReadMark.Unread
            index < 60 -> ReadMark.Reading(20)
            else -> ReadMark.Read
        }) }
        var shown by mutableStateOf(content(source, view))
        var showing by mutableStateOf(true)
        rule.setContent {
            val holder = rememberSaveableStateHolder()
            if (showing) holder.SaveableStateProvider("folder") {
                MaterialTheme { Box(Modifier.size(400.dp, 600.dp)) { FileList(shown, null, {}, {}) } }
            }
        }
        // Put the reading section's header at the top. The saved anchor must be book31, below it.
        rule.onNode(hasScrollToIndexAction()).performScrollToIndex(31)
        rule.onNodeWithText("book31.zip").assertIsDisplayed()
        rule.runOnIdle { showing = false }
        rule.runOnIdle {
            shown = content(source.filter { it.mark !is ReadMark.Reading }, view)
            showing = true
        }
        rule.onNodeWithText("book61.zip").assertIsDisplayed()
        rule.onNodeWithText("book1.zip").assertDoesNotExist()
    }

    @Test
    fun `two pseudo levels restore each scroll position and apply sections at every level`() {
        val source = rows((1..70).flatMap { author ->
            if (author == 40) (1..40).flatMap { series -> (1..40).map { "[Author$author] Series$series $it.zip" } }
            else (1..2).map { "[Author$author] book$it.zip" }
        })
        val parent = FolderKey(LocationId(1), "")
        var folder by mutableStateOf(parent)
        val look = ListLook(grouping = LibraryGrouping.Author, subgrouping = LibraryGrouping.Series)
        rule.setContent {
            val holder = rememberSaveableStateHolder()
            holder.SaveableStateProvider(folder.pseudo?.key ?: "parent") {
                val (existing, members) = resolvePseudoFolder(source, folder.pseudo, look)
                val shownRows = pseudoFolders(members, look.groupingInside(existing), true, "", LibrarySort.Name, true, existing)
                MaterialTheme { Box(Modifier.size(400.dp, 600.dp)) {
                    FileList(content(shownRows, LibraryView.List), null,
                        onOpen = { row -> row.pseudo?.let { folder = parent.copy(pseudo = it) } }, onLongPress = {},
                        onUp = if (folder.pseudo != null) ({ folder = folder.copy(pseudo = folder.pseudo?.parent) }) else null, folderKey = folder)
                } }
            }
        }
        // A folder section header occupies index 0.
        rule.onNode(hasScrollToIndexAction()).performScrollToIndex(39)
        rule.onNodeWithText("Author40").assertIsDisplayed().performClick()
        rule.onNodeWithContentDescription("フォルダ、40 件、折りたたみ").assertIsDisplayed()
        // Parent item and folder header precede the second-level groups. Series29 is kept below the top row,
        // where the sticky section header covers the item and takes the tap.
        rule.onNode(hasScrollToIndexAction()).performScrollToIndex(28)
        rule.onNodeWithText("Series29").assertIsDisplayed().performClick()
        rule.onNodeWithContentDescription("未読、40 件、折りたたみ").assertIsDisplayed()
        rule.onNode(hasScrollToIndexAction()).performScrollToIndex(30)
        rule.onNodeWithText("[Author40] Series29 29.zip").assertIsDisplayed()
        rule.runOnIdle { folder = folder.copy(pseudo = folder.pseudo!!.parent) }
        rule.onNodeWithText("Series29").assertIsDisplayed().performClick()
        rule.onNodeWithText("[Author40] Series29 29.zip").assertIsDisplayed()
        // Up from the second level returns to the first, then to the real folder.
        rule.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
        rule.onNodeWithText("上のフォルダへ").performClick()
        rule.onNodeWithText("Series29").assertIsDisplayed()
        rule.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
        rule.onNodeWithText("上のフォルダへ").performClick()
        rule.onNodeWithText("Author40").assertIsDisplayed()
    }

    @Test
    fun `reveal finds an entry after section headers are inserted`() {
        val shown = content(rows((1..70).map { "book$it.zip" }), LibraryView.Grid)
        rule.setContent { MaterialTheme { Box(Modifier.size(400.dp, 600.dp)) { FileList(shown, "book60.zip", {}, {}, onUp = {}) } } }
        rule.onNodeWithText("book60.zip").assertIsDisplayed()
    }
}
