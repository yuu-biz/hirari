package io.github.yuubiz.hirari.feature.reader

import io.github.yuubiz.hirari.core.model.BarDisplay
import io.github.yuubiz.hirari.core.model.BarLayout
import io.github.yuubiz.hirari.core.model.CommandBarEvent
import io.github.yuubiz.hirari.core.model.CommandBarState
import io.github.yuubiz.hirari.core.model.LayoutMode
import io.github.yuubiz.hirari.core.model.LayoutSettings
import io.github.yuubiz.hirari.core.model.PageShape
import io.github.yuubiz.hirari.core.model.PageView
import io.github.yuubiz.hirari.core.model.Point
import io.github.yuubiz.hirari.core.model.ReaderCommand
import io.github.yuubiz.hirari.core.model.ReadingDirection
import io.github.yuubiz.hirari.core.model.Rect
import io.github.yuubiz.hirari.core.model.ScaleMode
import io.github.yuubiz.hirari.core.model.ScreenOrientation
import io.github.yuubiz.hirari.core.model.Size
import io.github.yuubiz.hirari.core.model.reduce
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReaderBarsTest {
    private val portrait = Viewport(822f, 1828f, 2f)
    private val config = ReaderConfig(
        layout = LayoutSettings(LayoutMode.Single, ReadingDirection.RightToLeft, false),
        scaleMode = ScaleMode.FitScreen,
    )

    @Test
    fun pagesFitTheReservedAreaAndHiddenBarsStillUseTheWholeScreen() {
        val page = PageShape.Known(411, 914)
        val shown = readerOf(listOf(page), config, portrait)
        val hidden = readerOf(listOf(page), config.copy(barDisplay = BarDisplay.Hidden), portrait)
        assertEquals(Viewport(822f, 1468f, 2f), shown.viewport)
        assertEquals(1468f, assertNotNull(shown.geometry()).size.height, 0.01f)
        assertEquals(1828f, assertNotNull(hidden.geometry()).size.height, 0.01f)
        assertEquals(Size(822f, 1468f), shown.geometry()!!.viewport)
    }

    @Test
    fun pagePixelCoordinatesStartBelowTheInfoStripForPinchesAndLoupes() {
        val area = ReaderArea(portrait, config)
        assertEquals(Point(200f, 0f), area.toPage(Point(200f, 120f)))
        val finger = area.toPage(Point(200f, 300f))
        val state = readerOf(listOf(TALL), config, portrait).reduce(ReaderEvent.LoupeShown(finger))
        assertEquals(Point(200f, 180f), assertNotNull(state.loupe).finger)
        val frame = assertNotNull(state.loupeView(Long.MAX_VALUE)).rect
        assertTrue(frame.top >= 0 && frame.bottom <= state.viewport.height)
        assertEquals(Point(200f, 300f), ReaderArea(portrait, config.copy(barDisplay = BarDisplay.Hidden)).toPage(Point(200f, 300f)))
    }

    @Test
    fun aPinchKeepsThePagePointUnderTheScreenFocusAfterSubtractingTheInfoStrip() {
        val state = readerOf(listOf(PageShape.Known(822, 4000)), config.copy(scaleMode = ScaleMode.FitWidth), portrait)
        val focus = state.area.toPage(Point(400f, 720f))
        val before = assertNotNull(state.positionAt(0))
        val zoomed = state.reduce(ReaderEvent.Zoom(ZoomEvent.FingersDown(100f), focus))
            .reduce(ReaderEvent.Zoom(ZoomEvent.SpanChanged(120f), focus))
            .reduce(ReaderEvent.Zoom(ZoomEvent.SpanChanged(180f), focus))
        val after = assertNotNull(zoomed.positionAt(0))
        assertTrue(zoomed.zoom.factor > 1f)
        assertEquals(before.x + focus.x, (after.x + focus.x) / zoomed.zoom.factor, 0.01f)
        assertEquals(before.y + focus.y, (after.y + focus.y) / zoomed.zoom.factor, 0.01f)
    }

    @Test
    fun portraitOnlyBarsSwitchOnResizeAndSwitchBackAfterUnfolding() {
        val state = readerOf(listOf(TALL), config, portrait)
        assertEquals(state, state.reduce(ReaderEvent.ViewportChanged(portrait)))
        val wide = state.reduce(ReaderEvent.ViewportChanged(Viewport(1828f, 822f, 2f)))
        assertEquals(BarLayout.Hidden, wide.area.bars)
        assertEquals(wide.screen, wide.viewport)
        val square = wide.reduce(ReaderEvent.ViewportChanged(Viewport(1000f, 1000f, 2f)))
        assertEquals(BarLayout.Hidden, square.area.bars)
        val restored = square.reduce(ReaderEvent.ViewportChanged(portrait))
        assertEquals(state.viewport, restored.viewport)
        assertEquals(BarLayout.Shown, restored.area.bars)
        val always = wide.reduce(ReaderEvent.ConfigChanged(config.copy(barDisplay = BarDisplay.Always)))
        assertEquals(Viewport(1828f, 462f, 2f), always.viewport)
    }

    @Test
    fun automaticPairingAndPanelColumnsFollowTheScreenEvenWhenThePageAreaIsLandscape() {
        val state = readerOf(listOf(WIDE), config.copy(layout = config.layout.copy(mode = LayoutMode.Auto)), Viewport(411f, 500f, 1f))
        assertEquals(ScreenOrientation.Landscape, state.viewport.orientation)
        assertIs<PageView.Half>(state.current)
        assertEquals(4, state.commandPanel().columns)
        assertEquals(60f, state.commandPanel().bounds.top)
    }

    @Test
    fun tappingTheInfoStripUsesTheExistingCommandPanelDelay() {
        val panel = readerOf(listOf(TALL), config, portrait).commandPanel()
        val point = Point(100f, 30f)
        val held = panel.reduce(CommandBarState.Closed, CommandBarEvent.Down(point, 0, true), 8f).state
        assertIs<CommandBarState.Holding>(held)
        assertEquals(held, panel.reduce(held, CommandBarEvent.Hold(399), 8f).state)
        val opened = panel.reduce(held, CommandBarEvent.Hold(400), 8f).state
        assertTrue(opened.panelVisible)
        assertEquals(CommandBarState.Open, panel.reduce(opened, CommandBarEvent.Up(point), 8f).state)
        assertTrue(panel.reduce(CommandBarState.Closed, CommandBarEvent.Down(point, 0, false), 8f).state.panelVisible)
    }

    @Test
    fun aRowTapRunsOneCommandAndSlidingAcrossOrAwayCancels() {
        val row = CommandRow(listOf(ReaderCommand.Direction), Rect(0f, 794f, 411f, 854f))
        val point = Point(200f, 820f)
        val command = assertIs<PanelItem.Command>(row.tapped(point, point, moved = false))
        val effect = assertIs<CommandEffect.Configure>(config.effectOf(command.command))
        assertEquals(ReadingDirection.LeftToRight, effect.config.direction)
        assertNull(row.tapped(point, Point(205f, 820f), moved = true))
        assertNull(row.tapped(point, Point(400f, 820f), moved = false))
        assertNull(row.tapped(point, Point(200f, 750f), moved = false))
    }

    @Test
    fun theRowHoldsTheViewTheThreeSlotsAndTheMenuWithABlankForAnEmptySlot() {
        val row = CommandRow(listOf(ReaderCommand.ShiftPairing, null, ReaderCommand.ShiftPairing), Rect(0f, 794f, 411f, 854f))
        val shift = PanelItem.Command(ReaderCommand.ShiftPairing)
        assertEquals(listOf(PanelItem.Command(ReaderCommand.BookSettings), shift, PanelItem.Blank, shift, PanelItem.Menu), row.cells)
        assertTrue(row.cellWidth >= 56f)
        assertEquals(listOf(false, true, true, true, false), row.cells.map(row::isSlot))
        assertEquals(PanelItem.Blank, row.itemAt(Point(row.cellWidth * 2.5f, 820f)))
    }

    @Test
    fun guideZonesAndVerticalStripStepsUseTheSameReservedArea() {
        val state = readerOf(List(4) { TALL }, config.copy(layout = config.layout.copy(mode = LayoutMode.Vertical)), portrait)
        assertEquals(1468f, state.stripScreen.height)
        val guide = guideContent(state.area.regions, state.config).layout
        assertEquals(state.area.regions.commandRow, guide.commandRow)
        assertEquals(Rect(0f, 60f, 411f, 794f), guide.turnArea)
        assertTrue(guide.zones.all { it.rect.top >= 60f && it.rect.bottom <= 794f })
    }
}
