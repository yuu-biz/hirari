package io.github.yuubiz.hirari.feature.library

import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import io.github.yuubiz.hirari.core.source.smb.DiscoveredServer
import io.github.yuubiz.hirari.core.source.smb.DiscoveredShare
import io.github.yuubiz.hirari.core.source.smb.DiscoverySource
import io.github.yuubiz.hirari.core.source.smb.ServerSearch
import kotlinx.coroutines.flow.flowOf
import java.net.Inet4Address
import java.net.InetAddress
import io.github.yuubiz.hirari.core.source.smb.ShareDiscoveryResult

/** The password field of the SMB server form never lets its text out to the accessibility tree (docs/spec.md section 4.5). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class SmbServerScreenTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun main() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun reset() = Dispatchers.resetMain()

    @Test
    @Config(sdk = [36])
    fun `find shares is disabled without a host and a selected row fills the form without saving`() {
        val fixture = Fixture()
        val model = SmbServerViewModel(CompletableDeferred(fixture.services), null)
        fixture.smb.discovering = { ShareDiscoveryResult.Listed(listOf(DiscoveredShare("books2", 0, "漫画"))) }
        rule.setContent { MaterialTheme { SmbServerScreen(model, onClose = {}) } }
        rule.onNodeWithText("共有を探す").assertIsNotEnabled()
        rule.runOnIdle { model.edit(SmbFields(host = "nas")) }
        rule.onNodeWithText("共有を探す").performClick()
        rule.onNodeWithText("漫画").assertIsDisplayed()
        rule.onNodeWithText("books2").performClick()
        rule.runOnIdle {
            assertEquals("books2", (model.state.value as SmbFormUi.Editing).fields.share)
            assertTrue(fixture.locations.rows.value.isEmpty())
        }
    }

    @Test
    @Config(sdk = [36])
    fun `a found server is chosen from the list and its shares are listed next`() {
        val fixture = Fixture()
        val model = SmbServerViewModel(CompletableDeferred(fixture.services), null)
        val server = DiscoveredServer("My NAS", InetAddress.getByName("192.168.1.5") as Inet4Address, 4450, setOf(DiscoverySource.DnsSd), null)
        fixture.smb.searching = flowOf(ServerSearch.Settled(listOf(server)))
        fixture.smb.discovering = { ShareDiscoveryResult.Listed(listOf(DiscoveredShare("books", 0))) }
        rule.setContent { MaterialTheme { SmbServerScreen(model, onClose = {}) } }
        rule.onNodeWithText("サーバーを探す").performClick()
        rule.onNodeWithText("192.168.1.5:4450").assertIsDisplayed()
        rule.onNodeWithText("もう一度探す").assertIsDisplayed()
        rule.onNodeWithText("My NAS").performClick()
        rule.onNodeWithText("books").assertIsDisplayed()
        rule.runOnIdle {
            val fields = (model.state.value as SmbFormUi.Editing).fields
            assertEquals(SmbFields(displayName = "My NAS", host = "192.168.1.5", port = "4450"), fields)
        }
    }

    @Test
    @Config(sdk = [36])
    fun `an empty search tells how Windows servers are found`() {
        val fixture = Fixture()
        val model = SmbServerViewModel(CompletableDeferred(fixture.services), null)
        fixture.smb.searching = flowOf(ServerSearch.Settled(emptyList()))
        rule.setContent { MaterialTheme { SmbServerScreen(model, onClose = {}) } }
        rule.onNodeWithText("サーバーを探す").performClick()
        rule.onNodeWithText("サーバーが見つかりません。ホストを入力してください").assertIsDisplayed()
        rule.onNodeWithText("Windows のサーバーは、ネットワーク探索と NetBIOS over TCP/IP が有効なときに見つかります").assertIsDisplayed()
    }

    @Test
    fun `the password field is a password to a screen reader and exposes none of what was typed`() {
        val model = SmbServerViewModel(CompletableDeferred(Fixture().services), null)
        rule.setContent { MaterialTheme { SmbServerScreen(model, onClose = {}) } }

        val field = rule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        field.performTextInput(TYPED)

        assertNotNull(field.fetchSemanticsNode().config.getOrNull(SemanticsProperties.Password))
        val info = checkNotNull(checkNotNull(host(rule.activity.window.decorView).accessibilityNodeProvider).createAccessibilityNodeInfo(field.fetchSemanticsNode().id))
        assertTrue(info.isPassword, "the accessibility node is not a password")
        assertEquals("•".repeat(TYPED.length), info.text.toString(), "the accessibility node carries the typed password")
    }

    @Test
    @Config(qualifiers = "w411dp-h640dp")
    fun `the connection test and save buttons can be scrolled into view and clicked on a short screen`() {
        val fixture = Fixture()
        val model = SmbServerViewModel(CompletableDeferred(fixture.services), null)
        model.edit(SmbFields(host = "nas.local", share = "books"))
        var closed = false
        rule.setContent { MaterialTheme { SmbServerScreen(model, onClose = { closed = true }) } }

        rule.onNodeWithText("接続を試す").performScrollTo().assertIsDisplayed().performClick()
        rule.waitForIdle()
        assertEquals(1, fixture.smb.tests.size)
        assertEquals(ConnectionTest.Passed, (model.state.value as SmbFormUi.Editing).test)

        rule.onNodeWithText("保存").performScrollTo().assertIsDisplayed().performClick()
        rule.waitForIdle()
        assertEquals(SmbFormUi.Saved, model.state.value)
        assertEquals(1, fixture.locations.rows.value.size)
        assertTrue(closed, "saving closes the form")
    }

    private fun host(view: View): View {
        if (view.accessibilityNodeProvider != null) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) host(view.getChildAt(i)).takeIf { it.accessibilityNodeProvider != null }?.let { return it }
        return view
    }

    private companion object {
        const val TYPED = "hunter2-secret"
    }
}
