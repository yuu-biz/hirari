package io.github.yuubiz.hirari.feature.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.dp
import io.github.yuubiz.hirari.core.model.LibrarySort
import io.github.yuubiz.hirari.core.model.LibrarySection
import io.github.yuubiz.hirari.core.model.LibraryView
import io.github.yuubiz.hirari.core.model.LocationId
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w480dp-h900dp")
class FileListRevealTest {
    @get:Rule val rule = createComposeRule()
    private val fixture = Fixture()
    private val rows = (1..100).map { number ->
        val name = "book-%03d.zip".format(number)
        val entry = FolderEntry(name, name, false, 10, Instant.EPOCH)
        FileRow(requireNotNull(entry.toItem(false)), "series/$name", name)
    }

    @Test fun gridRevealsTheLastBookAfterTheReaderReplacesItself() = returning(LibraryView.Grid)
    @Test fun thumbnailListRevealsTheLastBookAfterTheReaderReplacesItself() = returning(LibraryView.ThumbnailList)
    @Test fun listRevealsTheLastBookAfterTheReaderReplacesItself() = returning(LibraryView.List)

    @Test fun gridRevealsTheSameBookAgainAfterItMovesToRead() = returnsAgain(LibraryView.Grid)
    @Test fun thumbnailListRevealsTheSameBookAgainAfterItMovesToRead() = returnsAgain(LibraryView.ThumbnailList)
    @Test fun listRevealsTheSameBookAgainAfterItMovesToRead() = returnsAgain(LibraryView.List)

    @Test fun gridUnfoldsTheReturnedBooksSection() = unfolds(LibraryView.Grid)
    @Test fun thumbnailListUnfoldsTheReturnedBooksSection() = unfolds(LibraryView.ThumbnailList)
    @Test fun listUnfoldsTheReturnedBooksSection() = unfolds(LibraryView.List)

    private fun sectioned(view: LibraryView) = FolderContent.Shown(
        rows.mapIndexed { index, row -> row.copy(mark = when {
            index < 30 -> ReadMark.Unread
            index < 60 -> ReadMark.Reading(20)
            else -> ReadMark.Read
        }) }, rows.size, "", view, ListLook(section = LibrarySection.ReadState), LibrarySort.Name, true,
        CoverLayout(fixture.covers, 200), folderCoverPath = null,
    )

    private fun returnsAgain(view: LibraryView) {
        val target = rows[30]
        var shown by mutableStateOf(sectioned(view))
        var token by mutableStateOf(1L)
        rule.setContent { MaterialTheme { Box(Modifier.size(360.dp, 600.dp)) {
            FileList(shown, target.path, {}, {}, revealToken = token)
        } } }
        rule.onNodeWithText(target.item.entry.name).assertIsDisplayed()
        // The list can remain composed behind the reader. Its old viewport must not suppress a new return.
        rule.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
        rule.onNodeWithText(target.item.entry.name).assertDoesNotExist()
        rule.runOnIdle {
            shown = shown.copy(rows = shown.rows.map { if (it.path == target.path) it.copy(mark = ReadMark.Read) else it })
            token++
        }
        rule.onNodeWithText(target.item.entry.name).assertIsDisplayed()
    }

    private fun unfolds(view: LibraryView) {
        val shown = sectioned(view)
        val target = rows[59]
        var reveal by mutableStateOf<String?>(null)
        var token by mutableStateOf(0L)
        rule.setContent { MaterialTheme { Box(Modifier.size(360.dp, 600.dp)) {
            FileList(shown, reveal, {}, {}, revealToken = token)
        } } }
        rule.onNode(hasScrollToIndexAction()).performScrollToIndex(31)
        rule.onNodeWithContentDescription("読みかけ、30 件、折りたたみ").performClick()
        rule.onNodeWithText(target.item.entry.name).assertDoesNotExist()
        rule.runOnIdle {
            reveal = target.path
            token++
        }
        rule.onNodeWithText(target.item.entry.name).assertIsDisplayed()
        // The reveal changes the fold state itself, so the header now offers to fold the section again.
        rule.onNode(hasScrollToIndexAction()).performScrollToIndex(31)
        rule.onNodeWithContentDescription("読みかけ、30 件、折りたたみ").assertIsDisplayed()
    }

    private fun returning(view: LibraryView) {
        var inFolder by mutableStateOf(true)
        var reveal by mutableStateOf<String?>(rows.first().path)
        val shown = FolderContent.Shown(rows, rows.size, "", view, ListLook(), LibrarySort.Name, true,
            CoverLayout(fixture.covers, 200), folderCoverPath = null)
        rule.setContent {
            val holder = rememberSaveableStateHolder()
            MaterialTheme {
                if (inFolder) holder.SaveableStateProvider("folder") {
                    Box(Modifier.size(360.dp, 600.dp)) {
                        FileList(shown, reveal, {}, {}, folderKey = FolderKey(LocationId(1), "series"))
                    }
                }
            }
        }
        rule.onNodeWithText(rows.first().item.entry.name).assertIsDisplayed()
        rule.runOnIdle { inFolder = false }
        rule.waitForIdle()
        rule.runOnIdle {
            reveal = rows.last().path
            inFolder = true
        }
        rule.onNodeWithText(rows.last().item.entry.name).assertIsDisplayed()
        // Also handle a result while the list's composition remains alive during navigation.
        rule.runOnIdle { reveal = rows.first().path }
        rule.onNodeWithText(rows.first().item.entry.name).assertIsDisplayed()
    }
}
