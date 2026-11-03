package io.github.yuubiz.hirari.core.ui

import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView

/**
 * 画面の明るさ (docs/spec.md section 4.10, `readerBrightness`): the window's brightness at [percent] while a reader is
 * up, or the device's for `null`. Leaving the reader gives the window back to the device.
 */
@Composable
fun ScreenBrightness(percent: Int?) {
    val window = LocalView.current.context.findActivity()?.window
    DisposableEffect(window, percent) {
        window?.attributes = window.attributes.apply { screenBrightness = windowBrightness(percent) ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
        onDispose { window?.attributes = window.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE } }
    }
}

/**
 * The window brightness for 画面の明るさ [percent], from 0 to 1, or `null` to follow the device. 0% is kept just
 * above black, since a window at 0 shows nothing on some devices.
 */
fun windowBrightness(percent: Int?): Float? = percent?.let { (it / 100f).coerceIn(MIN_WINDOW_BRIGHTNESS, 1f) }

private const val MIN_WINDOW_BRIGHTNESS = 0.01f
