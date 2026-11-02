package io.github.yuubiz.hirari.feature.text

import io.github.yuubiz.hirari.core.data.GlobalSettings
import io.github.yuubiz.hirari.core.model.TapLayout
import io.github.yuubiz.hirari.core.model.VolumeKeys
import kotlin.math.abs
import kotlin.math.hypot

/**
 * What an input asks of the text reader. The rules mirror the image reader's (docs/spec.md sections 4.8 and 4.9);
 * a text always binds on the right, so forward is to the left.
 */
sealed interface TextAction {
    /** One view forward, or back when [views] is negative. */
    data class Turn(val views: Int) : TextAction
    data object First : TextAction
    data object Last : TextAction
    data object OpenMenu : TextAction

    /** Back and Del: close what is open, or the reader. */
    data object Dismiss : TextAction
}

/** The regions of the reader screen, top to bottom (docs/spec.md section 4.8, 画面の領域). */
enum class TextRegion { CommandBar, TurnArea, PageBar }

/** System gestures start this close to the top and bottom edges while the system bars are hidden. */
const val SYSTEM_EDGE_DP = 6f

/** The region of a touch starting [y] dp from the top of a screen [height] dp tall, or null when the system takes it. */
fun regionAt(y: Float, height: Float, settings: GlobalSettings): TextRegion? = when {
    settings.hideSystemBars && (y <= SYSTEM_EDGE_DP || y >= height - SYSTEM_EDGE_DP) -> null
    y < settings.barHeightDp -> TextRegion.CommandBar
    y >= height - settings.barHeightDp -> TextRegion.PageBar
    else -> TextRegion.TurnArea
}

/** Bands on both sides leave the middle, which does nothing. */
private const val TWO_BAND_MAX_PERCENT = 45

/**
 * The turn for a tap at ([x], [y]) in a turn area [width] by [height] (docs/spec.md section 4.8, タップ領域): +1, -1,
 * or null for the middle that does nothing. `mirrorControls` moves the side bands.
 */
fun tapTurn(x: Float, y: Float, width: Float, height: Float, settings: GlobalSettings): Int? {
    val twoBands = settings.tapLayout == TapLayout.BothSideBands || settings.tapLayout == TapLayout.TopAndBottomBands
    val fraction = (if (twoBands) minOf(settings.tapBandPercent, TWO_BAND_MAX_PERCENT) else settings.tapBandPercent) / 100f
    return when (settings.tapLayout) {
        TapLayout.OneSideBand, TapLayout.BothSideBands -> {
            val band = width * fraction
            val nearLeft = x < band
            val nearRight = x >= width - band
            val back = if (settings.mirrorControls) nearLeft else nearRight
            val forward = if (settings.mirrorControls) nearRight else nearLeft
            when {
                back -> -1
                settings.tapLayout == TapLayout.OneSideBand || forward -> 1
                else -> null
            }
        }
        TapLayout.TopAndBottom, TapLayout.TopAndBottomBands -> {
            val band = height * fraction
            when {
                y < band -> -1
                settings.tapLayout == TapLayout.TopAndBottom || y >= height - band -> 1
                else -> null
            }
        }
    }
}

/**
 * The turn for a finger that moved [dx] by [dy] on the turn area, or null for no swipe. A right-bound text goes
 * forward when the finger moves right, unless `mirrorControls` swaps it.
 */
fun swipeTurn(dx: Float, dy: Float, touchSlop: Float, settings: GlobalSettings): Int? {
    if (!settings.swipeToTurn || abs(dx) <= touchSlop || abs(dx) <= abs(dy)) return null
    return if ((dx > 0) != settings.mirrorControls) 1 else -1
}

/** The `android.view.KeyEvent` codes the reader handles, as numbers so the mapping stays off the framework. */
object KeyCodes {
    const val BACK = 4
    const val DPAD_LEFT = 21
    const val DPAD_RIGHT = 22
    const val VOLUME_UP = 24
    const val VOLUME_DOWN = 25
    const val SPACE = 62
    const val ENTER = 66
    const val DEL = 67
    const val MENU = 82
    const val PAGE_UP = 92
    const val PAGE_DOWN = 93
    const val BUTTON_L1 = 102
    const val BUTTON_R1 = 103
    const val MOVE_HOME = 122
    const val MOVE_END = 123
    const val NUMPAD_ENTER = 160
}

sealed interface KeyOutcome {
    /** The system gets the key, as a volume key does when the setting is off. */
    data object NotHandled : KeyOutcome

    /** Taken without acting, as the release of a key the reader acted on. */
    data object Consumed : KeyOutcome
    data class Act(val action: TextAction) : KeyOutcome
}

/**
 * The keys of docs/spec.md section 4.8 (キー操作) for a text. The up and down arrows shift a pairing in the image
 * reader and do nothing here. A held key repeats as more presses.
 */
fun onKey(code: Int, pressed: Boolean, shift: Boolean, settings: GlobalSettings): KeyOutcome {
    val leftGoesForward = !settings.mirrorControls
    val action = when (code) {
        KeyCodes.DPAD_LEFT -> TextAction.Turn(if (leftGoesForward) 1 else -1)
        KeyCodes.DPAD_RIGHT -> TextAction.Turn(if (leftGoesForward) -1 else 1)
        KeyCodes.PAGE_DOWN, KeyCodes.ENTER, KeyCodes.NUMPAD_ENTER, KeyCodes.BUTTON_R1 -> TextAction.Turn(1)
        KeyCodes.PAGE_UP, KeyCodes.BUTTON_L1 -> TextAction.Turn(-1)
        KeyCodes.SPACE -> TextAction.Turn(if (shift) -1 else 1)
        KeyCodes.MOVE_HOME -> TextAction.First
        KeyCodes.MOVE_END -> TextAction.Last
        KeyCodes.MENU -> TextAction.OpenMenu
        KeyCodes.BACK, KeyCodes.DEL -> TextAction.Dismiss
        KeyCodes.VOLUME_DOWN, KeyCodes.VOLUME_UP -> when (settings.volumeKeys) {
            VolumeKeys.Off -> null
            VolumeKeys.DownAdvances -> TextAction.Turn(if (code == KeyCodes.VOLUME_DOWN) 1 else -1)
            VolumeKeys.UpAdvances -> TextAction.Turn(if (code == KeyCodes.VOLUME_UP) 1 else -1)
        }
        else -> null
    } ?: return KeyOutcome.NotHandled
    return if (pressed) KeyOutcome.Act(action) else KeyOutcome.Consumed
}

/** The page bar's unit of travel (docs/spec.md section 4.8, ページバー). */
const val PAGE_BAR_UNIT_DP = 5f

/** Pages moved for a finger [distance] dp from where it pressed the page bar: slow for the first two, then steady. */
fun pageBarPages(distance: Float): Int {
    val units = abs(distance) / PAGE_BAR_UNIT_DP
    val pages = when {
        units < 2 -> 0
        units < 6 -> 1
        units < 9 -> 2
        else -> 3 + (units - 9).toInt()
    }
    return if (distance < 0) -pages else pages
}

/**
 * A finger on the page bar (docs/spec.md section 4.8, ページバー) that pressed at ([downX], [downY]), in dp on the
 * screen. [pages] is how far it moves the reading position, forward when it went left as a text binds on the right.
 * Once it [moved] past the touch slop a release is no tap, and while it is [cancelled], a bar's height or more above
 * the bar, a release goes nowhere.
 */
data class PageBarDrag(
    val downX: Float,
    val downY: Float,
    val pages: Int = 0,
    val moved: Boolean = false,
    val cancelled: Boolean = false,
) {
    /** The finger is at ([x], [y]), with the bar's top edge at [barTop]. */
    fun moveTo(x: Float, y: Float, barTop: Float, barHeight: Float, slop: Float) = copy(
        pages = pageBarPages(downX - x),
        moved = moved || hypot(x - downX, y - downY) > slop,
        cancelled = y <= barTop - barHeight,
    )

    fun release(): PageBarRelease = when {
        !moved -> PageBarRelease.OpenPanel
        cancelled || pages == 0 -> PageBarRelease.None
        else -> PageBarRelease.Jump(pages)
    }
}

sealed interface PageBarRelease {
    /** A tap or a long press, which opens the page panel. */
    data object OpenPanel : PageBarRelease

    data object None : PageBarRelease

    /** Move [pages] pages from the current one, forward when positive. */
    data class Jump(val pages: Int) : PageBarRelease
}
