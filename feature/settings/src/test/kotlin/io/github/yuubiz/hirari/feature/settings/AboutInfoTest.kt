package io.github.yuubiz.hirari.feature.settings

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
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
    fun `the build identity preserves dirty and falls back when missing or unreadable`() {
        val info = AboutInfo.parse("1.0.0-rc.1", """{"commit":"123abcd-dirty"}""", null, 8L)
        assertEquals("1.0.0-rc.1", info.version)
        assertEquals(8L, info.versionCode)
        assertEquals("123abcd-dirty", info.commit)
        assertEquals("123abcd", AboutInfo.parse(null, """{"commit":"123abcd"}""", null).commit)
        for (json in listOf(null, "{}", "not json", """{"commit":" "}""")) {
            assertEquals("unknown", AboutInfo.parse(null, json, null).commit)
        }
    }

    @Test
    fun `the bundled assets give the changes of this version and a build date or none`() {
        val info = runBlocking { BundledAbout(context, UnconfinedTestDispatcher()).load() }
        val changes = assertNotNull(info.changes, "the changes text is not bundled")
        assertTrue("0.0.1" in changes, changes)
        val buildInfo = context.assets.open(AboutInfo.BUILD_INFO_ASSET).bufferedReader().use { it.readText() }
        assertEquals(AboutInfo.parse(null, buildInfo, null).builtOn, info.builtOn)
        assertEquals(AboutInfo.parse(null, buildInfo, null).commit, info.commit)
        assertTrue(Regex("(?:[0-9a-f]{7,}(?:-dirty)?|unknown)").matches(info.commit))
        @Suppress("DEPRECATION")
        val installed = context.packageManager.getPackageInfo(context.packageName, 0)
        assertEquals(installed.versionName, info.version)
        assertEquals(installed.longVersionCode, info.versionCode)
    }

    @Test
    fun `the dialog names the build and opens both browser links and the licenses`() {
        var licenses = 0
        val info = AboutInfo("1.0.0-rc.1", LocalDate.of(2026, 11, 3), "最初の版です。", 8L, "123abcd-dirty")
        rule.setContent { MaterialTheme { AboutDialog({ info }, onOpenLicenses = { licenses++ }, onDismiss = {}) } }
        rule.onNodeWithText("1.0.0-rc.1").assertExists()
        rule.onNodeWithText("8").assertExists()
        rule.onNodeWithText("123abcd-dirty").assertExists()
        rule.onNodeWithText("2026年11月3日").assertExists()
        rule.onNodeWithText("最初の版です。").assertExists()
        val application = shadowOf(context as Application)
        for ((label, url) in listOf(
            R.string.about_source to "https://github.com/yuu-biz/hirari",
            R.string.about_privacy to "https://github.com/yuu-biz/hirari/blob/main/docs/privacy.md",
        )) {
            rule.onNodeWithText(context.getString(label)).performScrollTo().performClick()
            val intent = assertNotNull(application.nextStartedActivity)
            assertEquals(Intent.ACTION_VIEW, intent.action)
            assertEquals(url, intent.dataString)
            assertNull(intent.component)
        }
        rule.onNodeWithText(context.getString(R.string.settings_licenses)).performScrollTo().performClick()
        assertEquals(1, licenses)
    }

    @Test
    fun `the dialog says what is unknown`() {
        rule.setContent { MaterialTheme { AboutDialog({ AboutInfo(null, null, null) }, onOpenLicenses = {}, onDismiss = {}) } }
        assertEquals(3, rule.onAllNodesWithText(context.getString(R.string.about_unknown)).fetchSemanticsNodes().size)
        rule.onNodeWithText("unknown").assertExists()
        rule.onNodeWithText(context.getString(R.string.about_changes_missing)).assertExists()
    }
}
