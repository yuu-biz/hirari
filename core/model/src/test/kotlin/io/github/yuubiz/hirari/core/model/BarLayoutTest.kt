package io.github.yuubiz.hirari.core.model

import kotlin.test.Test
import kotlin.test.assertEquals

class BarLayoutTest {
    @Test
    fun hiddenBarsKeepTheFullPageAndTheirOriginalTouchRegions() {
        val regions = ReaderRegions(Size(411f, 914f), 60f)
        assertEquals(Rect(0f, 0f, 411f, 914f), regions.pageArea)
        assertEquals(Rect(0f, 60f, 411f, 854f), regions.turnArea)
        assertEquals(ReaderRegion.CommandBar, regions.regionAt(Point(200f, 59f)))
        assertEquals(ReaderRegion.TurnArea, regions.regionAt(Point(200f, 794f)))
        assertEquals(ReaderRegion.PageBar, regions.regionAt(Point(200f, 854f)))
    }

    @Test
    fun shownBarsReserveThreeStripsAndOnlyThePageTurns() {
        val regions = ReaderRegions(Size(411f, 914f), 60f, bars = BarLayout.Shown)
        assertEquals(Rect(0f, 60f, 411f, 794f), regions.pageArea)
        assertEquals(regions.pageArea, regions.turnArea)
        assertEquals(Rect(0f, 794f, 411f, 854f), regions.commandRow)
        for ((y, region) in listOf(
            0f to ReaderRegion.CommandBar, 59f to ReaderRegion.CommandBar,
            60f to ReaderRegion.TurnArea, 793f to ReaderRegion.TurnArea,
            794f to ReaderRegion.CommandRow, 853f to ReaderRegion.CommandRow,
            854f to ReaderRegion.PageBar, 913f to ReaderRegion.PageBar,
        )) assertEquals(region, regions.regionAt(Point(200f, y)))
    }

    @Test
    fun pageBarSlidesAndSystemGestureBandsAreTheSameInBothLayouts() {
        val effects = BarLayout.entries.map { layout ->
            val regions = ReaderRegions(Size(411f, 914f), 60f, bars = layout)
            val bar = PageBar(0f, 411f, regions.pageBar.top, regions.pageBar.height, 200, ReadingDirection.RightToLeft, 8f)
            val down = Point(200f, 880f)
            assertEquals(ReaderRegion.PageBar, regions.regionAt(down))
            assertEquals(setOf(ScreenEdge.Bottom), regions.systemEdgesAt(Point(200f, 913f)))
            val pressed = bar.reduce(PageBarState.Idle, PageBarEvent.Down(down, 0, 20)).state
            val dragged = bar.reduce(pressed, PageBarEvent.Move(Point(170f, 880f))).state
            bar.reduce(dragged, PageBarEvent.Up(Point(170f, 880f))).effect
        }
        assertEquals(listOf(PageBarEffect.GoTo(22), PageBarEffect.GoTo(22)), effects)
    }

    @Test
    fun portraitMeansStrictlyNarrowerThanTallAndEveryChoiceIsExplicit() {
        for ((size, portrait) in listOf(Size(411f, 914f) to true, Size(914f, 411f) to false, Size(500f, 500f) to false)) {
            assertEquals(false, BarDisplay.Hidden.shown(size))
            assertEquals(portrait, BarDisplay.Portrait.shown(size))
            assertEquals(true, BarDisplay.Always.shown(size))
        }
    }
}
