package io.github.yuubiz.hirari.core.model

import io.github.yuubiz.hirari.core.model.TapLayout.OneSideBand
import io.github.yuubiz.hirari.core.model.TapLayout.BothSideBands
import io.github.yuubiz.hirari.core.model.TapLayout.TopAndBottom
import io.github.yuubiz.hirari.core.model.TapLayout.TopAndBottomBands
import io.github.yuubiz.hirari.core.model.Turn.Back
import io.github.yuubiz.hirari.core.model.Turn.Forward
import io.kotest.property.Arb
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.float
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class TapZonesTest {
    private val area = Size(400f, 800f)

    private fun TapZones.at(x: Float, y: Float = 400f) = turnAt(Point(x, y), area)

    @Test
    fun theDefaultRightTenthGoesBackAndTheRestForward() {
        val zones = TapZones()
        assertEquals(Back, zones.at(399f))
        assertEquals(Back, zones.at(360f))
        assertEquals(Forward, zones.at(359f))
        assertEquals(Forward, zones.at(0f))
    }

    @Test
    fun mirroringMovesTheBackBandToTheLeft() {
        val zones = TapZones(mirrored = true)
        assertEquals(Back, zones.at(0f))
        assertEquals(Back, zones.at(39f))
        assertEquals(Forward, zones.at(40f))
        assertEquals(Forward, zones.at(399f))
    }

    @Test
    fun sideBandsLeaveTheMiddleDoingNothing() {
        val zones = TapZones(BothSideBands, bandPercent = 20)
        assertEquals(Back, zones.at(320f))
        assertEquals(Forward, zones.at(79f))
        assertNull(zones.at(200f))
        assertNull(zones.at(80f))
        assertNull(zones.at(319f))
    }

    @Test
    fun mirroredSideBandsSwapSides() {
        val zones = TapZones(BothSideBands, bandPercent = 20, mirrored = true)
        assertEquals(Back, zones.at(10f))
        assertEquals(Forward, zones.at(390f))
    }

    @Test
    fun twoBandsAreCappedAtFortyFivePercent() {
        val sides = TapZones(BothSideBands, bandPercent = 90)
        assertEquals(Back, sides.at(220f))
        assertNull(sides.at(219f))
        assertNull(sides.at(180f))
        assertEquals(Forward, sides.at(179f))
        val vertical = TapZones(TopAndBottomBands, bandPercent = 90)
        assertEquals(Back, vertical.at(200f, 359f))
        assertNull(vertical.at(200f, 400f))
        assertEquals(Forward, vertical.at(200f, 440f))
    }

    @Test
    fun aSingleBandIsNotCapped() {
        val zones = TapZones(OneSideBand, bandPercent = 90)
        assertEquals(Back, zones.at(41f))
        assertEquals(Forward, zones.at(39f))
    }

    @Test
    fun theTopBandGoesBackAndTheRestForward() {
        val zones = TapZones(TopAndBottom, bandPercent = 10)
        assertEquals(Back, zones.at(200f, 79f))
        assertEquals(Forward, zones.at(200f, 80f))
        assertEquals(Forward, zones.at(200f, 799f))
    }

    @Test
    fun mirroringDoesNotMoveVerticalBands() {
        val zones = TapZones(TopAndBottomBands, bandPercent = 10, mirrored = true)
        assertEquals(Back, zones.at(200f, 10f))
        assertEquals(Forward, zones.at(200f, 790f))
    }

    @Test
    fun theBandWidthStaysInTheSettingRange() {
        assertFailsWith<IllegalArgumentException> { TapZones(bandPercent = 5) }
        assertFailsWith<IllegalArgumentException> { TapZones(bandPercent = 95) }
    }

    @Test
    fun layoutsWithoutAMiddleAlwaysTurn() = runBlocking<Unit> {
        checkAll(
            Arb.element(OneSideBand, TopAndBottom),
            Arb.int(1..9),
            Arb.boolean(),
            Arb.float(0f..399.9f, includeNaNs = false),
            Arb.float(0f..799.9f, includeNaNs = false),
        ) { layout, tenths, mirrored, x, y ->
            assertNotNull(TapZones(layout, tenths * 10, mirrored).at(x, y), "$layout at $x, $y")
        }
    }

    @Test
    fun aTapStepsThroughStopsOnlyWhenZoomedAndEnabled() {
        assertEquals(ReaderAction.StepStops(Forward), tapAction(Forward, overflows = true, tapAdvancesStops = true))
        assertEquals(ReaderAction.StepStops(Back), tapAction(Back, overflows = true, tapAdvancesStops = true))
        assertEquals(ReaderAction.TurnView(Forward), tapAction(Forward, overflows = true, tapAdvancesStops = false))
        assertEquals(ReaderAction.TurnView(Forward), tapAction(Forward, overflows = false, tapAdvancesStops = true))
    }

    @Test
    fun theBarsSplitTheScreen() {
        val regions = ReaderRegions(Size(400f, 800f), barHeight = 60f, systemBarsHidden = false)
        assertEquals(ReaderRegion.CommandBar, regions.regionAt(Point(200f, 0f)))
        assertEquals(ReaderRegion.CommandBar, regions.regionAt(Point(200f, 59f)))
        assertEquals(ReaderRegion.TurnArea, regions.regionAt(Point(200f, 60f)))
        assertEquals(ReaderRegion.TurnArea, regions.regionAt(Point(200f, 739f)))
        assertEquals(ReaderRegion.PageBar, regions.regionAt(Point(200f, 740f)))
        assertEquals(ReaderRegion.PageBar, regions.regionAt(Point(200f, 800f)))
        assertEquals(Rect(0f, 60f, 400f, 740f), regions.turnArea)
    }

    @Test
    fun hiddenSystemBarsIgnoreTouchesWithinSixDpOfTheTopAndBottom() {
        val regions = ReaderRegions(Size(400f, 800f), barHeight = 60f, systemBarsHidden = true)
        assertNull(regions.regionAt(Point(200f, 6f)))
        assertEquals(ReaderRegion.CommandBar, regions.regionAt(Point(200f, 7f)))
        assertNull(regions.regionAt(Point(200f, 794f)))
        assertEquals(ReaderRegion.PageBar, regions.regionAt(Point(200f, 793f)))
    }
}
