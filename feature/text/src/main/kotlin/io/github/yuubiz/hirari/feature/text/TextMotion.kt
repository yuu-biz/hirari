package io.github.yuubiz.hirari.feature.text

import io.github.yuubiz.hirari.core.data.GlobalSettings
import io.github.yuubiz.hirari.core.model.BookEndOutcome
import io.github.yuubiz.hirari.core.model.EdgeHit
import io.github.yuubiz.hirari.core.model.Turn
import io.github.yuubiz.hirari.core.model.TurnAnimation
import io.github.yuubiz.hirari.core.model.TurnEffect
import io.github.yuubiz.hirari.core.model.bookEndOutcome
import io.github.yuubiz.hirari.core.model.pullFrame
import io.github.yuubiz.hirari.core.model.turnMovement

/**
 * What moves on screen besides the view at rest (docs/spec.md section 4.8, めくり効果, 端引きでめくる and 本の端での動作),
 * with the same effects as the image reader. Views are named by the source offset or the index of their first page.
 */
internal sealed interface TextMotion {
    fun isDoneAt(nowMs: Long): Boolean

    /** A finger has pulled [pulledDp] toward the view [turn] leads to. */
    data class Pulling(val turn: Turn, val pulledDp: Float) : TextMotion {
        override fun isDoneAt(nowMs: Long) = false
    }

    /** The effect from the view whose first page starts at source offset [from] onto the current one. */
    data class Turning(val from: Int, val animation: TurnAnimation) : TextMotion {
        override fun isDoneAt(nowMs: Long) = animation.isDoneAt(nowMs)
    }

    /** A pull toward [turn] that did not turn, going back to rest. */
    data class Returning(val turn: Turn, val animation: TurnAnimation) : TextMotion {
        override fun isDoneAt(nowMs: Long) = animation.isDoneAt(nowMs)
    }

    /** The bounce of 止まる at an end of the text. */
    data class Bouncing(val hit: EdgeHit) : TextMotion {
        override fun isDoneAt(nowMs: Long) = hit.isDoneAt(nowMs)
    }
}

/** One view to draw: the one whose first page is [head], shifted right by [shift] pixels. */
internal data class TextLayer(val head: Int, val shift: Float, val alpha: Float)

/**
 * The views to draw at [nowMs] for [motion], bottom first, over a view [width] pixels wide whose first page is
 * [current] of [run]. A pull with no view to bring in, at an end of the text, leaves the view at rest.
 */
internal fun textLayers(
    motion: TextMotion?,
    current: Int,
    pagesPerView: Int,
    run: PageRun,
    effect: TurnEffect,
    width: Float,
    density: Float,
    nowMs: Long,
): List<TextLayer> {
    fun neighbor(turn: Turn): Int? = when (turn) {
        Turn.Forward -> (current + pagesPerView).takeIf { it <= run.lastIndex }
        Turn.Back -> if (current > 0) maxOf(0, current - pagesPerView) else null
    }
    val rest = listOf(TextLayer(current, 0f, 1f))
    val (frame, outgoing, incoming) = when (motion) {
        null -> return rest
        is TextMotion.Bouncing -> return listOf(TextLayer(current, bounceShift(motion, density, nowMs), 1f))
        is TextMotion.Pulling -> {
            val next = neighbor(motion.turn) ?: return rest
            Triple(pullFrame(effect, turnMovement(motion.turn, TEXT_DIRECTION), pulledShare(motion.pulledDp, width, density)), current, next)
        }
        is TextMotion.Returning -> {
            val next = neighbor(motion.turn) ?: return rest
            Triple(motion.animation.frameAt(nowMs), current, next)
        }
        is TextMotion.Turning -> Triple(motion.animation.frameAt(nowMs), run.indexOf(motion.from), current)
    }
    val out = TextLayer(outgoing, frame.outgoingShift * width, 1f)
    val into = TextLayer(incoming, frame.incomingShift * width, frame.incomingAlpha)
    return if (frame.incomingOnTop) listOf(out, into) else listOf(into, out)
}

/** How far the bounce of 止まる has moved what is on screen at [nowMs], in pixels; the 連続 row moves by it as a whole. */
internal fun bounceShift(motion: TextMotion?, density: Float, nowMs: Long): Float =
    (motion as? TextMotion.Bouncing)?.let { it.hit.bounceDpAt(nowMs, TEXT_DIRECTION) * density } ?: 0f

/** The share of a view [width] pixels wide that a pull of [pulledDp] has brought in. */
internal fun pulledShare(pulledDp: Float, width: Float, density: Float): Float = pulledDp * density / width

/**
 * The motion after a turn toward [turn] that did [result]: the effect from the view it left, which continues from
 * [pulled], the share a finger had pulled, or the bounce at an end. The 連続 layout scrolls its row instead.
 */
internal fun motionAfter(result: TurnResult, turn: Turn, pulled: Float?, settings: GlobalSettings, continuous: Boolean, nowMs: Long): TextMotion? =
    when (result) {
        is TurnResult.Moved -> if (continuous) {
            null
        } else {
            val movement = turnMovement(turn, TEXT_DIRECTION)
            val duration = settings.turnDurationMs.toLong()
            TextMotion.Turning(
                result.from,
                if (pulled == null) {
                    TurnAnimation.turn(settings.turnEffect, movement, nowMs, duration)
                } else {
                    TurnAnimation.finishPull(settings.turnEffect, movement, pulled, nowMs, duration)
                },
            )
        }
        TurnResult.Waiting -> null
        is TurnResult.Edge ->
            TextMotion.Bouncing(EdgeHit(result.edge, nowMs)).takeIf { bookEndOutcome(result.edge, settings.atBookEnd, autoPlaying = false) == BookEndOutcome.Bounce }
    }
