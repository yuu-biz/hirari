package io.github.yuubiz.hirari.core.model

import kotlin.math.roundToLong

/** A pull that does not turn goes back to rest in about this long. */
const val PULL_RETURN_MS = 120L

/**
 * One frame of a turn or a pull. Shifts are in view widths, positive to the right. In a turn the
 * incoming view is already the current one, so the reader draws it from the first frame. In a pull
 * it is the neighbor. [pullHint] is how far a pull has gone, from 0 to 1, for the effect that
 * shows only a hint at the edge.
 */
data class TurnFrame(
    val outgoingShift: Float,
    val incomingShift: Float,
    val incomingAlpha: Float,
    val incomingOnTop: Boolean,
    val pullHint: Float = 0f,
)

/** +1 when the views move right: a right-to-left book moving forward brings the next view in from the left. */
fun turnMovement(turn: Turn, direction: ReadingDirection): Int {
    val rightward = (turn == Turn.Forward) == (direction == ReadingDirection.RightToLeft)
    return if (rightward) 1 else -1
}

/** The frame of [effect] at [progress], where 0 is before the turn and 1 is after it. */
fun turnFrame(effect: TurnEffect, movement: Int, progress: Float): TurnFrame = when (effect) {
    TurnEffect.None ->
        if (progress >= 1f) TurnFrame(0f, 0f, 1f, true) else TurnFrame(0f, 0f, 0f, true, pullHint = progress)
    TurnEffect.Slide -> TurnFrame(movement * progress, movement * (progress - 1f), 1f, false)
    TurnEffect.Cover -> TurnFrame(0f, movement * (progress - 1f), 1f, true)
    TurnEffect.Fade -> TurnFrame(0f, 0f, progress, true)
}

/** While pulling, a fade looks like a slide (docs/spec.md section 4.8, めくり効果). */
private fun TurnEffect.whilePulling(): TurnEffect = if (this == TurnEffect.Fade) TurnEffect.Slide else this

/** The frame while a finger has pulled [pulled] view widths toward the neighbor. */
fun pullFrame(effect: TurnEffect, movement: Int, pulled: Float): TurnFrame =
    turnFrame(effect.whilePulling(), movement, pulled.coerceIn(0f, 1f))

/** A running turn effect, or the return of a pull that did not turn. */
data class TurnAnimation(val effect: TurnEffect, val movement: Int, val motion: Motion) {
    fun frameAt(nowMs: Long): TurnFrame = turnFrame(effect, movement, motion.valueAt(nowMs))

    fun isDoneAt(nowMs: Long): Boolean = motion.isDoneAt(nowMs)

    /** The next input ends the effect at once and is then handled as usual. */
    fun interruptedAt(nowMs: Long): TurnAnimation = copy(motion = motion.interruptedAt(nowMs))

    companion object {
        /** A turn by tap or key. [durationMs] is the `turnDurationMs` setting. */
        fun turn(effect: TurnEffect, movement: Int, nowMs: Long, durationMs: Long): TurnAnimation =
            TurnAnimation(effect, movement, Motion(nowMs, if (effect == TurnEffect.None) 0 else durationMs))

        /** The rest of a pull of [pulled] view widths that turns, at the speed of a whole turn. */
        fun finishPull(effect: TurnEffect, movement: Int, pulled: Float, nowMs: Long, durationMs: Long): TurnAnimation {
            val from = pulled.coerceIn(0f, 1f)
            val duration = if (effect == TurnEffect.None) 0 else (durationMs * (1f - from)).roundToLong()
            return TurnAnimation(effect.whilePulling(), movement, Motion(nowMs, duration, from, 1f))
        }

        /** A pull of [pulled] view widths going back to rest. */
        fun returnPull(effect: TurnEffect, movement: Int, pulled: Float, nowMs: Long): TurnAnimation =
            TurnAnimation(effect.whilePulling(), movement, Motion(nowMs, PULL_RETURN_MS, pulled.coerceIn(0f, 1f), 0f))
    }
}
