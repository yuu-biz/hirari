package io.github.yuubiz.hirari.feature.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.math.roundToInt

/** The tick of a slider with [count] ticks that [raw] is on. Material 3 hands back positions a hair off a tick, such as 30.999998. */
internal fun sliderIndex(raw: Float, count: Int): Int = raw.roundToInt().coerceIn(0, (count - 1).coerceAtLeast(0))

/**
 * Where a slider shows its thumb while the finger is on it and while the write of the released tick is on its way.
 * The row makes a new one when the stored value moves or a save fails, so the thumb goes back to the stored tick.
 */
internal class SliderDraft(private val count: Int) {
    private var raw by mutableStateOf<Float?>(null)

    fun shown(stored: Int): Int = sliderIndex(raw ?: stored.toFloat(), count)

    fun drag(position: Float) {
        raw = position
    }

    /** Settles the thumb on its tick and returns it. */
    fun release(stored: Int): Int = shown(stored).also { raw = it.toFloat() }
}
