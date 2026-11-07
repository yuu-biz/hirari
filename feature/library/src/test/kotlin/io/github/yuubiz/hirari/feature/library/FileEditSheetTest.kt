package io.github.yuubiz.hirari.feature.library

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import io.github.yuubiz.hirari.core.model.LibraryGrouping
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@RunWith(RobolectricTestRunner::class)
class FileEditSheetTest {
    @get:Rule val rule = createComposeRule()
    private fun row(name: String, rename: Boolean = true, delete: Boolean = true, directory: Boolean = false): FileRow {
        val entry = FolderEntry(name, name, directory, 1, null, rename, delete)
        return FileRow(requireNotNull(entry.toItem(true)), name, name)
    }

    @Test fun `sheet follows the setting and each of the provider flags independently`() {
        val enabled = mutableStateOf(false)
        val item = mutableStateOf(row("book.cbz"))
        rule.setContent {
            MaterialTheme { RowSheet(item.value, false, false, false, allowFileEdits = enabled.value) {} }
        }
        rule.onNodeWithText("名前を変更").assertDoesNotExist()
        rule.onNodeWithText("削除").assertDoesNotExist()
        rule.runOnIdle { enabled.value = true }
        rule.onNodeWithText("名前を変更").assertExists()
        rule.onNodeWithText("削除").assertExists()
        rule.runOnIdle { item.value = row("book.cbz", rename = false) }
        rule.onNodeWithText("名前を変更").assertDoesNotExist()
        rule.onNodeWithText("削除").assertExists()
        rule.runOnIdle { item.value = row("book.cbz", delete = false) }
        rule.onNodeWithText("名前を変更").assertExists()
        rule.onNodeWithText("削除").assertDoesNotExist()
    }

    @Test fun `all file kinds and folders offer supported edits and pseudo and parent items never do`() {
        val edits = setOf(SheetAction.Rename, SheetAction.Delete)
        for (item in listOf(row("book.cbz"), row("text.txt"), row("image.jpg"), row("folder", directory = true))) {
            assertEquals(edits, rowSheetActions(item, allowFileEdits = true).map { it.first }.toSet().intersect(edits))
            assertFalse(rowSheetActions(item).any { it.first in edits })
            val pseudo = PseudoFolder("key", "作者", LibraryGrouping.Author, true, "")
            assertFalse(rowSheetActions(item.copy(pseudo = pseudo), allowFileEdits = true).any { it.first in edits })
            assertFalse(rowSheetActions(item.copy(path = ""), allowFileEdits = true).any { it.first in edits })
        }
        assertFalse(rowSheetActions(row("..", directory = true), allowFileEdits = true).any { it.first in edits })
        val unsupported = row("book.cbz", rename = false, delete = false)
        assertFalse(rowSheetActions(unsupported, allowFileEdits = true).any { it.first in edits })
        assertEquals(edits, rowSheetActions(unsupported, allowFileEdits = true, canSave = true).map { it.first }.toSet().intersect(edits))
    }
}
