package io.github.yuubiz.hirari.core.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.github.yuubiz.hirari.core.model.Claims
import io.github.yuubiz.hirari.core.model.ReaderOrientation
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/**
 * What a reader asks of the window while it is up (docs/spec.md section 4.10): システムバーを隠す, the orientation of
 * 画面の向き, 画面の明るさ in percent or `null` for the device's, and whether the screen stays on.
 */
data class ReaderWindow(
    val hideSystemBars: Boolean,
    val orientation: ReaderOrientation,
    val brightness: Int?,
    val keepScreenOn: Boolean,
)

/**
 * Gives the window what [asked] says while the reader is on screen. A reader that opens a book in place of another
 * comes in while the other is still going out, so the window follows the newest reader, and goes back to the device's
 * once no reader holds it.
 */
@Composable
fun HoldReaderWindow(asked: ReaderWindow) {
    val activity = LocalView.current.context.findActivity() ?: return
    val held = remember(activity) { object { var claim: Claims<ReaderWindow>.Claim? = null } }
    DisposableEffect(activity) {
        val claim = activity.readerWindows().claim(asked)
        held.claim = claim
        onDispose { claim.release() }
    }
    SideEffect { held.claim?.value = asked }
}

private val readerWindows = WeakHashMap<Activity, Claims<ReaderWindow>>()

/** The claims reach their activity weakly, or they would keep their own key in [readerWindows] and the activity with it. */
private fun Activity.readerWindows(): Claims<ReaderWindow> = readerWindows.getOrPut(this) {
    val activity = WeakReference(this)
    Claims { activity.get()?.give(it) }
}

private fun Activity.give(asked: ReaderWindow?) {
    val controller = WindowCompat.getInsetsController(window, window.decorView)
    controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    if (asked?.hideSystemBars == true) controller.hide(WindowInsetsCompat.Type.systemBars()) else controller.show(WindowInsetsCompat.Type.systemBars())
    // A rotation recreates the activity, and the reader of the new one asks again for the same orientation.
    if (asked != null || !isChangingConfigurations) {
        requestedOrientation = when (asked?.orientation) {
            null, ReaderOrientation.System -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            ReaderOrientation.Portrait -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            ReaderOrientation.Landscape -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
    }
    window.attributes = window.attributes.apply { screenBrightness = windowBrightness(asked?.brightness) ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
    if (asked?.keepScreenOn == true) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
}
