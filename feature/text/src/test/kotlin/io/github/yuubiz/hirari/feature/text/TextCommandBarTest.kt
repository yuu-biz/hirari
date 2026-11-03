package io.github.yuubiz.hirari.feature.text

import androidx.compose.ui.geometry.Rect
import io.github.yuubiz.hirari.core.model.CommandBarEffect
import io.github.yuubiz.hirari.core.model.CommandBarEvent
import io.github.yuubiz.hirari.core.model.CommandBarEvent.Down
import io.github.yuubiz.hirari.core.model.CommandBarEvent.Hold
import io.github.yuubiz.hirari.core.model.CommandBarEvent.Move
import io.github.yuubiz.hirari.core.model.CommandBarEvent.Up
import io.github.yuubiz.hirari.core.model.CommandBarState
import io.github.yuubiz.hirari.core.model.CommandBarStep
import io.github.yuubiz.hirari.core.model.Point
import io.github.yuubiz.hirari.core.model.reduce
import kotlin.test.Test
import kotlin.test.assertEquals

class TextCommandBarTest {
    /** A panel laid out at density 2 under a 60 dp bar: 閉じる at the top left, then 目次 below it. */
    private val bounds = TextPanelBounds(density = 2f).apply {
        panel = Rect(0f, 120f, 800f, 400f)
        items[TextPanelItem.Close] = Rect(0f, 120f, 200f, 220f)
        items[TextPanelItem.Command(TextCommand.Contents)] = Rect(0f, 220f, 200f, 330f)
    }

    private fun run(vararg events: CommandBarEvent): List<CommandBarStep> =
        events.runningFold(CommandBarStep(CommandBarState.Closed)) { step, event -> bounds.reduce(step.state, event, touchSlop = 8f) }.drop(1)

    private val bar = Point(50f, 30f)
    private val contents = Point(50f, 140f)

    @Test
    fun `the panel finds its items in dp from the pixels it was laid out at`() {
        assertEquals(TextPanelItem.Close, bounds.itemAt(Point(10f, 70f)))
        assertEquals(TextPanelItem.Command(TextCommand.Contents), bounds.itemAt(contents))
        assertEquals(null, bounds.itemAt(Point(300f, 70f)))
        assertEquals(false, bounds.contains(bar))
        bounds.clear()
        assertEquals(null, bounds.itemAt(contents))
    }

    @Test
    fun `with the system bars hidden the panel opens only after a 400 ms hold`() {
        val early = run(Down(bar, 0, systemBarsHidden = true), Up(bar))
        assertEquals(CommandBarState.Closed, early.last().state)
        val held = run(Down(bar, 0, systemBarsHidden = true), Hold(400), Move(contents), Up(contents))
        assertEquals(true, held[1].state.panelVisible)
        assertEquals(CommandBarEffect.Haptic, held[2].effect)
        assertEquals(CommandBarEffect.Execute(TextPanelItem.Command(TextCommand.Contents)), held[3].effect)
    }

    @Test
    fun `a tap on the bar leaves the panel open`() {
        assertEquals(CommandBarState.Open, run(Down(bar, 0, systemBarsHidden = false), Up(bar)).last().state)
    }
}
