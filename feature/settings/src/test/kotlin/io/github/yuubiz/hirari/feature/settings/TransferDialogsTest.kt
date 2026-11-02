package io.github.yuubiz.hirari.feature.settings

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import io.github.yuubiz.hirari.core.data.ImportChanges
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w480dp-h240dp-land-xxhdpi", fontScale = 2f)
class TransferDialogsTest {
    @get:Rule
    val rule = createComposeRule()

    private fun text(id: Int) = ApplicationProvider.getApplicationContext<Context>().getString(id)

    @Test
    fun `on a small screen with large text the import confirmation can be scrolled to its last line`() {
        val changes = ImportChanges(settings = 3, bookOverrides = 1, servers = 1, progress = 0, bookmarks = 0, history = 0)
        rule.setContent { MaterialTheme { ConfirmImportDialog(changes, onConfirm = {}, onCancel = {}) } }
        assertTrue(rule.onAllNodes(hasScrollAction()).fetchSemanticsNodes().isNotEmpty(), "nothing in the dialog scrolls")
        rule.onNodeWithText(text(R.string.import_servers_note)).performScrollTo()
    }
}
