package io.github.yuubiz.hirari.feature.settings

import kotlin.math.roundToInt

/** The tick of a slider with [count] ticks that [raw] is on. Material 3 hands back positions a hair off a tick, such as 30.999998. */
internal fun sliderIndex(raw: Float, count: Int): Int = raw.roundToInt().coerceIn(0, (count - 1).coerceAtLeast(0))
