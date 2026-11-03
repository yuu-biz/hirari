package io.github.yuubiz.hirari

import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.view.KeyEvent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yuubiz.hirari.core.model.BOOK_ID_PREFIX_BYTES
import io.github.yuubiz.hirari.core.model.BookId
import io.github.yuubiz.hirari.core.model.BookProgress
import io.github.yuubiz.hirari.core.model.ReadState
import io.github.yuubiz.hirari.core.model.documentBookId
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.Instant

/**
 * The page panel and the menu of docs/spec.md section 4.8 on the real activity. The reader's page number is the
 * label TalkBack reads (section 6.7), which is how these tests see where the reader is.
 */
@OptIn(ExperimentalTestApi::class)
class ReaderSheetsTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val app = context.applicationContext as HirariApp

    @Before
    fun skipFullScreenNotice() {
        val output = instrumentation.uiAutomation.executeShellCommand("settings put secure immersive_mode_confirmations confirmed")
        ParcelFileDescriptor.AutoCloseInputStream(output).use { it.readBytes() }
    }

    /** A corpus book the store has never seen, and the intent that opens it. */
    private fun freshBook(): Pair<BookId, Intent> = runBlocking {
        val bytes = instrumentation.context.assets.open("zip/images.zip").use { it.readBytes() }
        val file = File(context.cacheDir, "sheets-test.zip").apply { writeBytes(bytes) }
        val id = documentBookId(bytes.copyOf(minOf(bytes.size, BOOK_ID_PREFIX_BYTES)), bytes.size.toLong())
        app.services.await().storage.progress.save(listOf(BookProgress(id, ReadState.Unread, null, Instant.EPOCH)))
        id to Intent(context, MainActivity::class.java).putExtra("book", Uri.fromFile(file).toString())
    }

    private fun pageLabel(): String? = compose.onAllNodes(hasContentDescription(" / ", substring = true)).fetchSemanticsNodes()
        .firstNotNullOfOrNull { it.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull() }

    private fun awaitPage(head: Int): String {
        compose.waitUntil(15_000) { pageLabel()?.startsWith("$head ") == true || pageLabel()?.startsWith("$head-") == true }
        return pageLabel()!!
    }

    /** A screenshot the run leaves in `/data/local/tmp`, which outlives the app's removal after the run. */
    private fun screenshot(name: String) {
        compose.waitForIdle()
        instrumentation.waitForIdleSync()
        android.os.SystemClock.sleep(500)
        val output = instrumentation.uiAutomation.executeShellCommand("screencap -p /data/local/tmp/hirari-$name.png")
        ParcelFileDescriptor.AutoCloseInputStream(output).use { it.readBytes() }
    }

    /**
     * The reader times a turn from `SystemClock.uptimeMillis` and ends it on the frame clock's time, which are one
     * clock on a device. The rule's frame clock counts from zero, so without this a turn never ends and Compose
     * is never idle. Later frames then read a time past the end of any turn that starts within the next minute.
     */
    private fun alignFrameClockWithUptime() {
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(android.os.SystemClock.uptimeMillis() + 60_000, ignoreFrameDuration = true)
        compose.mainClock.autoAdvance = true
    }

    private fun tapPageBar() = compose.onRoot().performTouchInput { click(Offset(width / 2f, height - 30.dp.toPx())) }

    @Test
    fun thePagePanelMovesTheReaderAndUndoGoesBack() {
        val (_, intent) = freshBook()
        ActivityScenario.launch<MainActivity>(intent).use {
            val count = awaitPage(1).substringAfter("/ ")
            tapPageBar()
            for (step in listOf("−100", "−10", "−1", "+1", "+10", "+100")) compose.onNodeWithText(step).assertIsDisplayed()
            compose.waitForIdle()
            screenshot("page-panel")

            compose.onNodeWithText("+1").performClick()
            assertEquals("2 / $count", awaitPage(2))
            compose.onNodeWithText("取り消す").performClick()
            awaitPage(1)

            compose.onNodeWithContentDescription("ページ番号").performTextReplacement("3")
            compose.onNodeWithContentDescription("ページ番号").performImeAction()
            awaitPage(3)
            compose.onNodeWithText("末尾").performClick()
            awaitPage(count.toInt())

            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription("ページ番号")).fetchSemanticsNodes().isEmpty() }
        }
    }

    @Test
    fun theMenuKeyOpensTheMenuAndRestoringPutsTheBookBack() = runBlocking {
        val (id, intent) = freshBook()
        val services = app.services.await()
        ActivityScenario.launch<MainActivity>(intent).use {
            awaitPage(1)
            alignFrameClockWithUptime()
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT)
            compose.waitUntil(5_000) { pageLabel()?.startsWith("1 ") == false }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_MENU)
            compose.onNodeWithText("位置を戻して閉じる").assertIsDisplayed()
            compose.waitForIdle()
            screenshot("menu")

            withTimeout(15_000) {
                while (services.storage.progress.get(id)?.state != ReadState.Reading) {
                    services.progress.flush().join()
                    delay(100)
                }
            }
            compose.onNodeWithText("位置を戻して閉じる").performClick()
            withTimeout(15_000) {
                while (true) {
                    services.progress.flush().join()
                    val saved = services.storage.progress.get(id)
                    if (saved?.state == ReadState.Unread) {
                        assertEquals("the book has no position again", null, saved.position)
                        break
                    }
                    delay(100)
                }
            }
        }
    }
}
