package io.github.yuubiz.hirari.feature.library

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import io.github.yuubiz.hirari.core.model.BookAddress
import io.github.yuubiz.hirari.core.model.LibraryLocation
import io.github.yuubiz.hirari.core.model.LocationId
import io.github.yuubiz.hirari.core.model.OpenBookRequest
import io.github.yuubiz.hirari.core.model.StartAt
import io.github.yuubiz.hirari.core.model.UriString
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class FolderBookSheetTest {
    @get:Rule val rule = createComposeRule()
    private val fixture = Fixture()
    private val place = LibraryLocation.SafTree(LocationId(1), "漫画", UriString("content://tree/manga"))
    private val opened = mutableListOf<OpenBookRequest>()
    private val label = "このフォルダを本として読む"

    private fun entry(name: String, directory: Boolean = false) =
        FolderEntry(name, "doc:$name", directory, null, null)

    private fun show(children: List<FolderEntry>) {
        fixture.locations.rows.value = listOf(place)
        fixture.lister.folders[""] = listOf(entry("作品", directory = true))
        fixture.lister.folders["作品"] = children
        val model = FolderViewModel(CompletableDeferred(fixture.services), FolderKey(place.id, ""), Dispatchers.Unconfined)
        val actions = FolderActions(
            openBook = { opened += it }, openText = {}, openArchive = {}, openFolder = {},
            pinShortcut = { true }, openAncestor = {}, close = {},
        )
        rule.setContent { MaterialTheme { FolderScreen(model, actions) } }
        rule.waitUntil { model.state.value.content is FolderContent.Shown }
        rule.onNodeWithText("作品").performTouchInput { longClick() }
        rule.waitForIdle()
        assertEquals(2, fixture.lister.calls, "one parent listing and one direct child listing")
    }

    @Test
    fun `an image folder offers the action and opens its book at the saved position`() {
        show(listOf(entry("p01.JPG"), entry("notes.txt")))
        rule.onNodeWithText(label).performClick()
        assertEquals(
            listOf(OpenBookRequest(BookAddress.InLocation(place.id, "作品", folder = true, "doc:作品"), StartAt.Saved)),
            opened,
        )
        rule.onNodeWithText(label).assertDoesNotExist()
    }

    @Test
    fun `an empty folder does not offer the action`() {
        show(emptyList())
        rule.onNodeWithText(label).assertDoesNotExist()
    }

    @Test
    fun `texts archives and child folders do not make an image folder`() {
        fixture.lister.folders["作品/下巻"] = listOf(entry("p01.jpg"))
        show(listOf(entry("notes.txt"), entry("book.zip"), entry("下巻", directory = true)))
        rule.onNodeWithText(label).assertDoesNotExist()
    }

    @Test
    fun `images excluded by the reader do not offer the action`() {
        show(listOf(entry(".cover.jpg"), entry("pages.jpg", directory = true)))
        rule.onNodeWithText(label).assertDoesNotExist()
    }
}
