package io.github.yuubiz.hirari

import android.content.Intent
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import android.view.KeyEvent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.printToString
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

private const val TAG = "RouteSmbTest"
private const val PLACE = "route-test-nas"
private val PAGE_LABEL = Regex("""(\d+)(?: から (\d+))? ページ目、全 (\d+) ページ""")

/**
 * The whole route a user takes to read a book from a NAS, on the real activity with the default settings (見開き,
 * which pairs two pages even in portrait; docs/spec.md section 2.5): the SMB form, the file list with its covers, a
 * book of the list, turns by tap through the stop points of the portrait pairs and back, the page panel, the
 * history, and the book again at the view it was left on. Nothing is warmed before the activity and no debug intent
 * starts the book. It needs the Docker Samba of `tools/smb-fixture`, reached from the emulator at 10.0.2.2, and
 * skips without it. The instrumentation arguments are `smbHost`, `smbShare` and `smbBook` (the file in the share,
 * which must be a book of decodable pages: `route.zip` of the fixture), and optionally `smbPort` (445), `smbUser`
 * and `smbPassword` (a guest when empty).
 */
@OptIn(ExperimentalTestApi::class)
class RouteSmbTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val arguments = InstrumentationRegistry.getArguments()
    private val host = arguments.getString("smbHost")
    private val port = arguments.getString("smbPort") ?: "445"
    private val share = arguments.getString("smbShare")
    private val book = arguments.getString("smbBook")
    private val user = arguments.getString("smbUser").orEmpty()
    private val password = arguments.getString("smbPassword").orEmpty()

    @Before
    fun prepare() {
        assumeTrue("no server: pass smbHost, smbShare and smbBook", host != null && share != null && book != null)
        val automation = instrumentation.uiAutomation
        if (Build.VERSION.SDK_INT >= 37) automation.grantRuntimePermission(context.packageName, "android.permission.ACCESS_LOCAL_NETWORK")
        assumeTrue("the server at $host:$port does not answer", reachable(host!!, port.toInt()))
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("settings put secure immersive_mode_confirmations confirmed")).use { it.readBytes() }
    }

    private fun reachable(host: String, port: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(host, port), 3_000) }
        true
    } catch (_: java.io.IOException) {
        false
    }

    private fun launcher(): Intent = checkNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName))

    /** Waits for [condition] and, when it does not hold in time, leaves the semantics tree in the log. */
    private fun await(what: String, timeoutMs: Long = 20_000, condition: () -> Boolean) {
        try {
            compose.waitUntil(what, timeoutMs, condition)
        } catch (e: Throwable) {
            Log.e(TAG, "$what; the screen: ${compose.onRoot().printToString(maxDepth = 12)}")
            throw e
        }
    }

    private fun shown(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    private fun click(text: String) {
        await("$text is shown") { shown(text) }
        compose.onAllNodesWithText(text)[0].performClick()
    }

    private fun field(label: String, value: String) {
        if (value.isEmpty()) return
        val node = compose.onNode(hasText(label) and hasSetTextAction())
        node.performScrollTo().performTextInput(value)
    }

    /** The reader times a turn on uptime and the rule's frame clock counts from zero; see ReaderSheetsTest. */
    private fun alignFrameClockWithUptime() {
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(android.os.SystemClock.uptimeMillis() + 60_000, ignoreFrameDuration = true)
        compose.mainClock.autoAdvance = true
    }

    /** The first and last page of the view and the page count, from the label a screen reader reads (docs/spec.md section 6.7). */
    private fun pages(): Triple<Int, Int, Int>? = compose.onAllNodes(SemanticsMatcher("has a page label") { labelOf(it) != null }).fetchSemanticsNodes()
        .firstNotNullOfOrNull { labelOf(it) }

    private fun labelOf(node: SemanticsNode): Triple<Int, Int, Int>? = listOfNotNull(
        node.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull(),
        node.config.getOrNull(SemanticsProperties.StateDescription),
    ).firstNotNullOfOrNull { PAGE_LABEL.matchEntire(it)?.destructured }
        ?.let { (first, last, total) -> Triple(first.toInt(), last.ifEmpty { first }.toInt(), total.toInt()) }

    private fun awaitView(first: Int) {
        await("the reader shows the view from page $first") { pages()?.first == first }
    }

    /** A tap [xShare] of the way across the page and half way down, which the reader reads as a turn forward (the left) or back (the right edge). */
    private fun tapAcross(xShare: Float) {
        compose.onRoot().performTouchInput { click(Offset(width * xShare, height / 2f)) }
        compose.waitForIdle()
    }

    private fun tapForward() = tapAcross(0.4f)

    private fun tapBack() = tapAcross(0.96f)

    private fun guideUp() = shown("どこかをタップすると閉じます")

    private fun pagePanelUp() = compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("ページ番号"))).fetchSemanticsNodes().isNotEmpty()

    private fun addServer() {
        click("場所を追加")
        click("SMB サーバー")
        await("the form is shown") { shown("接続を試す") }
        field("表示名", PLACE)
        field("ホスト", host!!)
        compose.onNode(hasText("ポート") and hasSetTextAction()).performScrollTo().performTextReplacement(port)
        field("共有名", share!!)
        field("ユーザー名", user)
        field("パスワード", password)
        closeSoftKeyboard()
        compose.onNodeWithText("接続を試す").performScrollTo().performClick()
        await("the connection test passes") { shown("接続できました") }
        closeSoftKeyboard()
        compose.onNodeWithText("保存").performScrollTo().performClick()
        await("the form is closed") { !shown("接続を試す") }
        await("the place is listed") { shown(PLACE) }
        compose.waitForIdle()
    }

    @Test
    fun aBookOfAServerIsReadFromTheListTurnedAndFoundAgainInTheHistory() {
        ActivityScenario.launch<MainActivity>(launcher()).use { first ->
            await("the home screen is shown") { shown("場所を追加") }
            addServer()

            compose.onNodeWithText(PLACE).performClick()
            val name = book!!
            await("the file list shows the book", 30_000) { shown(name) }
            val thumbs = File(context.cacheDir, "thumbs")
            await("the cover of the book is made", 30_000) { thumbs.listFiles { f -> f.extension == "webp" }?.isNotEmpty() == true }

            compose.onAllNodesWithText(name)[0].performClick()
            await("the operation guide of a first read is shown", 30_000) { guideUp() }
            alignFrameClockWithUptime()
            tapForward()
            await("the first tap only closes the guide") { !guideUp() }
            await("the reader shows the first view", 30_000) { pages() != null }
            val total = pages()!!.third
            assertEquals("a pair of portrait pages is the first view", Triple(1, 2, total), pages())

            tapForward()
            assertEquals("the tap from the right page goes to the left page of the pair", Triple(1, 2, total), pages())
            tapForward()
            awaitView(3)
            repeat(2) { tapForward() }
            awaitView(5)
            tapBack()
            tapBack()
            awaitView(3)

            compose.onNodeWithContentDescription("ページバー").performClick()
            await("the page panel is shown") { pagePanelUp() }
            compose.onNodeWithContentDescription("ページ番号").performTextReplacement("13")
            compose.onNodeWithContentDescription("ページ番号").performImeAction()
            awaitView(13)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            await("the page panel is closed") { !pagePanelUp() }

            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            await("the file list is back", 30_000) { shown(name) && pages() == null }
            runBlocking { (context.applicationContext as HirariApp).services.await().progress.flush().join() }
            first.close()
        }

        ActivityScenario.launch<MainActivity>(launcher()).use {
            await("the home screen is shown again") { shown("場所を追加") }
            assertTrue("a start with the default settings opens no book on its own", pages() == null)
            click("履歴")
            await("the history lists the book", 30_000) { shown(book!!) }
            compose.onAllNodesWithText(book!!)[0].performClick()
            await("the reader opens", 30_000) { pages() != null }
            assertEquals("the book reopens at the view it was left on", 13, pages()!!.first)
            assertTrue("the guide is not shown again", !guideUp())
            repeat(2) { tapForward() }
            awaitView(15)
        }
    }
}
