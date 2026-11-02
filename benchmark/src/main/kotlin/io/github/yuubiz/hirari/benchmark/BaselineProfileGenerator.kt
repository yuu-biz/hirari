package io.github.yuubiz.hirari.benchmark

import android.view.KeyEvent
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.regex.Pattern

private const val PACKAGE = "io.github.yuubiz.hirari"
private const val PLACE = "hirari-bench"
private const val BOOK = "images.zip"
private const val TIMEOUT_MS = 10_000L
private const val TURNS = 6
private const val CLICK_ATTEMPTS = 3
private const val ITERATIONS = 6
private const val STABLE_ITERATIONS = 2

/**
 * The profile of docs/spec.md section 9, M6: the start to the home screen, then the paths of P4 and P7. The second
 * generator adds a folder of the device as a place through the system's folder picker, opens it, opens a corpus
 * book from its file list, and turns pages.
 *
 * Run with `./gradlew :app:generateBaselineProfile` on a device or emulator whose system folder picker is the
 * one of AOSP or Google.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)

    @Test
    fun coldStartToHome() = rule.collect(
        PACKAGE,
        maxIterations = ITERATIONS,
        stableIterations = STABLE_ITERATIONS,
        includeInStartupProfile = true,
    ) {
        pressHome()
        startActivityAndWait()
    }

    @Test
    fun readBook() {
        stageBook()
        rule.collect(PACKAGE, maxIterations = ITERATIONS, stableIterations = STABLE_ITERATIONS) {
            device.executeShellCommand("pm clear $PACKAGE")
            pressHome()
            startActivityAndWait()
            if (!device.hasObject(By.pkg(PACKAGE).text(PLACE))) addPlace()
            openFromFileList()
            turnPages()
            closeReader()
        }
    }

    /** The corpus book goes to the top of the shared storage, where the system's folder picker lists it. */
    private fun stageBook() {
        val copy = File(checkNotNull(instrumentation.targetContext.externalCacheDir), BOOK)
        instrumentation.context.assets.open("zip/$BOOK").use { input -> copy.outputStream().use { input.copyTo(it) } }
        val place = "/sdcard/$PLACE"
        device.executeShellCommand("rm -rf $place")
        device.executeShellCommand("mkdir -p $place")
        device.executeShellCommand("cp ${copy.absolutePath} $place/$BOOK")
    }

    private fun addPlace() {
        val picker = Pattern.compile(".*documentsui")
        click(By.pkg(PACKAGE).text(appString("place_add")))
        click(By.pkg(PACKAGE).text(appString("place_add_folder")))
        click(By.pkg(picker).text(PLACE))
        click(By.pkg(picker).res("android:id/button1"))
        check(device.wait(Until.hasObject(By.pkg(picker).res(Pattern.compile(".*:id/alertTitle"))), TIMEOUT_MS)) {
            "the system did not ask to allow the folder"
        }
        click(By.pkg(picker).res("android:id/button1"))
        check(device.wait(Until.hasObject(By.pkg(PACKAGE).text(PLACE)), TIMEOUT_MS)) { "the place was not added" }
    }

    private fun openFromFileList() {
        click(By.pkg(PACKAGE).text(PLACE))
        click(By.pkg(PACKAGE).text(BOOK))
        check(device.wait(Until.gone(By.pkg(PACKAGE).text(BOOK)), TIMEOUT_MS)) { "the reader did not open $BOOK" }
        Thread.sleep(1_500)
    }

    /** The system's picker and the app's lists redraw as they load, so a found view can be gone by the click. */
    private fun click(selector: BySelector) {
        repeat(CLICK_ATTEMPTS) {
            try {
                device.wait(Until.findObject(selector), TIMEOUT_MS)?.let {
                    it.click()
                    return
                }
            } catch (_: StaleObjectException) {
            }
        }
        error("no view to click for $selector")
    }

    private fun turnPages() {
        repeat(TURNS) { press(KeyEvent.KEYCODE_DPAD_LEFT) }
        repeat(TURNS) { press(KeyEvent.KEYCODE_DPAD_RIGHT) }
        val x = device.displayWidth / 4
        val y = device.displayHeight / 2
        repeat(2) {
            device.click(x, y)
            Thread.sleep(400)
        }
    }

    private fun press(code: Int) {
        device.pressKeyCode(code)
        Thread.sleep(400)
    }

    private fun closeReader() {
        device.pressBack()
        check(device.wait(Until.hasObject(By.pkg(PACKAGE).text(BOOK)), TIMEOUT_MS)) { "the file list did not come back" }
        device.pressBack()
    }

    private fun appString(name: String): String {
        val resources = instrumentation.targetContext.packageManager.getResourcesForApplication(PACKAGE)
        return resources.getString(resources.getIdentifier(name, "string", PACKAGE))
    }
}
