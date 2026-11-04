package io.github.yuubiz.hirari

import android.util.Log
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yuubiz.hirari.core.data.GlobalSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

private const val TAG = "ColdStartTest"
private const val HOME_ACTION = "場所を追加"
private const val UNPLANNED_NOTICE = "前回までの起動の記録を読めなかったため、ホームから始めました"

/**
 * The first start of a process (docs/spec.md section 4.3, 起動時の動作). Every test runs in a process of its own with
 * empty app data (the orchestrator of `app/build.gradle.kts`), and none touches [HirariApp] before the activity does:
 * the services are still being built on the background threads when the activity asks for its plan, as they are when
 * a user taps the icon. A test that writes to the files of the settings does so before anything has read them.
 */
@OptIn(ExperimentalTestApi::class)
class ColdStartTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val datastore = File(context.filesDir, "datastore")
    private val settingsFile = File(datastore, "settings.json")
    private val launchFile = File(datastore, "launch.json")

    private fun launcher() = checkNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName))

    private fun shown(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    private fun await(what: String, timeoutMs: Long = 20_000, condition: () -> Boolean) {
        try {
            compose.waitUntil(what, timeoutMs, condition)
        } catch (e: Throwable) {
            Log.e(TAG, "$what; the screen: ${compose.onRoot().printToString(maxDepth = 12)}")
            throw e
        }
    }

    private fun write(file: File, bytes: ByteArray) {
        datastore.mkdirs()
        file.delete()
        file.writeBytes(bytes)
    }

    @Test
    fun theHomeScreenComesUpWhenTheActivityStartsBeforeTheServicesAreBuilt() {
        ActivityScenario.launch<MainActivity>(launcher()).use {
            await("the home screen is shown") { shown(HOME_ACTION) }
        }
    }

    @Test
    fun filesOfTheSettingsAndTheLaunchRecordThatDoNotReadStartAtHomeWithTheDefaults() {
        val garbage = byteArrayOf(0, -1, 0x7b, 0x7b, 0x22, -128, 0, 0x5d)
        write(settingsFile, garbage)
        write(launchFile, garbage)
        ActivityScenario.launch<MainActivity>(launcher()).use {
            await("the home screen is shown") { shown(HOME_ACTION) }
            val app = context.applicationContext as HirariApp
            val settings = runBlocking { app.services.await().storage.settings.settings.first() }
            assertEquals("the settings start over", GlobalSettings(), settings)
            assertTrue("the start read the file of the settings", !settingsFile.readBytes().contentEquals(garbage))
        }
    }

    /** An IOException must reach the start as a notice while every settings collector receives defaults. */
    @Test
    fun aSettingsFileThatCannotBeReadStartsAtHomeAndSaysSo() {
        datastore.mkdirs()
        settingsFile.delete()
        assertTrue(settingsFile.mkdir())
        ActivityScenario.launch<MainActivity>(launcher()).use {
            await("the home screen is shown") { shown(HOME_ACTION) }
            await("the notice names the start that could not be planned") { shown(UNPLANNED_NOTICE) }
        }
    }
}
