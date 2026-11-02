package io.github.yuubiz.hirari.core.model

import java.nio.charset.Charset

enum class ScaleMode { FitScreen, FitWidth, FitHeight, Original, Stretch }

enum class TrimStrength { Off, Weak, Medium, Strong, Strongest }

enum class Rotation(val degrees: Int) { None(0), Quarter(90), Half(180), ThreeQuarters(270) }

data class ImageAdjust(
    /** −5 to +5. One scale for gamma and brightness. */
    val level: Int = 0,
    val sharpen: Boolean = false,
    val mono: Boolean = false,
    val negative: Boolean = false,
) {
    init {
        require(level in -5..5) { "level $level is outside -5..5" }
    }
}

/** Each `null` follows the global setting. */
data class BookOverrides(
    val direction: ReadingDirection? = null,
    val layoutMode: LayoutMode? = null,
    val coverAlone: Boolean? = null,
    val scaleMode: ScaleMode? = null,
    val trim: TrimStrength? = null,
    val rotation: Rotation? = null,
    val reverseOrder: Boolean? = null,
    val nameCharset: Charset? = null,
    val adjust: ImageAdjust? = null,
)
