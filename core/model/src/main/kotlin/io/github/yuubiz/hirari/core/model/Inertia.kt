package io.github.yuubiz.hirari.core.model

import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.roundToLong

private const val FRICTION_PER_MS = GLIDE_FRICTION_PER_S / 1000.0

/**
 * The motion that carries on after a finger lets go while moving (docs/spec.md section 4.8), the same for the strip of
 * 縦に連続 and for a view larger than the screen. It starts at [velocityX] and [velocityY] pixels per millisecond,
 * slows to e⁻ᵏ of its speed every second for k = [GLIDE_FRICTION_PER_S], and ends after [durationMs], once it is
 * slower than [GLIDE_STOP_DP_PER_S].
 */
data class Inertia(val velocityX: Double, val velocityY: Double, val startMs: Long, val durationMs: Long) {
    /** How far the motion has carried on each axis by [nowMs], in pixels. */
    fun distanceXAt(nowMs: Long): Double = velocityX * travelled(nowMs)

    fun distanceYAt(nowMs: Long): Double = velocityY * travelled(nowMs)

    fun isDoneAt(nowMs: Long): Boolean = nowMs - startMs >= durationMs

    /** The distance a speed of one pixel per millisecond carries by [nowMs]. */
    private fun travelled(nowMs: Long): Double {
        val t = (nowMs - startMs).coerceIn(0L, durationMs).toDouble()
        return (1.0 - exp(-FRICTION_PER_MS * t)) / FRICTION_PER_MS
    }

    companion object {
        /**
         * The motion of something let go at [dpPerSX] and [dpPerSY] at [nowMs], its speed capped at
         * [GLIDE_MAX_DP_PER_S]. `null` when it is slower than [GLIDE_MIN_DP_PER_S], which does not carry on.
         */
        fun of(dpPerSX: Float, dpPerSY: Float, nowMs: Long, pxPerDp: Float): Inertia? {
            val speed = hypot(dpPerSX.toDouble(), dpPerSY.toDouble())
            if (speed < GLIDE_MIN_DP_PER_S) return null
            val capped = min(speed, GLIDE_MAX_DP_PER_S.toDouble())
            val pxPerMs = capped / speed * pxPerDp / 1000.0
            val durationMs = (ln(capped / GLIDE_STOP_DP_PER_S) / FRICTION_PER_MS).roundToLong()
            return Inertia(dpPerSX * pxPerMs, dpPerSY * pxPerMs, nowMs, durationMs)
        }
    }
}
