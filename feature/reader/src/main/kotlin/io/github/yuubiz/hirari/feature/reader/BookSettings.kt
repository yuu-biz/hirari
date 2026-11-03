package io.github.yuubiz.hirari.feature.reader

import io.github.yuubiz.hirari.core.data.GlobalSettings
import io.github.yuubiz.hirari.core.model.BookOverrides

/**
 * This setup with the values of the 本ごとの設定 sheet taken from [from] (docs/spec.md section 4.8, メニュー):
 * 綴じ方向, 組み方, 表紙の単独表示, 拡大モード, 余白トリミング, 回転, and 画像補正. 元に戻す takes them from the setup the
 * sheet opened on, and 全体の設定に戻す from the one the global settings alone give.
 */
fun ReaderConfig.withBookValues(from: ReaderConfig): ReaderConfig =
    copy(layout = from.layout, scaleMode = from.scaleMode, trim = from.trim, rotation = from.rotation, adjust = from.adjust)

/**
 * Whether 表紙の単独表示, 拡大モード, and 余白トリミング change what this reader shows. 縦に連続 ignores them (docs/spec.md
 * section 4.8), so 本ごとの設定 shows them disabled and keeps their values for the other layouts.
 */
val ReaderConfig.pageFitApplies: Boolean get() = !vertical

/** 既定にする: these settings with the values of [config] that have a global setting. The rotation has none, so it stays the book's. */
fun GlobalSettings.adopting(config: ReaderConfig): GlobalSettings = copy(
    readingDirection = config.direction,
    layoutMode = config.layout.mode,
    coverAlone = config.layout.coverAlone,
    scaleMode = config.scaleMode,
    trimStrength = config.trim,
    adjustLevel = config.adjust.level,
    adjustSharpen = config.adjust.sharpen,
    adjustMono = config.adjust.mono,
    adjustNegative = config.adjust.negative,
)

/** The overrides once 既定にする moved their values into the global settings: those fields follow the global settings again. */
fun BookOverrides.adopted(): BookOverrides = copy(direction = null, layoutMode = null, coverAlone = null, scaleMode = null, trim = null, adjust = null)

/** 並びを逆に set to [on]. Off is stored as no override, since the natural order is what a book has without one. */
fun BookOverrides.reversedTo(on: Boolean): BookOverrides = copy(reverseOrder = if (on) true else null)

/** The steps of 画面の明るさ in percent (docs/spec.md section 4.10, `readerBrightness`). */
val BRIGHTNESS_STEPS: IntProgression = GlobalSettings.READER_BRIGHTNESS

/**
 * The window brightness for 画面の明るさ [percent], from 0 to 1, or `null` to follow the device. 0% is kept just
 * above black, since a window at 0 shows nothing on some devices.
 */
fun windowBrightness(percent: Int?): Float? = percent?.let { (it / 100f).coerceIn(MIN_WINDOW_BRIGHTNESS, 1f) }

private const val MIN_WINDOW_BRIGHTNESS = 0.01f
