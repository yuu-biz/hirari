package io.github.yuubiz.hirari.feature.library

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import kotlinx.coroutines.awaitCancellation
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class FileEditDialogTest {
    @get:Rule val rule = createComposeRule()
    private val entry = FolderEntry("folder", "folder", true, null, null, true, true)
    private val row = FileRow(FileItem.Folder(entry), "a/folder", "folder")

    @Test fun `counting names the folder shows progress disables delete and can be cancelled`() {
        val shown = mutableStateOf(true)
        var started = false
        var cancelled = false
        var deleted = 0
        rule.setContent {
            MaterialTheme {
                if (shown.value) FileEditDialog(row, false, { _, progress ->
                    started = true
                    progress(9)
                    try { awaitCancellation() } finally { cancelled = true }
                }, { deleted++; FileEditResult.Done }, { shown.value = false })
            }
        }
        rule.waitUntil { started }
        rule.onNodeWithText("folder").assertExists()
        rule.onNodeWithText("中の項目を数えています（9 項目）").assertExists()
        rule.onNode(hasText("削除") and hasClickAction()).assertIsNotEnabled()
        rule.onNodeWithText("取り消す").performClick(); rule.waitForIdle()
        rule.waitUntil { cancelled }
        assertEquals(0, deleted)
    }

    @Test fun `capped count and reading record warning precede explicit confirmation`() {
        var deleted = 0
        rule.setContent {
            MaterialTheme { FileEditDialog(row, false, { _, progress -> progress(1000); 1000 }, { deleted++; FileEditResult.Done }, {}) }
        }
        rule.onNodeWithText("中の 1,000 項目以上も消えます").assertExists()
        rule.onNodeWithText("削除しますか？ 読書の記録も消えます。").assertExists()
        assertEquals(0, deleted)
        rule.onNode(hasText("削除") and hasClickAction()).assertIsEnabled().performClick()
        rule.waitUntil { deleted == 1 }
    }
}
