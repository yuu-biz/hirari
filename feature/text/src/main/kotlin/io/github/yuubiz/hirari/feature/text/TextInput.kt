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
import io.github.yuubiz.hirari.core.model.CommandBarEffect
import io.github.yuubiz.hirari.core.model.CommandBarEvent
import io.github.yuubiz.hirari.core.model.CommandBarState
import io.github.yuubiz.hirari.core.model.KeyBindings
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
import io.github.yuubiz.hirari.core.model.TurnAnimation
import io.github.yuubiz.hirari.core.model.TurnAreaTouch
import io.github.yuubiz.hirari.core.model.reduce
import io.github.yuubiz.hirari.core.model.turnMovement
import io.github.yuubiz.hirari.core.ui.HoldTimer
import io.github.yuubiz.hirari.core.ui.awaitOrHold

/** A text always binds on the right (docs/spec.md section 4.9, 操作). */
internal val TEXT_DIRECTION = ReadingDirection.RightToLeft

/** The keys of docs/spec.md section 4.8 (キー操作), as the text reader takes them. */
internal fun GlobalSettings.textKeys() = KeyBindings(TEXT_DIRECTION, mirrorControls, volumeKeys)

/** The tap zones of docs/spec.md section 4.8 (タップ領域). */
internal fun GlobalSettings.textTapZones() = TapZones(tapLayout, tapBandPercent, mirrorControls)

/**
 * The turn [this] makes in a text, or null for an action that does not turn. A text has no stops, so stepping
 * through them turns the view.
 */
internal fun ReaderAction.textTurn(): Turn? = when (this) {
    is ReaderAction.TurnView -> turn
    is ReaderAction.StepStops -> turn
    else -> null
}

internal val Turn.views: Int get() = if (this == Turn.Forward) 1 else -1

/** Where a release on the page bar goes in a text. */
internal sealed interface PageBarJump {
    /** The page that holds source offset [offset], which does not depend on how many pages are laid out by the release. */
    data class Page(val offset: Int) : PageBarJump

    data object First : PageBarJump

    data object Last : PageBarJump
}

/**
 * Where the page bar's move to [page] of [run], the run the finger pressed on, goes. An end page picked at an edge of
 * the bar, [atEdge], is the first or last page of the text, which the pages laid out so far may not reach yet.
 */
internal fun pageBarJump(page: Int, atEdge: Boolean, run: PageRun): PageBarJump = when {
    atEdge && page == run.lastIndex && !run.complete -> PageBarJump.Last
    atEdge && page == 0 && !run.fromStart -> PageBarJump.First
    else -> PageBarJump.Page(run.start(page))
}

/** The index in [later] of [page] of [pressed], the run the page bar was pressed on: the pages are told apart by where they start. */
internal fun pageInLaterRun(pressed: PageRun, page: Int, later: PageRun): Int =
    later.indexOf(pressed.start(page.coerceIn(0, pressed.lastIndex)))

/**
 * What the text reader draws besides the pages at rest: the page bar's preview, the command under the finger, and
 * the motion of a turn.
 */
@Stable
internal class TextTouch {
    var pageBar by mutableStateOf<PageBarState>(PageBarState.Idle)

    /** The run the page bar's pages are counted in: the one on screen when the finger pressed, while the layout goes on. */
    var pageBarRun by mutableStateOf<PageRun?>(null)
    var highlight by mutableStateOf<TextPanelItem?>(null)
    var motion by mutableStateOf<TextMotion?>(null)
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
    private val panelBounds: TextPanelBounds,
    private val onItem: (TextPanelItem) -> Unit,
    private val haptic: () -> Unit,
    /** Turns toward a [Turn], continuing the effect from the share of the view a finger pulled, if it did. */
    private val turn: (Turn, Float?) -> Unit,
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
            touch.motion = null
            when (region) {
                ReaderRegion.CommandBar -> commandBar(down, settings, slop)
                ReaderRegion.PageBar -> pageBar(down, current, regions, slop)
                ReaderRegion.TurnArea -> turnArea(down, current, regions, slop)
            }
        }
    }

    /**
     * Press, slide, and release on the command bar. While the system bars are hidden, the panel opens only once the
     * press is held. A tap leaves it open, and the panel then takes the taps itself.
     */
    private suspend fun AwaitPointerEventScope.commandBar(down: PointerInputChange, settings: GlobalSettings, slop: Float) {
        var bar: CommandBarState = CommandBarState.Closed
        fun step(event: CommandBarEvent) {
            val step = panelBounds.reduce(bar, event, slop)
            bar = step.state
            touch.highlight = (bar as? CommandBarState.Sliding)?.highlight as? TextPanelItem
            onPanel(if (bar.panelVisible) TextPanel.Commands else null)
            when (val effect = step.effect) {
                CommandBarEffect.Haptic -> haptic()
                is CommandBarEffect.Execute -> (effect.item as? TextPanelItem)?.let(onItem)
                null -> Unit
            }
        }
        step(CommandBarEvent.Down(down.position.dp(density), down.uptimeMillis, settings.hideSystemBars))
        down.consume()
        val hold = HoldTimer(down.uptimeMillis)
        while (true) {
            val holding = bar is CommandBarState.Holding
            val event = awaitOrHold(hold, waiting = holding)
            if (holding && hold.due) step(CommandBarEvent.Hold(hold.at))
            if (event == null) continue
            val change = event.changes.firstOrNull { it.id == down.id } ?: event.changes.first()
            change.consume()
            if (!change.pressed) {
                step(CommandBarEvent.Up(change.position.dp(density)))
                return
            }
            step(CommandBarEvent.Move(change.position.dp(density)))
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
        fun step(event: PageBarEvent, atEdge: Boolean = false) {
            val step = bar.reduce(touch.pageBar, event)
            touch.pageBar = step.state
            when (val effect = step.effect) {
                is PageBarEffect.GoTo -> {
                    when (val jump = pageBarJump(effect.page, atEdge, run)) {
                        is PageBarJump.Page -> viewModel.jumpTo(jump.offset)
                        PageBarJump.First -> viewModel.first()
                        PageBarJump.Last -> viewModel.last()
                    }
                }
                PageBarEffect.OpenPagePanel -> onPanel(TextPanel.PageJump)
                null -> Unit
            }
        }
        touch.pageBarRun = run
        step(PageBarEvent.Down(down.position.dp(density), down.uptimeMillis, current.current))
        down.consume()
        val hold = HoldTimer(down.uptimeMillis)
        var held = false
        while (true) {
            val event = awaitOrHold(hold, waiting = !held)
            if (!held && hold.due) {
                held = true
                step(PageBarEvent.Hold(hold.at))
                if (touch.pageBar == PageBarState.Idle) return
            }
            if (event == null) continue
            val change = event.changes.firstOrNull { it.id == down.id } ?: event.changes.first()
            change.consume()
            if (!change.pressed) {
                val at = change.position.dp(density)
                val armed = (touch.pageBar as? PageBarState.Dragging)?.armedEdges.orEmpty()
                step(PageBarEvent.Up(at), atEdge = bar.pickedEdge(at.x, armed) != null)
                return
            }
            step(PageBarEvent.Move(change.position.dp(density)))
        }
    }

    /**
     * A tap turns by the tap zones and a sideways drag pulls the neighboring view in. In the 連続 layout the row of
     * pages scrolls under a drag instead, so the drag is left to it.
     */
    private suspend fun AwaitPointerEventScope.turnArea(down: PointerInputChange, current: TextReaderUiState.Reading, regions: ReaderRegions, slop: Float) {
        val settings = current.settings
        val continuous = current.frame?.continuous == true
        val rules = TurnAreaTouch(TEXT_DIRECTION, settings.mirrorControls, settings.swipeToTurn, slop, settings.loupeHoldMs.toLong())
        val pullable = if (continuous) emptySet() else setOf(Side.Left, Side.Right)
        var touchState = rules.reduce(TouchState.Idle, TouchEvent.Down(down.position.dp(density), down.uptimeMillis, pullable)).state
        val velocity = VelocityTracker().apply { addPosition(down.uptimeMillis, down.position) }
        val width = size.width.toFloat()
        try {
            while (true) {
                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: return
                velocity.addPosition(change.uptimeMillis, change.position)
                if (change.pressed) {
                    touchState = rules.reduce(touchState, TouchEvent.Move(change.position.dp(density))).state
                    (touchState as? TouchState.Pulling)?.let {
                        change.consume()
                        touch.motion = TextMotion.Pulling(it.turn, it.pulled)
                    }
                    continue
                }
                val vx = velocity.calculateVelocity().x / density
                val outcome = rules.reduce(touchState, TouchEvent.Up(change.position.dp(density), change.uptimeMillis, vx)).outcome
                touchState = TouchState.Idle
                when (outcome) {
                    is TouchOutcome.Tap -> {
                        val area = regions.turnArea
                        val at = Point(outcome.point.x - area.left, outcome.point.y - area.top)
                        settings.textTapZones().turnAt(at, Size(area.width, area.height))?.let { turn(it, null) }
                    }
                    is TouchOutcome.Commit -> turn(outcome.turn, pulledShare(outcome.pulled, width, density))
                    is TouchOutcome.Return -> touch.motion = TextMotion.Returning(
                        outcome.turn,
                        TurnAnimation.returnPull(
                            settings.turnEffect,
                            turnMovement(outcome.turn, TEXT_DIRECTION),
                            pulledShare(outcome.pulled, width, density),
                            change.uptimeMillis,
                        ),
                    )
                    is TouchOutcome.ShowLoupe, TouchOutcome.HideLoupe, null -> Unit
                }
                return
            }
        } finally {
            if (touchState is TouchState.Pulling) touch.motion = null
        }
    }
}

private fun Offset.dp(density: Float) = Point(x / density, y / density)
