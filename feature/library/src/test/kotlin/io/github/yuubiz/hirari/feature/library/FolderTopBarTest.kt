package io.github.yuubiz.hirari.feature.library

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.yuubiz.hirari.core.model.LibraryLocation
import io.github.yuubiz.hirari.core.model.LibraryView
import io.github.yuubiz.hirari.core.model.LocationId
import io.github.yuubiz.hirari.core.model.UriString
import android.os.Looper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The top bar and the parent item of docs/spec.md section 4.3 (上部バー, 親フォルダの項目). */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class FolderTopBarTest {
    @get:Rule
    val rule = createComposeRule()

    private val fixture = Fixture()
    private val place = LibraryLocation.SafTree(LocationId(1), "漫画", UriString("content://tree/manga"))
    private val opened = mutableListOf<String>()
    private val shortcuts = mutableListOf<PlaceShortcut>()
    private var closed = 0

    private fun file(name: String) = FolderEntry(name, "doc:$name", directory = false, size = 1, modified = null)

    private fun show(path: String, view: LibraryView = LibraryView.Grid, parentItem: Boolean = true) {
        fixture.locations.rows.value = listOf(place)
        fixture.lister.folders.getOrPut(path) { listOf(file("1巻.zip"), file("2巻.zip")) }
        fixture.settings.value.value = fixture.settings.value.value.copy(libraryView = view, showParentItem = parentItem)
        val model = FolderViewModel(CompletableDeferred(fixture.services), FolderKey(place.id, path), Dispatchers.Unconfined)
        val actions = FolderActions(
            openBook = {}, openText = {}, openArchive = {}, openFolder = {}, pinShortcut = { shortcuts += it; true },
            openAncestor = { opened += it },
            close = { closed++ },
        )
        rule.setContent { MaterialTheme { FolderScreen(model, actions) } }
        rule.waitUntil { model.state.value.content is FolderContent.Shown }
    }

    private val isButton = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)
    private val upLabel = "上のフォルダへ"
    private val parentItem = (hasText(upLabel) or hasContentDescription(upLabel)) and !isButton

    private fun upButton(): SemanticsNodeInteraction = rule.onNode(hasContentDescription(upLabel) and isButton)

    @Test
    fun `up goes to the parent folder below the top of the place`() {
        show("a/b")
        upButton().performClick()
        assertEquals(listOf("a"), opened)
        assertTrue(rule.onAllNodes(hasContentDescription("閉じる")).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `at the top of the place the leading button closes the list and no parent item shows`() {
        show("")
        rule.onNode(hasContentDescription("閉じる") and isButton).performClick()
        assertEquals(1, closed)
        assertTrue(rule.onAllNodes(hasContentDescription(upLabel)).fetchSemanticsNodes().isEmpty())
        assertTrue(rule.onAllNodes(parentItem).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `the parent item comes first in the grid and goes up`() = parentItemComesFirst(LibraryView.Grid)

    @Test
    fun `the parent item comes first in the thumbnail list and goes up`() = parentItemComesFirst(LibraryView.ThumbnailList)

    @Test
    fun `the parent item comes first in the list and goes up`() = parentItemComesFirst(LibraryView.List)

    private fun parentItemComesFirst(view: LibraryView) {
        show("a/b", view)
        val parent = rule.onNode(parentItem).fetchSemanticsNode().boundsInRoot
        val first = rule.onNodeWithText("1巻.zip").fetchSemanticsNode().boundsInRoot
        assertTrue(parent.top < first.top || (parent.top == first.top && parent.left < first.left), "$parent before $first")
        rule.onNode(parentItem).performClick()
        assertEquals(listOf("a"), opened)
    }

    @Test
    fun `the setting hides the parent item`() {
        show("a/b", parentItem = false)
        assertTrue(rule.onAllNodes(parentItem).fetchSemanticsNodes().isEmpty())
        upButton().performClick()
        assertEquals(listOf("a"), opened)
    }

    @Test
    fun `the title lists the folders above and goes up to the one picked`() {
        show("a/b/c")
        rule.onNode(hasText("c") and SemanticsMatcher.keyIsDefined(SemanticsProperties.Role)).performClick()
        rule.onNodeWithText("b").assertExists()
        assertTrue(rule.onAllNodes(hasText("漫画")).fetchSemanticsNodes().size == 2, "the place in the title and in the menu")
        assertEquals(1, rule.onAllNodes(hasText("c")).fetchSemanticsNodes().size)
        rule.onNodeWithText("a").performClick()
        assertEquals(listOf("a"), opened)
    }

    @Test
    fun `the view button turns the grid to the thumbnail list, the list, and the grid again`() {
        show("a")
        val views = (1..3).map {
            rule.onNode(hasContentDescription("表示形式") and isButton).performClick()
            rule.waitForIdle()
            fixture.settings.value.value.libraryView
        }
        assertEquals(listOf(LibraryView.ThumbnailList, LibraryView.List, LibraryView.Grid), views)
    }

    @Test
    fun `the view button says the current view`() {
        show("a", LibraryView.ThumbnailList)
        rule.onNode(hasContentDescription("表示形式") and isButton)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "サムネイル付きリスト"))
    }

    private fun menu(item: String) {
        rule.onNode(hasContentDescription("その他の操作")).performClick()
        rule.onNodeWithText(item).performClick()
        rule.waitForIdle()
    }

    @Test
    fun `the menu pins this folder, takes the pin off, and makes its shortcut`() {
        show("a/b")
        menu("このフォルダをピン留め")
        assertEquals(listOf(place.id to "a/b"), fixture.locations.pins.value.map { it.location to it.path })
        menu("ピン留めを外す")
        assertTrue(fixture.locations.pins.value.isEmpty())
        menu("ホーム画面にショートカットを作成")
        assertEquals(listOf(PlaceShortcut(place.id, "a/b", folder = true, label = "b")), shortcuts)
    }

    @Test
    fun `the top of a place offers its shortcut and no pin`() {
        show("")
        rule.onNode(hasContentDescription("その他の操作")).performClick()
        assertTrue(rule.onAllNodes(hasText("このフォルダをピン留め")).fetchSemanticsNodes().isEmpty())
        rule.onNodeWithText("ホーム画面にショートカットを作成").performClick()
        rule.waitForIdle()
        assertEquals(listOf(PlaceShortcut(place.id, "", folder = true, label = "漫画")), shortcuts)
    }

    @Test
    fun `reading the folder again keeps the list where it was scrolled`() {
        fixture.lister.folders["a"] = (0 until 60).map { file("r%02d.zip".format(it)) }
        show("a", LibraryView.List)
        rule.onNode(hasScrollToIndexAction()).performScrollToIndex(40)
        rule.onNodeWithText("r40.zip").assertIsDisplayed()
        fixture.lister.folders["a"] = (0 until 61).map { file("r%02d.zip".format(it)) }
        fixture.lister.delayMs = 1_000
        menu("読み直す")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        rule.waitForIdle()
        rule.onNodeWithText("r40.zip").assertIsDisplayed()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_000))
        rule.waitForIdle()
        assertEquals(2, fixture.lister.calls)
        rule.onNodeWithText("r40.zip").assertIsDisplayed()
        assertTrue(rule.onAllNodes(hasText("r00.zip")).fetchSemanticsNodes().isEmpty())
    }
}
