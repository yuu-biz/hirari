package io.github.yuubiz.hirari.feature.library

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import io.github.yuubiz.hirari.core.model.LibraryLocation
import io.github.yuubiz.hirari.core.model.LocationId
import io.github.yuubiz.hirari.core.model.UriString
import io.github.yuubiz.hirari.core.source.save.SaveFailure
import io.github.yuubiz.hirari.core.source.save.SaveOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertTrue

/** How a save ended reaches the file list even when the list was not showing then (docs/spec.md section 4.5). */
@RunWith(RobolectricTestRunner::class)
class SaveResultsTest {
    @get:Rule
    val rule = createComposeRule()

    private val fixture = Fixture()
    private val server = LibraryLocation.SmbShare(LocationId(2), "NAS", "nas.local", 445, "books", "漫画", null, null, null)
    private val actions = FolderActions({}, {}, {}, {}, { false }, {}, {})
    private val failed = SaveFinished.Failed("1巻.zip", SaveFailure.ConnectionLost)
        .text(ApplicationProvider.getApplicationContext<Context>().resources)

    private fun saveThatFails() {
        fixture.smb.saving = { SaveOutcome.Failed(SaveFailure.ConnectionLost) }
        fixture.saves.start(SaveRequest(server, "作品/1巻.zip", "1巻.zip", false, UriString("content://tree/saves")))
        assertTrue(fixture.saves.jobs.value.isEmpty(), "the save has ended")
    }

    @Test
    fun `a save that ended while the list was away shows when a list shows, and only once`() {
        fixture.locations.rows.value = listOf(server)
        fixture.smb.folders["作品"] = listOf(FolderEntry("1巻.zip", null, directory = false, size = 1, modified = null))
        saveThatFails()

        var shown by mutableStateOf(true)
        rule.setContent {
            if (shown) {
                val model = remember { FolderViewModel(CompletableDeferred(fixture.services), FolderKey(server.id, "作品"), Dispatchers.Unconfined) }
                FolderScreen(model, actions)
            }
        }
        rule.waitUntil(5_000) { rule.onAllNodesWithText(failed).fetchSemanticsNodes().isNotEmpty() }

        shown = false
        rule.waitForIdle()
        shown = true
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(1_000)
        rule.onNodeWithText(failed).assertDoesNotExist()
    }
}
