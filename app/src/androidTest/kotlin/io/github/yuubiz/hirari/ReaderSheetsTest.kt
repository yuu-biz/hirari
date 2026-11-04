package io.github.yuubiz.hirari

import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.view.KeyEvent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
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

private val PAGE_LABEL = Regex("""(\d+)(?: から (\d+))? ページ目、全 (\d+) ページ""")

/**
 * The page panel and the menu of docs/spec.md section 4.8 on the real activity. The reader's page number is the
 * label TalkBack reads (section 6.7), which is how these tests see where the reader is.
 */
@OptIn(ExperimentalTestApi::class)
class ReaderSheetsTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    @get:Rule
    val layout = OnePageLayout()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val app = context.applicationContext as HirariApp

    @Before
    fun skipFullScreenNotice() {
        val output = instrumentation.uiAutomation.executeShellCommand("settings put secure immersive_mode_confirmations confirmed")
        ParcelFileDescriptor.AutoCloseInputStream(output).use { it.readBytes() }
    }

    /**
     * A corpus book the store has never seen, and the intent that opens it. The operation guide of a first read is
     * marked seen, since while it is up it takes every touch and hides the page from a screen reader.
     */
    private fun freshBook(): Pair<BookId, Intent> = runBlocking {
        val bytes = instrumentation.context.assets.open("zip/images.zip").use { it.readBytes() }
        val file = File(context.cacheDir, "sheets-test.zip").apply { writeBytes(bytes) }
        val id = documentBookId(bytes.copyOf(minOf(bytes.size, BOOK_ID_PREFIX_BYTES)), bytes.size.toLong())
        val services = app.services.await()
        services.storage.launch.markReaderGuideSeen()
        services.storage.progress.save(listOf(BookProgress(id, ReadState.Unread, null, Instant.EPOCH)))
        id to Intent(context, MainActivity::class.java).putExtra("book", Uri.fromFile(file).toString())
    }

    /** The page label of a screen reader (`1 ページ目、全 37 ページ`, `1 から 2 ページ目、全 37 ページ`) as `1 / 37` or `1-2 / 37`. */
    private fun pageLabel(): String? = compose.onAllNodes(SemanticsMatcher("has a page label") { labelOf(it) != null }).fetchSemanticsNodes()
        .firstNotNullOfOrNull { labelOf(it) }

    /** The page's own node carries the label as its description, and the slider of the page panel as its state. */
    private fun labelOf(node: SemanticsNode): String? = listOfNotNull(
        node.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull(),
        node.config.getOrNull(SemanticsProperties.StateDescription),
    ).firstNotNullOfOrNull { PAGE_LABEL.matchEntire(it)?.destructured }
        ?.let { (first, last, total) -> "$first${if (last.isEmpty()) "" else "-$last"} / $total" }

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

    /**
     * A tap the way the system injects it: down and up go through the input dispatcher, [gapMs] apart in event
     * time, as `input tap` (no gap) and UiAutomator do, not through Compose's own test injection. The reader hides the
     * system bars, so its 60 dp page bar ends at the bottom of the real display, which `displayMetrics` leaves short by
     * the navigation bar of a three-button device. The tap lands 54 dp up, inside the bar and above the 48 dp navigation
     * bar, so it cannot press that bar's home button while the system bars are still on their way out.
     */
    private fun systemTapPageBar(gapMs: Long) {
        val metrics = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        context.getSystemService(android.view.WindowManager::class.java).defaultDisplay.getRealMetrics(metrics)
        val x = metrics.widthPixels / 2f
        val y = metrics.heightPixels - 54 * metrics.density
        val downAt = android.os.SystemClock.uptimeMillis()
        fun event(action: Int, at: Long) = android.view.MotionEvent.obtain(downAt, at, action, x, y, 0).apply {
            source = android.view.InputDevice.SOURCE_TOUCHSCREEN
        }
        instrumentation.uiAutomation.injectInputEvent(event(android.view.MotionEvent.ACTION_DOWN, downAt), true)
        if (gapMs > 0) android.os.SystemClock.sleep(gapMs)
        instrumentation.uiAutomation.injectInputEvent(event(android.view.MotionEvent.ACTION_UP, downAt + gapMs), true)
    }

    private fun pagePanelUp() = compose.onAllNodes(hasContentDescription("ページ番号")).fetchSemanticsNodes().isNotEmpty()

    private fun guideUp() = compose.onAllNodes(hasText("どこかをタップすると閉じます")).fetchSemanticsNodes().isNotEmpty()

    /**
     * A short tap on the page bar opens the page panel whatever the gap between its down and up (docs/spec.md
     * section 4.8, ページバー). The operation guide takes every touch while it is up, so the first tap after a
     * book is first read only closes it; a benchmark that taps right after opening a new book sees no panel for
     * that reason, and not because of the bar.
     */
    @Test
    fun aShortTapFromTheSystemOpensThePagePanelAndTheGuideTakesTheFirstOne() {
        val (_, intent) = freshBook()
        ActivityScenario.launch<MainActivity>(intent).use {
            awaitPage(1)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_MENU)
            compose.onNodeWithText("操作ガイド").performClick()
            compose.waitUntil(5_000) { guideUp() }
            systemTapPageBar(gapMs = 0)
            compose.waitUntil(5_000) { !guideUp() }
            compose.waitForIdle()
            assertEquals("the tap that closed the guide did not reach the bar", false, pagePanelUp())

            systemTapPageBar(gapMs = 0)
            compose.waitUntil(5_000) { pagePanelUp() }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            compose.waitUntil(5_000) { !pagePanelUp() }

            systemTapPageBar(gapMs = 60)
            compose.waitUntil(5_000) { pagePanelUp() }
        }
    }

    @Test
    fun thePagePanelMovesTheReaderAndUndoGoesBack() {
        val (_, intent) = freshBook()
        ActivityScenario.launch<MainActivity>(intent).use {
            val count = awaitPage(1).substringAfter("/ ")
            tapPageBar()
            for (step in listOf("100 ページ戻る", "10 ページ戻る", "1 ページ戻る", "1 ページ進む", "10 ページ進む", "100 ページ進む")) {
                compose.waitUntil(5_000) { runCatching { compose.onNodeWithContentDescription(step).assertIsDisplayed() }.isSuccess }
            }
            compose.waitForIdle()
            screenshot("page-panel")

            compose.onNodeWithContentDescription("1 ページ進む").performClick()
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
