package io.github.yuubiz.hirari.feature.settings

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class AboutInfoTest {
    @get:Rule
    val rule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `the build date is the day of the commit time in its own offset`() {
        val info = AboutInfo.parse("0.0.1", """{"commitTime":"2026-11-03T05:14:45+09:00"}""", "changes")
        assertEquals(LocalDate.of(2026, 11, 3), info.builtOn)
        assertEquals("2026年11月3日", AboutInfo.dateText(info.builtOn!!))
    }

    @Test
    fun `a build without a commit time has no build date`() {
        assertNull(AboutInfo.parse("0.0.1", """{"commitTime":null}""", "changes").builtOn)
        assertNull(AboutInfo.parse("0.0.1", "{}", "changes").builtOn)
    }

    @Test
    fun `a build date that cannot be read is left out`() {
        assertNull(AboutInfo.parse("0.0.1", "not json", "changes").builtOn)
        assertNull(AboutInfo.parse("0.0.1", """{"commitTime":"yesterday"}""", "changes").builtOn)
        assertNull(AboutInfo.parse("0.0.1", null, "changes").builtOn)
    }

    @Test
    fun `blank parts are missing parts`() {
        val info = AboutInfo.parse(" ", """{"commitTime":null}""", "\n  \n")
        assertNull(info.version)
        assertNull(info.changes)
        assertEquals("変更点", AboutInfo.parse("1", null, "\n変更点\n").changes)
    }

    @Test
    fun `the bundled assets give the changes of this version and a build date or none`() {
        val info = runBlocking { BundledAbout(context, UnconfinedTestDispatcher()).load() }
        val changes = assertNotNull(info.changes, "the changes text is not bundled")
        assertTrue("0.0.1" in changes, changes)
        val buildInfo = context.assets.open(AboutInfo.BUILD_INFO_ASSET).bufferedReader().use { it.readText() }
        assertEquals(AboutInfo.parse(null, buildInfo, null).builtOn, info.builtOn)
    }

    @Test
    fun `the dialog shows the version, the build date and the changes and opens the licenses`() {
        var licenses = 0
        val info = AboutInfo("0.0.1", LocalDate.of(2026, 11, 3), "最初の版です。")
        rule.setContent { MaterialTheme { AboutDialog({ info }, onOpenLicenses = { licenses++ }, onDismiss = {}) } }
        rule.onNodeWithText("0.0.1").assertExists()
        rule.onNodeWithText("2026年11月3日").assertExists()
        rule.onNodeWithText("最初の版です。").assertExists()
        rule.onNodeWithText(context.getString(R.string.settings_licenses)).performClick()
        assertEquals(1, licenses)
    }

    @Test
    fun `the dialog says what is unknown`() {
        rule.setContent { MaterialTheme { AboutDialog({ AboutInfo(null, null, null) }, onOpenLicenses = {}, onDismiss = {}) } }
        assertEquals(2, rule.onAllNodesWithText(context.getString(R.string.about_unknown)).fetchSemanticsNodes().size)
        rule.onNodeWithText(context.getString(R.string.about_changes_missing)).assertExists()
    }
}
