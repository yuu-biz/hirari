package io.github.yuubiz.hirari.feature.reader

import io.github.yuubiz.hirari.core.model.Point
import io.github.yuubiz.hirari.core.model.Rect
import io.github.yuubiz.hirari.core.model.Size

enum class Turn { Forward, Back }

enum class ReaderRegion { CommandBar, TurnArea, PageBar }

/** System gestures start this close to the top and bottom edges while the system bars are hidden. */
const val SYSTEM_EDGE_DP = 6f

/** The three regions of the reader screen (docs/spec.md section 4.8). Every length is in dp. */
data class ReaderRegions(val screen: Size, val barHeight: Float, val systemBarsHidden: Boolean) {
    val turnArea: Rect get() = Rect(0f, barHeight, screen.width, screen.height - barHeight)

    /** The region a touch starting at [point] belongs to, or `null` when the reader ignores it. */
    fun regionAt(point: Point): ReaderRegion? = when {
        systemBarsHidden && (point.y <= SYSTEM_EDGE_DP || point.y >= screen.height - SYSTEM_EDGE_DP) -> null
        point.y < barHeight -> ReaderRegion.CommandBar
        point.y >= screen.height - barHeight -> ReaderRegion.PageBar
        else -> ReaderRegion.TurnArea
    }
}

/** The `tapLayout` setting. */
enum class TapLayout {
    /** The right band goes back and the rest goes forward. */
    SideBand,

    /** The right band goes back, the left band goes forward, and the middle does nothing. */
    SideBands,

    /** The top band goes back and the rest goes forward. */
    TopBand,

    /** The top band goes back, the bottom band goes forward, and the middle does nothing. */
    TopBottomBands,
}

/** Bands on both sides leave room for the middle that does nothing. */
const val TWO_BAND_MAX_PERCENT = 45

/** The tap zones of the turn area. [mirrored] is the `mirrorControls` setting. */
data class TapZones(
    val layout: TapLayout = TapLayout.SideBand,
    val bandPercent: Int = 10,
    val mirrored: Boolean = false,
) {
    init {
        require(bandPercent in 10..90) { "bandPercent $bandPercent is outside 10..90" }
    }

    /** The turn for a tap at [point] in turn-area coordinates, or `null` for the zone that does nothing. */
    fun turnAt(point: Point, area: Size): Turn? {
        val percent = when (layout) {
            TapLayout.SideBand, TapLayout.TopBand -> bandPercent
            TapLayout.SideBands, TapLayout.TopBottomBands -> minOf(bandPercent, TWO_BAND_MAX_PERCENT)
        }
        val fraction = percent / 100f
        return when (layout) {
            TapLayout.SideBand, TapLayout.SideBands -> {
                val band = area.width * fraction
                val (back, forward) = if (mirrored) Side.Near to Side.Far else Side.Far to Side.Near
                when {
                    back.contains(point.x, band, area.width) -> Turn.Back
                    layout == TapLayout.SideBand || forward.contains(point.x, band, area.width) -> Turn.Forward
                    else -> null
                }
            }
            TapLayout.TopBand, TapLayout.TopBottomBands -> {
                val band = area.height * fraction
                when {
                    Side.Near.contains(point.y, band, area.height) -> Turn.Back
                    layout == TapLayout.TopBand || Side.Far.contains(point.y, band, area.height) -> Turn.Forward
                    else -> null
                }
            }
        }
    }

    /** [Near] is the left or top end of an axis, [Far] the right or bottom end. */
    private enum class Side {
        Near,
        Far,
        ;

        fun contains(at: Float, band: Float, length: Float): Boolean =
            if (this == Near) at < band else at >= length - band
    }
}
