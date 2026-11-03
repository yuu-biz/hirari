package io.github.yuubiz.hirari.benchmark

import android.app.Instrumentation
import android.view.KeyEvent
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.util.regex.Pattern

internal const val PACKAGE = "io.github.yuubiz.hirari"
internal const val TIMEOUT_MS = 10_000L
private const val CLICK_ATTEMPTS = 3
private const val LIST_TIMEOUT_MS = 30_000L
private const val PERMISSION_WAIT_MS = 5_000L
private const val SCROLLS = 3
private const val SCROLL_STEPS = 20
private const val PICKER_LEVELS = 3
private const val PICKER_WAIT_MS = 1_500L

/** The server of P4 on a network: `folder` is empty for the top of the share, and `user` and `password` for a guest. */
internal class ServerSettings(
    val host: String,
    val port: String,
    val share: String,
    val folder: String,
    val user: String,
    val password: String,
)

/**
 * The paths through the app that the Baseline Profile generator and the benchmarks share: adding a folder of the
 * device as a place through the system's folder picker, and opening books from a place's file list.
 */
internal class Driver(private val instrumentation: Instrumentation) {
    val device: UiDevice = UiDevice.getInstance(instrumentation)

    /** A fresh install state: the data and the folder grants are gone, and the app starts at the home screen. */
    fun startClean() {
        device.executeShellCommand("pm clear $PACKAGE")
        device.pressHome()
    }

    fun addPlace(place: String) {
        val picker = Pattern.compile(".*documentsui")
        click(By.pkg(PACKAGE).text(appString("place_add")))
        click(By.pkg(PACKAGE).text(appString("place_add_folder")))
        // The picker opens where it was last used, which may be inside another place; Back goes up a folder.
        repeat(PICKER_LEVELS) {
            if (device.wait(Until.hasObject(By.pkg(picker).text(place)), PICKER_WAIT_MS)) return@repeat
            device.pressBack()
        }
        click(By.pkg(picker).text(place))
        click(By.pkg(picker).res("android:id/button1"))
        check(device.wait(Until.hasObject(By.pkg(picker).res(Pattern.compile(".*:id/alertTitle"))), TIMEOUT_MS)) {
            "the system did not ask to allow the folder"
        }
        click(By.pkg(picker).res("android:id/button1"))
        check(device.wait(Until.hasObject(By.pkg(PACKAGE).text(place)), TIMEOUT_MS)) { "the place was not added" }
    }

    /**
     * Adds the SMB server of [server] as a place named [name] through the form of the app. The fields are filled
     * in the order of the form, one Tab apart, so the form scrolls itself to each.
     */
    fun addServer(name: String, server: ServerSettings) {
        click(By.pkg(PACKAGE).text(appString("place_add")))
        click(By.pkg(PACKAGE).text(appString("place_add_server")))
        click(By.pkg(PACKAGE).clazz("android.widget.EditText"))
        val fields = listOf(name, server.host, server.port, server.share, server.folder, "", server.user, server.password)
        for (value in fields) {
            if (value.isNotEmpty()) device.findObject(By.pkg(PACKAGE).focused(true)).text = value
            device.pressKeyCode(KeyEvent.KEYCODE_TAB)
        }
        val save = By.pkg(PACKAGE).text(appString("smb_save"))
        repeat(SCROLLS) {
            if (device.hasObject(save)) return@repeat
            device.swipe(device.displayWidth / 2, device.displayHeight * 2 / 3, device.displayWidth / 2, device.displayHeight / 3, SCROLL_STEPS)
        }
        click(save)
        if (!device.wait(Until.hasObject(By.pkg(PACKAGE).text(name)), TIMEOUT_MS)) fail("the server was not added")
    }

    /** Opens the place's file list and waits until [first] is listed. */
    fun openPlace(place: String, first: String, onNetwork: Boolean = false) {
        click(By.pkg(PACKAGE).text(place))
        if (onNetwork) {
            // The first connection of a fresh install asks to reach the local network.
            val allow = device.wait(Until.findObject(By.res(Pattern.compile(".*:id/permission_allow_button"))), PERMISSION_WAIT_MS)
            if (allow != null) {
                allow.click()
                // The tap that asked is not carried on once the system has answered.
                if (!device.wait(Until.hasObject(By.pkg(PACKAGE).text(first)), PERMISSION_WAIT_MS)) click(By.pkg(PACKAGE).text(place))
            }
        }
        if (!device.wait(Until.hasObject(By.pkg(PACKAGE).text(first)), LIST_TIMEOUT_MS)) fail("$first was not listed in $place")
    }

    /** Taps [name] in the open file list and waits until the list is gone behind the reader. */
    fun open(name: String) {
        click(By.pkg(PACKAGE).text(name))
        check(device.wait(Until.gone(By.pkg(PACKAGE).text(name)), TIMEOUT_MS)) { "the reader did not open $name" }
    }

    fun openFromFileList(place: String, name: String) {
        click(By.pkg(PACKAGE).text(place))
        open(name)
    }

    /** The system's picker and the app's lists redraw as they load, so a found view can be gone by the click. */
    fun click(selector: BySelector) {
        repeat(CLICK_ATTEMPTS) {
            try {
                device.wait(Until.findObject(selector), TIMEOUT_MS)?.let {
                    it.click()
                    return
                }
            } catch (_: StaleObjectException) {
            }
        }
        fail("no view to click for $selector")
    }

    /** Fails with what the screen shows, and leaves a picture of it at `/sdcard/hirari-failure.png`. */
    private fun fail(message: String): Nothing {
        device.executeShellCommand("screencap -p /sdcard/hirari-failure.png")
        val shown = device.findObjects(By.text(Pattern.compile(".+"))).joinToString { "${it.applicationPackage}:${it.text}" }
        error("$message; the screen shows $shown")
    }

    fun appString(name: String, vararg args: Any): String {
        val resources = instrumentation.targetContext.packageManager.getResourcesForApplication(PACKAGE)
        return resources.getString(resources.getIdentifier(name, "string", PACKAGE), *args)
    }
}
