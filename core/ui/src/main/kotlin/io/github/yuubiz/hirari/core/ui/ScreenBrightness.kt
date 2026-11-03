package io.github.yuubiz.hirari.core.ui

/**
 * The window brightness for 画面の明るさ [percent] (docs/spec.md section 4.10, `readerBrightness`), from 0 to 1, or
 * `null` to follow the device. 0% is kept just above black, since a window at 0 shows nothing on some devices.
 */
fun windowBrightness(percent: Int?): Float? = percent?.let { (it / 100f).coerceIn(MIN_WINDOW_BRIGHTNESS, 1f) }

private const val MIN_WINDOW_BRIGHTNESS = 0.01f
