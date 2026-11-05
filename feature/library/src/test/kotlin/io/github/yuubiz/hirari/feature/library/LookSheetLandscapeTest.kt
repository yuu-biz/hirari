package io.github.yuubiz.hirari.feature.library

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performClick
import io.github.yuubiz.hirari.core.model.LibraryLocation
import io.github.yuubiz.hirari.core.model.LibraryGrouping
import io.github.yuubiz.hirari.core.model.LibraryView
import io.github.yuubiz.hirari.core.model.LocationId
import io.github.yuubiz.hirari.core.model.UriString
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 表示の調整 in a window that is wider than it is tall leaves the list showing (docs/spec.md section 4.3). */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w900dp-h480dp")
class LookSheetLandscapeTest {
    @get:Rule
    val rule = createComposeRule()

    private val fixture = Fixture()
    private val place = LibraryLocation.SafTree(LocationId(1), "漫画", UriString("content://tree/manga"))

    private fun show() {
        fixture.locations.rows.value = listOf(place)
        fixture.lister.folders["a"] = (1..12).map { FolderEntry("${it}巻.zip", "doc:$it", directory = false, size = 1, modified = null) }
        fixture.settings.value.value = fixture.settings.value.value.copy(libraryView = LibraryView.Grid)
        val model = FolderViewModel(CompletableDeferred(fixture.services), FolderKey(place.id, "a"), Dispatchers.Unconfined)
        val actions = FolderActions(openBook = {}, openText = {}, openArchive = {}, openFolder = {}, pinShortcut = { true }, openAncestor = {}, close = {})
        rule.setContent { MaterialTheme { FolderScreen(model, actions) } }
        rule.waitUntil { model.state.value.content is FolderContent.Shown }
        rule.onNode(hasContentDescription("その他の操作")).performClick()
        rule.onNodeWithText("表示の調整").performClick()
        rule.waitForIdle()
    }

    private fun lefts(text: String) = rule.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().map { it.boundsInRoot }

    @Test
    fun `the sheet takes the end of the window and the list beside it keeps showing`() {
        show()
        val settings = rule.onNodeWithText("列数（横画面）").also { it.assertIsDisplayed() }.fetchSemanticsNode().boundsInRoot
        val window = rule.onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue(settings.left >= window.width - 360f, "the sheet is wider than 360 dp: it starts at ${settings.left}")
        assertTrue(settings.left >= window.width / 2, "the sheet starts at ${settings.left} of ${window.width}")

        val books = lefts("巻")
        assertTrue(books.size >= 6, "${books.size} books show")
        val beside = books.filter { it.right <= settings.left }
        assertTrue(beside.size >= 3, "${beside.size} books show beside the sheet")
        assertTrue(beside.all { it.top >= 0f && it.bottom <= window.height }, "the books beside the sheet lie in the window")
    }

    @Test
    fun `every setting of the sheet is reachable by scrolling and saves at once`() {
        show()
        rule.onNodeWithText("4 列").performScrollTo().performClick()
        rule.waitForIdle()
        // A chip's own scrolling parent is its row, so the sheet is scrolled to the label below the row first.
        rule.onNodeWithText("セクション").performScrollTo()
        rule.onNodeWithText("作者").performClick()
        rule.waitForIdle()
        assertEquals(LibraryGrouping.Author, fixture.settings.value.value.libraryGrouping)
        rule.onNodeWithText("サムネイルの大きさ").performScrollTo().assertIsDisplayed()
        assertEquals(4, fixture.settings.value.value.gridColumnsLandscape)
    }

    @Test
    fun `circle author merging is disabled without author grouping and saves a toggle immediately`() {
        show()
        rule.onNodeWithText("サークルと作者をまとめる").performScrollTo().assertIsNotEnabled()
        assertEquals(true, fixture.settings.value.value.mergeCircleAuthors)
        rule.onNodeWithText("セクション").performScrollTo()
        rule.onNodeWithText("作者").performClick()
        rule.onNodeWithText("サークルと作者をまとめる").performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(false, fixture.settings.value.value.mergeCircleAuthors)
    }

    @Test
    fun `sections save independently beside grouping`() {
        show()
        rule.onNodeWithText("サークルと作者をまとめる").performScrollTo()
        rule.onNodeWithText("読書状態").performClick()
        rule.waitForIdle()
        assertEquals(io.github.yuubiz.hirari.core.model.LibrarySection.ReadState, fixture.settings.value.value.librarySection)
        assertEquals(LibraryGrouping.None, fixture.settings.value.value.libraryGrouping)
        rule.onNodeWithText("セクション").performScrollTo()
        rule.onNodeWithText("作者").performClick()
        rule.waitForIdle()
        assertEquals(LibraryGrouping.Author, fixture.settings.value.value.libraryGrouping)
        assertEquals(io.github.yuubiz.hirari.core.model.LibrarySection.ReadState, fixture.settings.value.value.librarySection)
    }

    @Test
    fun `the close button takes the sheet away`() {
        show()
        rule.onNode(hasContentDescription("表示の調整を閉じる")).performClick()
        rule.waitForIdle()
        assertTrue(rule.onAllNodes(hasText("列数（横画面）")).fetchSemanticsNodes().isEmpty())
    }
}
