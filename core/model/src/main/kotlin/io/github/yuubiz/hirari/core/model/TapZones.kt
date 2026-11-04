package io.github.yuubiz.hirari.core.model

enum class Turn { Forward, Back }

enum class ReaderRegion { CommandBar, TurnArea, PageBar }

/** System gestures start at least this close to the top and bottom edges while the system bars are hidden. */
const val SYSTEM_EDGE_DP = 6f

/**
 * How far in from each edge of the screen system gestures start, in dp (docs/spec.md section 4.8, 画面の領域). [top]
 * and [bottom] are where the system bars come out, and [left] and [right] where a drag is the back gesture.
 */
data class SystemGestureEdges(val top: Float, val bottom: Float, val left: Float, val right: Float) {
    companion object {
        /** Before Android 10 no gesture comes in from the sides, and the window reports no gesture insets. */
        val Fixed = SystemGestureEdges(SYSTEM_EDGE_DP, SYSTEM_EDGE_DP, 0f, 0f)

        /** The insets of system gestures from Android 10, never narrower at the top and bottom than [Fixed]. */
        fun of(mandatoryTop: Float, mandatoryBottom: Float, left: Float, right: Float) =
            SystemGestureEdges(maxOf(mandatoryTop, SYSTEM_EDGE_DP), maxOf(mandatoryBottom, SYSTEM_EDGE_DP), left, right)
    }
}

/** The three regions of the reader screen (docs/spec.md section 4.8). Every length is in dp. */
data class ReaderRegions(
    val screen: Size,
    val barHeight: Float,
    val systemBarsHidden: Boolean,
    val edges: SystemGestureEdges = SystemGestureEdges.Fixed,
) {
    val turnArea: Rect get() = Rect(0f, barHeight, screen.width, screen.height - barHeight)

    /** Whether a drag from [point] is the system's back gesture, which the reader leaves to the system. */
    fun startsBackGesture(point: Point): Boolean = point.x < edges.left || point.x > screen.width - edges.right

    /** The region a touch starting at [point] belongs to, or `null` when the reader ignores it. */
    fun regionAt(point: Point): ReaderRegion? = when {
        systemBarsHidden && (point.y <= edges.top || point.y >= screen.height - edges.bottom) -> null
        point.y < barHeight -> ReaderRegion.CommandBar
        point.y >= screen.height - barHeight -> ReaderRegion.PageBar
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
