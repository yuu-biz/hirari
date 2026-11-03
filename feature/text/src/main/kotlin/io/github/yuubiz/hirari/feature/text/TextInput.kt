package io.github.yuubiz.hirari.feature.text

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.util.VelocityTracker
import io.github.yuubiz.hirari.core.data.GlobalSettings
import io.github.yuubiz.hirari.core.model.KeyBindings
import io.github.yuubiz.hirari.core.model.PAGE_BAR_EDGE_DP
import io.github.yuubiz.hirari.core.model.PageBar
import io.github.yuubiz.hirari.core.model.PageBarEffect
import io.github.yuubiz.hirari.core.model.PageBarEvent
import io.github.yuubiz.hirari.core.model.PageBarState
import io.github.yuubiz.hirari.core.model.Point
import io.github.yuubiz.hirari.core.model.ReaderAction
import io.github.yuubiz.hirari.core.model.ReaderRegion
import io.github.yuubiz.hirari.core.model.ReaderRegions
import io.github.yuubiz.hirari.core.model.ReadingDirection
import io.github.yuubiz.hirari.core.model.Side
import io.github.yuubiz.hirari.core.model.Size
import io.github.yuubiz.hirari.core.model.TapZones
import io.github.yuubiz.hirari.core.model.TouchEvent
import io.github.yuubiz.hirari.core.model.TouchOutcome
import io.github.yuubiz.hirari.core.model.TouchState
import io.github.yuubiz.hirari.core.model.Turn
import io.github.yuubiz.hirari.core.model.TurnAreaTouch
import io.github.yuubiz.hirari.core.ui.HoldTimer
import io.github.yuubiz.hirari.core.ui.awaitOrHold
import kotlin.math.hypot

/** A text always binds on the right (docs/spec.md section 4.9, 操作). */
internal val TEXT_DIRECTION = ReadingDirection.RightToLeft

/** The keys of docs/spec.md section 4.8 (キー操作), as the text reader takes them. */
internal fun GlobalSettings.textKeys() = KeyBindings(TEXT_DIRECTION, mirrorControls, volumeKeys)

/** The tap zones of docs/spec.md section 4.8 (タップ領域). */
internal fun GlobalSettings.textTapZones() = TapZones(tapLayout, tapBandPercent, mirrorControls)

/**
 * The views [this] turns in a text: 1 forward, -1 back, or null for an action that does not turn. A text has no
 * stops, so stepping through them turns the view.
 */
internal fun ReaderAction.views(): Int? = when (this) {
    is ReaderAction.TurnView -> turn.views
    is ReaderAction.StepStops -> turn.views
    else -> null
}

internal val Turn.views: Int get() = if (this == Turn.Forward) 1 else -1

/** Where a release on the page bar goes in a text. */
internal sealed interface PageBarJump {
    data class Page(val index: Int) : PageBarJump

    data object First : PageBarJump

    data object Last : PageBarJump
}

/**
 * Where the page bar's move to [page] of [run] goes. An end page picked at an edge of the bar, [atEdge], is the first
 * or last page of the text, which the pages laid out so far may not reach yet.
 */
internal fun pageBarJump(page: Int, atEdge: Boolean, run: PageRun): PageBarJump = when {
    atEdge && page == run.lastIndex && !run.complete -> PageBarJump.Last
    atEdge && page == 0 && !run.fromStart -> PageBarJump.First
    else -> PageBarJump.Page(page)
}

/** The touch state the text reader draws: the page bar's preview. */
@Stable
internal class TextTouch {
    var pageBar by mutableStateOf<PageBarState>(PageBarState.Idle)
}

/**
 * Turns pointer input into text reader operations (docs/spec.md sections 4.8 and 4.9) through the rules the image
 * reader uses: the regions, the command bar, the page bar, and taps and swipes on the turn area.
 */
internal class TextInput(
    private val state: () -> TextReaderUiState.Reading,
    private val viewModel: TextReaderViewModel,
    private val touch: TextTouch,
    private val onTouch: () -> Unit,
    private val onPanel: (TextPanel?) -> Unit,
    private val onCommandAt: (Offset) -> Unit,
    private val act: (ReaderAction) -> Unit,
) {
    suspend fun PointerInputScope.handle() {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val current = state()
            val settings = current.settings
            val regions = ReaderRegions(Size(size.width / density, size.height / density), settings.barHeightDp.toFloat(), settings.hideSystemBars)
            val slop = viewConfiguration.touchSlop / density
            val region = regions.regionAt(down.position.dp(density)) ?: return@awaitEachGesture
            onTouch()
            when (region) {
                ReaderRegion.CommandBar -> commandBar(down, slop)
                ReaderRegion.PageBar -> pageBar(down, current, regions, slop)
                ReaderRegion.TurnArea -> turnArea(down, current, regions, slop)
            }
        }
    }

    private suspend fun AwaitPointerEventScope.commandBar(down: PointerInputChange, slop: Float) {
        onPanel(TextPanel.Commands)
        var last = down
        while (true) {
            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: return
            last = change
            if (!change.pressed) break
        }
        val moved = hypot(last.position.x - down.position.x, last.position.y - down.position.y) / density > slop
        if (moved) {
            onPanel(null)
            onCommandAt(last.position)
        }
    }

    private suspend fun AwaitPointerEventScope.pageBar(down: PointerInputChange, current: TextReaderUiState.Reading, regions: ReaderRegions, slop: Float) {
        val run = current.run ?: return
        val bar = PageBar(
            left = 0f,
            right = regions.screen.width,
            top = regions.screen.height - regions.barHeight,
            height = regions.barHeight,
            pageCount = run.size,
            direction = TEXT_DIRECTION,
            touchSlop = slop,
        )
        fun step(event: PageBarEvent, at: Point?) {
            val step = bar.reduce(touch.pageBar, event)
            touch.pageBar = step.state
            when (val effect = step.effect) {
                is PageBarEffect.GoTo -> {
                    val atEdge = at != null && (at.x <= bar.left + PAGE_BAR_EDGE_DP || at.x >= bar.right - PAGE_BAR_EDGE_DP)
                    when (val jump = pageBarJump(effect.page, atEdge, run)) {
                        is PageBarJump.Page -> viewModel.showPage(jump.index)
                        PageBarJump.First -> viewModel.first()
                        PageBarJump.Last -> viewModel.last()
                    }
                }
                PageBarEffect.OpenPagePanel -> onPanel(TextPanel.PageJump)
                null -> Unit
            }
        }
        step(PageBarEvent.Down(down.position.dp(density), down.uptimeMillis, current.current), null)
        down.consume()
        val hold = HoldTimer(down.uptimeMillis)
        var held = false
        while (true) {
            val event = awaitOrHold(hold, waiting = !held)
            if (!held && hold.due) {
                held = true
                step(PageBarEvent.Hold(hold.at), null)
                if (touch.pageBar == PageBarState.Idle) return
            }
            if (event == null) continue
            val change = event.changes.firstOrNull { it.id == down.id } ?: event.changes.first()
            change.consume()
            if (!change.pressed) {
                step(PageBarEvent.Up(change.position.dp(density)), change.position.dp(density))
                return
            }
            step(PageBarEvent.Move(change.position.dp(density)), null)
        }
    }

    /**
     * A tap turns by the tap zones and a sideways drag swipes. In the 連続 layout the row of pages scrolls under a
     * drag instead, so the drag is left to it.
     */
    private suspend fun AwaitPointerEventScope.turnArea(down: PointerInputChange, current: TextReaderUiState.Reading, regions: ReaderRegions, slop: Float) {
        val settings = current.settings
        val continuous = current.frame?.continuous == true
        val rules = TurnAreaTouch(TEXT_DIRECTION, settings.mirrorControls, settings.swipeToTurn, slop, settings.loupeHoldMs.toLong())
        val pullable = if (continuous) emptySet() else setOf(Side.Left, Side.Right)
        var touchState = rules.reduce(TouchState.Idle, TouchEvent.Down(down.position.dp(density), down.uptimeMillis, pullable)).state
        val velocity = VelocityTracker().apply { addPosition(down.uptimeMillis, down.position) }
        while (true) {
            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: return
            velocity.addPosition(change.uptimeMillis, change.position)
            if (change.pressed) {
                touchState = rules.reduce(touchState, TouchEvent.Move(change.position.dp(density))).state
                if (touchState is TouchState.Pulling) change.consume()
                continue
            }
            val vx = velocity.calculateVelocity().x / density
            when (val outcome = rules.reduce(touchState, TouchEvent.Up(change.position.dp(density), change.uptimeMillis, vx)).outcome) {
                is TouchOutcome.Tap -> {
                    val area = regions.turnArea
                    val at = Point(outcome.point.x - area.left, outcome.point.y - area.top)
                    settings.textTapZones().turnAt(at, Size(area.width, area.height))?.let { act(ReaderAction.TurnView(it)) }
                }
                is TouchOutcome.Commit -> act(ReaderAction.TurnView(outcome.turn))
                is TouchOutcome.Return -> act(ReaderAction.TurnView(outcome.turn))
                is TouchOutcome.ShowLoupe, TouchOutcome.HideLoupe, null -> Unit
            }
            return
        }
    }
}

private fun Offset.dp(density: Float) = Point(x / density, y / density)
