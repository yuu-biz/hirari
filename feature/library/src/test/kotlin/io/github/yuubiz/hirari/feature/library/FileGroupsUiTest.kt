package io.github.yuubiz.hirari.feature.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import io.github.yuubiz.hirari.core.model.LibrarySort
import io.github.yuubiz.hirari.core.model.LibraryView
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

    private fun content(names: List<String>, view: LibraryView, grouping: LibraryGrouping = LibraryGrouping.Author): FolderContent.Shown {
        val rows = names.map { FolderEntry(it, it, false, 1, null) }.toRows("", Ordering(LibrarySort.Name, false, false))
        val look = GlobalSettings(libraryGrouping = grouping).listLook()
        return FolderContent.Shown(rows, rows.size, "", view, look, LibrarySort.Name, false,
            CoverLayout(fixture.covers, fixture.covers.sizePx(look.thumbSize)), null)
    }

    @Test fun `grid header folds and unfolds its items and survives recreation`() = folds(LibraryView.Grid)
    @Test fun `thumbnail list header folds and unfolds its items and survives recreation`() = folds(LibraryView.ThumbnailList)
    @Test fun `list header folds and unfolds its items and survives recreation`() = folds(LibraryView.List)

    private fun folds(view: LibraryView) {
        val restoration = StateRestorationTester(rule)
        val shown = content(listOf("[A] 1.zip", "[A] 2.zip", "[B] 1.zip", "plain.zip"), view)
        val opened = mutableListOf<String>()
        restoration.setContent {
            MaterialTheme { Box(Modifier.size(400.dp, 800.dp)) {
                FileList(shown, null, onOpen = { opened += it.path }, onLongPress = {}, onUp = {})
            } }
        }
        val header = rule.onNodeWithContentDescription("A、2 件、折りたたみ")
        val bounds = header.fetchSemanticsNode().boundsInRoot
        assertEquals(rule.onNode(hasScrollToIndexAction()).fetchSemanticsNode().boundsInRoot.width, bounds.width, 0.5f)
        rule.onNodeWithText("[A] 1.zip").assertIsDisplayed()
        header.performClick()
        rule.onNodeWithText("[A] 1.zip").assertDoesNotExist()
        rule.onNodeWithText("[A] 2.zip").assertDoesNotExist()
        rule.onNodeWithText("[B] 1.zip").assertIsDisplayed()
        rule.onNodeWithText("plain.zip").assertIsDisplayed()
        restoration.emulateSavedInstanceStateRestore()
        rule.onNodeWithText("[A] 1.zip").assertDoesNotExist()
        rule.onNodeWithContentDescription("A、2 件、展開").performClick()
        rule.onNodeWithText("[A] 2.zip").assertIsDisplayed().performClick()
        assertEquals(listOf("[A] 2.zip"), opened)
    }

    @Test
    fun `reveal finds a folder item by key after headers are inserted`() {
        val names = (1..70).map { "[author$it] book.zip" }
        val target = "[author60] book.zip"
        val shown = content(names, LibraryView.Grid)
        rule.setContent { MaterialTheme { Box(Modifier.size(400.dp, 600.dp)) {
            FileList(shown, target, {}, {}, onUp = {})
        } } }
        rule.onNodeWithText(target).assertIsDisplayed()
    }

    @Test
    fun `grouping changes keep the scrolled item and restoration uses its key`() {
        val restoration = StateRestorationTester(rule)
        val names = (1..70).map { "[author$it] book.zip" }
        var shown by mutableStateOf(content(names, LibraryView.List, LibraryGrouping.None))
        restoration.setContent { MaterialTheme { Box(Modifier.size(400.dp, 600.dp)) {
            FileList(shown, null, {}, {})
        } } }
        rule.onNode(hasScrollToIndexAction()).performScrollToIndex(39)
        val target = "[author40] book.zip"
        rule.onNodeWithText(target).assertIsDisplayed()
        rule.runOnIdle { shown = shown.copy(look = shown.look.copy(grouping = LibraryGrouping.Author)) }
        rule.onNodeWithText(target).assertIsDisplayed()
        restoration.emulateSavedInstanceStateRestore()
        rule.onNodeWithText(target).assertIsDisplayed()
    }

    @Test
    fun `restoration finds the saved item when the restored list has more headers`() {
        val restoration = StateRestorationTester(rule)
        val names = (1..70).map { "[author$it] book.zip" }
        // Change only at recreation: the old composition saves its key before the new one adds headers.
        var grouping = LibraryGrouping.None
        restoration.setContent { MaterialTheme { Box(Modifier.size(400.dp, 600.dp)) {
            FileList(content(names, LibraryView.List, grouping), null, {}, {})
        } } }
        rule.onNode(hasScrollToIndexAction()).performScrollToIndex(39)
        val target = "[author40] book.zip"
        rule.onNodeWithText(target).assertIsDisplayed()
        grouping = LibraryGrouping.Author
        restoration.emulateSavedInstanceStateRestore()
        rule.onNodeWithText(target).assertIsDisplayed()
    }
}
