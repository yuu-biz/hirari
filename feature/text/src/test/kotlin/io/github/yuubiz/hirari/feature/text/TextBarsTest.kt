package io.github.yuubiz.hirari.feature.text

import io.github.yuubiz.hirari.core.data.GlobalSettings
import io.github.yuubiz.hirari.core.model.BarDisplay
import io.github.yuubiz.hirari.core.model.BarLayout
import io.github.yuubiz.hirari.core.model.CommandBarEvent
import io.github.yuubiz.hirari.core.model.CommandBarState
import io.github.yuubiz.hirari.core.model.PageBar
import io.github.yuubiz.hirari.core.model.PageBarEffect
import io.github.yuubiz.hirari.core.model.PageBarEvent
import io.github.yuubiz.hirari.core.model.PageBarState
import io.github.yuubiz.hirari.core.model.Point
import io.github.yuubiz.hirari.core.model.ReaderRegion
import io.github.yuubiz.hirari.core.model.Rect
import io.github.yuubiz.hirari.core.model.Size
import io.github.yuubiz.hirari.core.model.TextLayout
import io.github.yuubiz.hirari.core.model.TapLayout
import io.github.yuubiz.hirari.core.model.Turn
import io.github.yuubiz.hirari.core.model.reduce
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TextBarsTest {
    private val portrait = Viewport(822, 1828, 2f)
    private val settings = GlobalSettings(barHeightDp = 60)

    @Test
    fun `the display setting uses screen orientation and reserves three strips only when shown`() {
        for (display in BarDisplay.entries) {
            for (viewport in listOf(portrait, Viewport(1828, 822, 2f), Viewport(822, 822, 2f))) {
                val current = settings.copy(barDisplay = display)
                val shown = display == BarDisplay.Always || display == BarDisplay.Portrait && viewport.width < viewport.height
                val regions = viewport.textRegions(current)
                assertEquals(if (shown) BarLayout.Shown else BarLayout.Hidden, regions.bars)
                assertEquals(ReaderRegion.CommandBar, regions.regionAt(Point(20f, 30f)))
                assertEquals(ReaderRegion.PageBar, regions.regionAt(Point(20f, regions.screen.height - 30f)))
                assertEquals(if (shown) ReaderRegion.CommandRow else ReaderRegion.TurnArea,
                    regions.regionAt(Point(20f, regions.screen.height - 90f)))
                if (shown) {
                    assertEquals(regions.turnArea, regions.pageArea)
                    assertEquals(Rect(0f, 60f, regions.screen.width, regions.screen.height - 120f), regions.pageArea)
                } else {
                    assertEquals(Rect(0f, 0f, regions.screen.width, regions.screen.height), regions.pageArea)
                    assertNull(textCommandRow(regions))
                }
            }
        }
    }

    @Test
    fun `turn taps use the reduced page area and never run in the strips`() {
        for (display in listOf(BarDisplay.Hidden, BarDisplay.Always)) {
            val current = settings.copy(barDisplay = display, tapLayout = TapLayout.TopAndBottomBands, tapBandPercent = 20)
            val regions = portrait.textRegions(current)
            val turns = mutableListOf<Turn>()
            for (y in listOf(30f, 80f, 400f, regions.turnArea.bottom - 10f, 884f)) {
                textTap(current, Point(200f, y), regions) { turns += it }
            }
            regions.commandRow?.let { row -> textTap(current, Point(200f, row.top + 30f), regions) { turns += it } }
            assertEquals(listOf(Turn.Back, Turn.Forward), turns)
        }
    }

    @Test
    fun `single spread auto and continuous frames use the page area and reclaim their own info bands`() {
        for (layout in TextLayout.entries) {
            for (display in listOf(BarDisplay.Hidden, BarDisplay.Always)) {
                val current = settings.copy(textLayout = layout, barDisplay = display)
                val frame = pageFrame(portrait, current)
                val area = portrait.textRegions(current).pageArea
                assertEquals(area.height * portrait.density, frame.pageHeight)
                assertEquals(area.width * portrait.density,
                    frame.pageWidth * frame.pagesPerView + frame.gutter * (frame.pagesPerView - 1))
                assertEquals(display == BarDisplay.Hidden, frame.band > 0f)
                assertEquals(frame.pageHeight - 2 * frame.margin - 2 * frame.band, frame.params.height)
                assertTrue(frame.params.width > 0f && frame.params.height > 0f)
            }
        }
    }

    @Test
    fun `info strip keeps the hidden command bar tap and hold rules in both displays`() {
        val bounds = TextPanelBounds(1f)
        for (display in listOf(BarDisplay.Hidden, BarDisplay.Always)) {
            val regions = portrait.textRegions(settings.copy(barDisplay = display))
            val point = Point(20f, regions.commandBar.top + 30f)
            assertEquals(ReaderRegion.CommandBar, regions.regionAt(point))
            val down = bounds.reduce(CommandBarState.Closed, CommandBarEvent.Down(point, 0, false), 8f)
            assertEquals(CommandBarState.Open, bounds.reduce(down.state, CommandBarEvent.Up(point), 8f).state)
            val hidden = bounds.reduce(CommandBarState.Closed, CommandBarEvent.Down(point, 0, true), 8f)
            assertEquals(CommandBarState.Closed, bounds.reduce(hidden.state, CommandBarEvent.Up(point), 8f).state)
            assertTrue(bounds.reduce(hidden.state, CommandBarEvent.Hold(400), 8f).state.panelVisible)
        }
    }

    @Test
    fun `the visible row executes text commands in order with close and settings at the ends`() {
        val regions = textRegions(Size(600f, 914f), settings.copy(barDisplay = BarDisplay.Always))
        val row = assertNotNull(textCommandRow(regions))
        val commands = TextCommand.entries.filter { it != TextCommand.Settings }
        assertEquals(listOf(TextPanelItem.Close) + commands.map { TextPanelItem.Command(it) } + TextPanelItem.Command(TextCommand.Settings), row.cells)
        assertTrue(row.cellWidth >= 56f)
        val executed = mutableListOf<TextCommand>()
        var closed = 0
        for (index in row.cells.indices) {
            val point = Point((index + 0.5f) * row.cellWidth, row.bounds.top + 20f)
            val item = assertNotNull(row.tapped(point, point, false))
            runTextItem(item, { closed++ }, { error("no overflow") }, executed::add)
            assertNull(row.tapped(point, point, true))
        }
        assertEquals(1, closed)
        assertEquals(commands + TextCommand.Settings, executed)
    }

    @Test
    fun `overflow replaces the last middle cell with more and opens the text panel`() {
        val row = assertNotNull(textCommandRow(portrait.textRegions(settings.copy(barDisplay = BarDisplay.Always))))
        assertEquals(listOf(TextPanelItem.Close) + TextCommand.entries.take(4).map { TextPanelItem.Command(it) } +
            listOf(TextPanelItem.More, TextPanelItem.Command(TextCommand.Settings)), row.cells)
        assertTrue(row.cellWidth >= 56f)
        val point = Point(5.5f * row.cellWidth, row.bounds.top + 20f)
        val item = assertNotNull(row.tapped(point, point, false))
        var panel: TextPanel? = null
        runTextItem(item, { error("not close") }, { panel = it }, { error("not a command") })
        assertEquals(TextPanel.Commands, panel)
        assertNull(row.tapped(point, Point(20f, point.y), false))
    }

    @Test
    fun `shown and hidden page bars have identical tap hold slide cancel and end gestures`() {
        val point = Point(200f, 884f)
        val scripts = listOf(
            listOf(PageBarEvent.Up(point)),
            listOf(PageBarEvent.Hold(400)),
            listOf(PageBarEvent.Move(Point(160f, 884f)), PageBarEvent.Up(Point(160f, 884f))),
            listOf(PageBarEvent.Move(Point(160f, 790f)), PageBarEvent.Up(Point(160f, 790f))),
            listOf(PageBarEvent.Move(Point(0f, 884f)), PageBarEvent.Up(Point(0f, 884f))),
            listOf(PageBarEvent.Move(Point(411f, 884f)), PageBarEvent.Up(Point(411f, 884f))),
        )
        for (script in scripts) {
            val results = listOf(BarDisplay.Hidden, BarDisplay.Always).map { display ->
                val regions = portrait.textRegions(settings.copy(barDisplay = display))
                val bar = PageBar(0f, regions.screen.width, regions.pageBar.top, regions.barHeight, 30, TEXT_DIRECTION, 8f)
                val down = bar.reduce(PageBarState.Idle, PageBarEvent.Down(point, 0, 10))
                script.runningFold(down) { previous, event -> bar.reduce(previous.state, event) }
            }
            assertEquals(results[0], results[1])
        }
        val bar = PageBar(0f, 411f, 854f, 60f, 30, TEXT_DIRECTION, 8f)
        val down = bar.reduce(PageBarState.Idle, PageBarEvent.Down(point, 0, 10))
        assertEquals(PageBarEffect.OpenPagePanel, bar.reduce(down.state, PageBarEvent.Up(point)).effect)
    }
}
