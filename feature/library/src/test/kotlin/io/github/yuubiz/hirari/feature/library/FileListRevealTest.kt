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
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import io.github.yuubiz.hirari.core.model.LibrarySort
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
