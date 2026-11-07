package io.github.yuubiz.hirari.core.model

import kotlin.math.abs

enum class Turn { Forward, Back }

enum class ReaderRegion { CommandBar, CommandRow, TurnArea, PageBar }

enum class BarLayout { Hidden, Shown }

/** The system's swipes from the top and bottom edges start at least this close to them. */
const val SYSTEM_EDGE_DP = 6f

/** An edge of the screen a system gesture comes in from. */
enum class ScreenEdge {
    Top,
    Bottom,
    Left,
    Right,
    ;

    /** Whether a drag of [dx] and [dy] goes into the screen from this edge, more across the edge than along it. */
    fun into(dx: Float, dy: Float): Boolean = when (this) {
        Top -> dy > 0f && dy >= abs(dx)
        Bottom -> dy < 0f && -dy >= abs(dx)
        Left -> dx > 0f && dx >= abs(dy)
        Right -> dx < 0f && -dx >= abs(dy)
    }
}

/** Whether a drag from [origin] to [point], of a touch that went down in the bands of these edges, is the system's swipe. */
fun Set<ScreenEdge>.takeSwipe(origin: Point, point: Point): Boolean = any { it.into(point.x - origin.x, point.y - origin.y) }

/**
 * How far in from each edge of the screen the system's swipes start, in dp (docs/spec.md section 4.8, 画面の領域).
 * [top] and [bottom] are where the system bars and the home gesture come in, and [left] and [right] where the back
 * gesture does. Only a swipe into the screen from a band is the system's; a tap, a hold, or a drag along the edge
 * stays the reader's.
 */
data class SystemGestureEdges(val top: Float, val bottom: Float, val left: Float, val right: Float) {
    companion object {
        /** Before Android 10 no gesture comes in from the sides, and the window reports no gesture insets. */
        val Fixed = SystemGestureEdges(SYSTEM_EDGE_DP, SYSTEM_EDGE_DP, 0f, 0f)

        /** The insets of system gestures from Android 10, never narrower at the top and bottom than [Fixed]. */
        fun of(mandatoryTop: Float, mandatoryBottom: Float, left: Float, right: Float) =
            SystemGestureEdges(maxOf(mandatoryTop, SYSTEM_EDGE_DP), maxOf(mandatoryBottom, SYSTEM_EDGE_DP), left, right)
    }

    /**
     * The bands of a reader laid inside the shown system bars, which lie [bottom], [left], and [right] in from those
     * edges of the screen. The shown status bar takes the swipe from the top itself, and none starts below it.
     */
    fun insideShownBars(bottom: Float, left: Float, right: Float) = SystemGestureEdges(
        top = 0f,
        bottom = (this.bottom - bottom).coerceAtLeast(0f),
        left = (this.left - left).coerceAtLeast(0f),
        right = (this.right - right).coerceAtLeast(0f),
    )
}

/** The touch regions of the reader screen (docs/spec.md section 4.8). Every length is in screen dp. */
data class ReaderRegions(
    val screen: Size,
    val barHeight: Float,
    val edges: SystemGestureEdges = SystemGestureEdges.Fixed,
    val bars: BarLayout = BarLayout.Hidden,
) {
    val commandBar: Rect get() = Rect(0f, 0f, screen.width, barHeight)
    val pageBar: Rect get() = Rect(0f, screen.height - barHeight, screen.width, screen.height)
    val commandRow: Rect? get() = if (bars == BarLayout.Shown) Rect(0f, screen.height - 2 * barHeight, screen.width, screen.height - barHeight) else null
    val turnArea: Rect get() = Rect(0f, barHeight, screen.width, commandRow?.top ?: pageBar.top)
    /** Hidden bars overlay the full page; shown bars reserve their own space. */
    val pageArea: Rect get() = if (bars == BarLayout.Shown) turnArea else Rect(0f, 0f, screen.width, screen.height)

    /** The edges whose bands hold [point], so that a swipe from it into the screen is the system's. */
    fun systemEdgesAt(point: Point): Set<ScreenEdge> = buildSet {
        if (point.y < edges.top) add(ScreenEdge.Top)
        if (point.y > screen.height - edges.bottom) add(ScreenEdge.Bottom)
        if (point.x < edges.left) add(ScreenEdge.Left)
        if (point.x > screen.width - edges.right) add(ScreenEdge.Right)
    }

    /** The region a touch starting at [point] belongs to. */
    fun regionAt(point: Point): ReaderRegion = when {
        point.y < barHeight -> ReaderRegion.CommandBar
        point.y >= screen.height - barHeight -> ReaderRegion.PageBar
        commandRow != null && point.y >= screen.height - 2 * barHeight -> ReaderRegion.CommandRow
        else -> ReaderRegion.TurnArea
    }
}

/**
 * What a tap toward [turn] does. With `tapAdvancesStops` on, a view larger than the screen
 * ([overflows]) steps through its stops before it turns.
 */
fun tapAction(turn: Turn, overflows: Boolean, tapAdvancesStops: Boolean): ReaderAction =
    if (overflows && tapAdvancesStops) ReaderAction.StepStops(turn) else ReaderAction.TurnView(turn)

/** Bands on both sides leave room for the middle that does nothing. */
const val TWO_BAND_MAX_PERCENT = 45

/**
 * The tap zones of the turn area (docs/spec.md section 4.8, タップ領域). [mirrored] is the
 * `mirrorControls` setting. Bands stay where they are whatever the reading direction.
 */
data class TapZones(
    val layout: TapLayout = TapLayout.OneSideBand,
    val bandPercent: Int = 10,
    val mirrored: Boolean = false,
) {
    init {
        require(bandPercent in 10..90) { "bandPercent $bandPercent is outside 10..90" }
    }

    /** The turn for a tap at [point] in turn-area coordinates, or `null` for the zone that does nothing. */
    fun turnAt(point: Point, area: Size): Turn? {
        val percent = when (layout) {
            TapLayout.OneSideBand, TapLayout.TopAndBottom -> bandPercent
            TapLayout.BothSideBands, TapLayout.TopAndBottomBands -> minOf(bandPercent, TWO_BAND_MAX_PERCENT)
        }
        val fraction = percent / 100f
        return when (layout) {
            TapLayout.OneSideBand, TapLayout.BothSideBands -> {
                val band = area.width * fraction
                val (back, forward) = if (mirrored) End.Near to End.Far else End.Far to End.Near
                when {
                    back.contains(point.x, band, area.width) -> Turn.Back
                    layout == TapLayout.OneSideBand || forward.contains(point.x, band, area.width) -> Turn.Forward
                    else -> null
                }
            }
            TapLayout.TopAndBottom, TapLayout.TopAndBottomBands -> {
                val band = area.height * fraction
                when {
                    End.Near.contains(point.y, band, area.height) -> Turn.Back
                    layout == TapLayout.TopAndBottom || End.Far.contains(point.y, band, area.height) -> Turn.Forward
                    else -> null
                }
            }
        }
    }

    /** [Near] is the left or top end of an axis, [Far] the right or bottom end. */
    private enum class End {
        Near,
        Far,
        ;

        fun contains(at: Float, band: Float, length: Float): Boolean =
            if (this == Near) at < band else at >= length - band
    }
}
