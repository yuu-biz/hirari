package io.github.yuubiz.hirari.feature.reader

import io.github.yuubiz.hirari.core.model.PageShape
import io.github.yuubiz.hirari.core.model.PageView
import io.github.yuubiz.hirari.core.model.ReadingDirection
import io.github.yuubiz.hirari.core.model.Rect
import io.github.yuubiz.hirari.core.model.ScaleMode
import io.github.yuubiz.hirari.core.model.Side
import io.github.yuubiz.hirari.core.model.Size
import io.github.yuubiz.hirari.core.model.leadingSide
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * A view laid out in image pixels before scaling. [pages] holds one rectangle per page in reading
 * order, placed where the page sits on screen, as `scrollStops` takes them.
 */
data class ViewContent(val size: Size, val pages: List<Rect>) {
    /** The page rectangles in screen pixels at [scale]. */
    fun pagesAt(scale: Scale): List<Rect> = pages.map { Rect(it.left * scale.x, it.top * scale.y, it.right * scale.x, it.bottom * scale.y) }
}

/**
 * Lays out [view] (docs/spec.md section 4.8, 見開き). A pair grows its lower page to the height
 * of the taller one and puts [gapPx] between them. A half is the half of the page it shows.
 */
fun viewContent(view: PageView, shape: (Int) -> PageShape.Known, direction: ReadingDirection, gapPx: Float = 0f): ViewContent =
    when (view) {
        is PageView.One -> shape(view.page).let { single(it.width.toFloat(), it.height.toFloat()) }
        is PageView.Half -> shape(view.page).let { single(it.width / 2f, it.height.toFloat()) }
        is PageView.Two -> {
            val earlier = shape(view.earlier)
            val later = shape(view.later)
            val height = maxOf(earlier.height, later.height).toFloat()
            val earlierWidth = earlier.width * height / earlier.height
            val laterWidth = later.width * height / later.height
            val (left, right) = if (direction.leadingSide == Side.Left) earlierWidth to laterWidth else laterWidth to earlierWidth
            val leftRect = Rect(0f, 0f, left, height)
            val rightRect = Rect(left + gapPx, 0f, left + gapPx + right, height)
            val inOrder = if (direction.leadingSide == Side.Left) listOf(leftRect, rightRect) else listOf(rightRect, leftRect)
            ViewContent(Size(left + gapPx + right, height), inOrder)
        }
    }

private fun single(width: Float, height: Float) = ViewContent(Size(width, height), listOf(Rect(0f, 0f, width, height)))

/** Screen pixels per image pixel on each axis. Only [ScaleMode.Stretch] makes them differ. */
data class Scale(val x: Float, val y: Float) {
    val smaller: Float get() = min(x, y)
    val larger: Float get() = max(x, y)

    operator fun times(factor: Float) = Scale(x * factor, y * factor)
}

/**
 * The scale of [mode] for [content] on [viewport], both in pixels (docs/spec.md section 4.8,
 * 拡大モード). [capAtOriginal] keeps it at or under one image pixel per screen pixel.
 */
fun modeScale(mode: ScaleMode, content: Size, viewport: Size, capAtOriginal: Boolean): Scale {
    val byWidth = viewport.width / content.width
    val byHeight = viewport.height / content.height
    val scale = when (mode) {
        ScaleMode.FitPage -> min(viewport.width * frames(content) / frames(viewport) / content.width, byHeight).let { Scale(it, it) }
        ScaleMode.FitScreen -> min(byWidth, byHeight).let { Scale(it, it) }
        ScaleMode.FitWidth -> Scale(byWidth, byWidth)
        ScaleMode.FitHeight -> Scale(byHeight, byHeight)
        ScaleMode.Original -> Scale(1f, 1f)
        ScaleMode.Stretch -> Scale(byWidth, byHeight)
    }
    return if (capAtOriginal) Scale(min(scale.x, 1f), min(scale.y, 1f)) else scale
}

/**
 * The frames of [size] in [ScaleMode.FitPage]: two for a landscape shape, one for any other. A view is fitted into as
 * many frames as it has, each a screen wide on a portrait screen and half a screen on a landscape one.
 */
private fun frames(size: Size): Int = if (size.width > size.height) 2 else 1

const val PINCH_START_DP = 20f
const val PINCH_MIN_OF_FIT = 0.5f
const val PINCH_MAX_OF_FIT = 8f

/** The zoom changes with the span ratio to this power, so a wider pinch zooms faster. */
const val PINCH_GAIN = 1.5f
const val RESET_TAPS_WINDOW_MS = 1_000L

/**
 * The range of the zoom factor over [mode], the scale of the scale mode, given [fit], the scale
 * of [ScaleMode.FitScreen].
 */
data class ZoomLimits(val mode: Scale, val fit: Float, val capAtOriginal: Boolean) {
    val minFactor: Float get() = PINCH_MIN_OF_FIT * fit / mode.smaller
    val maxFactor: Float get() {
        val byFit = PINCH_MAX_OF_FIT * fit / mode.smaller
        val top = if (capAtOriginal) min(byFit, 1f / mode.larger) else byFit
        return maxOf(top, minFactor)
    }
}

/**
 * The pinch zoom over the scale mode (docs/spec.md section 4.8, ピンチと拡大率). [factor] 1 is the
 * scale mode itself and stays across turns. [pinch] is set while two fingers are down.
 */
data class ZoomState(val factor: Float = 1f, val pinch: Pinch? = null, val lastResetTapMs: Long? = null) {
    /** The `150%` shown during a pinch. */
    val percent: Int get() = (factor * 100f).roundToInt()

    /** [startSpan] is in dp. Until [active], the span has not changed by [PINCH_START_DP]. */
    data class Pinch(val startSpan: Float, val startFactor: Float, val active: Boolean)

    fun reduce(event: ZoomEvent, limits: ZoomLimits): ZoomState = when (event) {
        is ZoomEvent.FingersDown -> copy(pinch = Pinch(event.span, factor, active = false))
        is ZoomEvent.SpanChanged -> when {
            pinch == null -> this
            pinch.active -> {
                val zoomed = pinch.startFactor * (event.span / pinch.startSpan).pow(PINCH_GAIN)
                copy(factor = zoomed.coerceIn(limits.minFactor, limits.maxFactor))
            }
            abs(event.span - pinch.startSpan) >= PINCH_START_DP -> copy(pinch = Pinch(event.span, factor, active = true))
            else -> this
        }
        is ZoomEvent.SecondFingerTap ->
            if (lastResetTapMs != null && event.timeMs - lastResetTapMs <= RESET_TAPS_WINDOW_MS) {
                copy(factor = 1f, pinch = pinch?.copy(startFactor = 1f, active = false), lastResetTapMs = null)
            } else {
                copy(lastResetTapMs = event.timeMs)
            }
        ZoomEvent.FingersUp -> {
            val belowFit = factor * limits.mode.smaller < limits.fit
            ZoomState(factor = if (belowFit) 1f else factor)
        }
        ZoomEvent.ModeChanged -> ZoomState()
    }
}

sealed interface ZoomEvent {
    /** [span] is the distance between the two fingers in dp. */
    data class FingersDown(val span: Float) : ZoomEvent
    data class SpanChanged(val span: Float) : ZoomEvent

    /** The second finger tapped while the first stayed down. */
    data class SecondFingerTap(val timeMs: Long) : ZoomEvent
    data object FingersUp : ZoomEvent

    /** The scale mode changed. */
    data object ModeChanged : ZoomEvent
}
