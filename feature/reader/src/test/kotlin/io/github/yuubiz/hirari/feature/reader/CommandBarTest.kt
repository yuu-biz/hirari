package io.github.yuubiz.hirari.feature.reader

import io.github.yuubiz.hirari.core.model.CommandBarEffect
import io.github.yuubiz.hirari.core.model.CommandBarEvent
import io.github.yuubiz.hirari.core.model.CommandBarState
import io.github.yuubiz.hirari.core.model.CommandBarStep
import io.github.yuubiz.hirari.core.model.Point
import io.github.yuubiz.hirari.core.model.Rect
import io.github.yuubiz.hirari.core.model.ScreenOrientation.Landscape
import io.github.yuubiz.hirari.core.model.ScreenOrientation.Portrait
import io.github.yuubiz.hirari.core.model.CommandBarEvent.Down
import io.github.yuubiz.hirari.core.model.CommandBarEvent.Hold
import io.github.yuubiz.hirari.core.model.CommandBarEvent.Move
import io.github.yuubiz.hirari.core.model.CommandBarEvent.Up
import io.github.yuubiz.hirari.core.model.reduce
import io.github.yuubiz.hirari.feature.reader.PanelItem.Command
import io.github.yuubiz.hirari.core.model.ReaderCommand.AddBookmark
import io.github.yuubiz.hirari.core.model.ReaderCommand.AutoPlay
import io.github.yuubiz.hirari.core.model.ReaderCommand.Bookmarks
import io.github.yuubiz.hirari.core.model.ReaderCommand.Layout
import io.github.yuubiz.hirari.core.model.ReaderCommand.Orientation
import io.github.yuubiz.hirari.core.model.ReaderCommand.Rotate
import io.github.yuubiz.hirari.core.model.ReaderCommand.ScaleMode
import io.github.yuubiz.hirari.core.model.ReaderCommand.Settings
import io.github.yuubiz.hirari.core.model.ReaderCommand.Trim
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CommandBarTest {
    private val eight = listOf(Orientation, Trim, Layout, ScaleMode, Rotate, AddBookmark, Bookmarks, AutoPlay)
    private val portrait = CommandPanel(eight, Portrait, Rect(0f, 60f, 400f, 360f))
    private val landscape = CommandPanel(eight, Landscape, Rect(0f, 60f, 800f, 260f))
    private val panel = CommandPanel(listOf(Orientation, Trim, Layout, ScaleMode), Portrait, Rect(0f, 60f, 400f, 260f))
    private val bar = Point(200f, 30f)

    private fun CommandPanel.run(vararg events: CommandBarEvent): List<CommandBarStep> =
        events.runningFold(CommandBarStep(CommandBarState.Closed)) { step, event -> reduce(step.state, event, touchSlop = 8f) }.drop(1)

    @Test
    fun theTopRowHoldsCloseAndMenuAtItsEnds() {
        assertEquals(PanelItem.Close, portrait.itemAt(Point(50f, 100f)))
        assertEquals(PanelItem.Menu, portrait.itemAt(Point(350f, 100f)))
        assertNull(portrait.itemAt(Point(150f, 100f)))
        assertEquals(PanelItem.Close, landscape.itemAt(Point(50f, 100f)))
        assertEquals(PanelItem.Menu, landscape.itemAt(Point(750f, 100f)))
    }

    @Test
    fun portraitLaysTheCommandsInFourColumnsByTwoRows() {
        assertEquals(Command(Orientation), portrait.itemAt(Point(50f, 200f)))
        assertEquals(Command(ScaleMode), portrait.itemAt(Point(350f, 200f)))
        assertEquals(Command(Rotate), portrait.itemAt(Point(50f, 300f)))
        assertEquals(Command(AutoPlay), portrait.itemAt(Point(399f, 359f)))
    }

    @Test
    fun landscapeLaysTheCommandsInEightColumnsByOneRow() {
        assertEquals(Command(Orientation), landscape.itemAt(Point(50f, 200f)))
        assertEquals(Command(Rotate), landscape.itemAt(Point(450f, 200f)))
        assertEquals(Command(AutoPlay), landscape.itemAt(Point(750f, 200f)))
    }

    @Test
    fun emptyCellsAndTheOutsideHoldNothing() {
        assertNull(CommandPanel(listOf(Orientation, Trim), Portrait, Rect(0f, 60f, 400f, 260f)).itemAt(Point(350f, 200f)))
        assertNull(panel.itemAt(Point(150f, 100f)))
        assertNull(panel.itemAt(Point(50f, 300f)))
        assertNull(panel.itemAt(Point(50f, 30f)))
        assertNull(panel.itemAt(Point(50f, 360f)))
    }

    @Test
    fun thePanelHasAsManyRowsAsItsCommandsNeed() {
        assertEquals(2, panel.rows)
        assertEquals(3, portrait.rows)
        assertEquals(4, CommandPanel(eight + Settings, Portrait, Rect(0f, 60f, 400f, 460f)).rows)
        assertEquals(2, landscape.rows)
    }

    @Test
    fun pressingTheBarOpensThePanelAtOnce() {
        assertTrue(panel.run(Down(bar, 0, systemBarsHidden = false)).last().state.panelVisible)
    }

    @Test
    fun slidingOntoACommandAndReleasingRunsIt() {
        val steps = panel.run(Down(bar, 0, false), Move(Point(150f, 200f)), Move(Point(160f, 210f)), Up(Point(160f, 210f)))
        assertEquals(CommandBarEffect.Haptic, steps[1].effect)
        assertNull(steps[2].effect)
        assertEquals(CommandBarEffect.Execute(Command(Trim)), steps[3].effect)
        assertEquals(CommandBarState.Closed, steps[3].state)
    }

    @Test
    fun everyNewHighlightBuzzes() {
        val steps = panel.run(Down(bar, 0, false), Move(Point(50f, 200f)), Move(Point(150f, 200f)), Move(Point(150f, 300f)), Move(Point(150f, 200f)))
        assertEquals(listOf(CommandBarEffect.Haptic, CommandBarEffect.Haptic, null, CommandBarEffect.Haptic), steps.drop(1).map { it.effect })
        assertEquals(Command(Trim), assertIs<CommandBarState.Sliding>(steps.last().state).highlight)
    }

    @Test
    fun releasingOverNothingClosesThePanel() {
        val last = panel.run(Down(bar, 0, false), Move(Point(150f, 300f)), Up(Point(150f, 300f))).last()
        assertNull(last.effect)
        assertEquals(CommandBarState.Closed, last.state)
    }

    @Test
    fun aTapLeavesThePanelOpenForAnotherTap() {
        val open = panel.run(Down(bar, 0, false), Up(Point(203f, 33f))).last()
        assertEquals(CommandBarState.Open, open.state)
        val tapped = panel.run(Down(bar, 0, false), Up(bar), Down(Point(250f, 200f), 500, false), Up(Point(250f, 200f))).last()
        assertEquals(CommandBarEffect.Execute(Command(Layout)), tapped.effect)
    }

    @Test
    fun aTapOutsideTheOpenPanelClosesIt() {
        val last = panel.run(Down(bar, 0, false), Up(bar), Down(Point(200f, 600f), 500, false)).last()
        assertEquals(CommandBarState.Closed, last.state)
        assertNull(last.effect)
    }

    @Test
    fun aTapOnAnEmptyCellKeepsThePanelOpen() {
        assertEquals(CommandBarState.Open, panel.run(Down(bar, 0, false), Up(bar), Down(Point(150f, 100f), 500, false), Up(Point(150f, 100f))).last().state)
    }

    @Test
    fun aCancelledTouchClosesThePanelWithoutRunning() {
        val steps = panel.run(Down(bar, 0, false), Move(Point(150f, 200f)), CommandBarEvent.Cancel, Up(Point(150f, 200f)))
        assertEquals(CommandBarState.Closed, steps[2].state)
        assertNull(steps[2].effect)
        assertNull(steps[3].effect)
    }

    @Test
    fun hiddenSystemBarsOpenThePanelAfterFourHundredMs() {
        val steps = panel.run(Down(bar, 1_000, systemBarsHidden = true), Hold(1_399), Hold(1_400))
        assertFalse(steps[0].state.panelVisible)
        assertFalse(steps[1].state.panelVisible)
        assertTrue(steps[2].state.panelVisible)
    }

    @Test
    fun hiddenSystemBarsIgnoreAQuickTapOrASwipe() {
        assertEquals(CommandBarState.Closed, panel.run(Down(bar, 0, true), Up(bar), Hold(400)).last().state)
        assertEquals(CommandBarState.Closed, panel.run(Down(bar, 0, true), Move(Point(200f, 80f)), Hold(400)).last().state)
    }
}
