package io.github.yuubiz.hirari.feature.reader

import io.github.yuubiz.hirari.core.model.Point
import io.github.yuubiz.hirari.core.model.Rect
import io.github.yuubiz.hirari.core.model.ReaderCommand
import io.github.yuubiz.hirari.core.model.ReadingDirection
import io.github.yuubiz.hirari.core.model.ScreenOrientation
import io.github.yuubiz.hirari.core.model.StopOrder
import kotlin.math.hypot

private const val PANEL_ROW_DP = 64f

/** The panel under the command bar, in dp, holding the chosen `commandBarItems` (docs/spec.md section 4.8, コマンドバー). */
fun ReaderState.commandPanel(): CommandPanel {
    val width = viewport.width / viewport.density
    val columns = if (viewport.orientation == ScreenOrientation.Portrait) 4 else 8
    val rows = 1 + ReaderCommand.MAX_ITEMS / columns
    val top = config.barHeightDp
    return CommandPanel(config.commandBarItems, viewport.orientation, Rect(0f, top, width, top + rows * PANEL_ROW_DP))
}

/** What running a command does. */
sealed interface CommandEffect {
    data class Configure(val config: ReaderConfig) : CommandEffect

    /** 組ずらし: the pairing one page later. */
    data object ShiftForward : CommandEffect

    /** 自動再生. */
    data object StartAutoPlay : CommandEffect

    /** A sheet over the reader, which the screen opens. */
    data class Show(val sheet: ReaderSheet) : CommandEffect

    /** ページを共有 of the view on screen, which [shareChoice] makes into [Show] or [Share]. */
    data object SharePage : CommandEffect

    /** The screen shares [page]. */
    data class Share(val page: Int) : CommandEffect

    /** A command of a later milestone. */
    data object NotYet : CommandEffect
}

/** The effect of [command] on a reader set up as this (docs/spec.md section 4.8, コマンドバー). */
fun ReaderConfig.effectOf(command: ReaderCommand): CommandEffect = when (command) {
    ReaderCommand.Layout -> CommandEffect.Configure(copy(layout = layout.copy(mode = layout.mode.next())))
    ReaderCommand.ScaleMode -> CommandEffect.Configure(copy(scaleMode = scaleMode.next()))
    ReaderCommand.Direction -> CommandEffect.Configure(
        copy(layout = layout.copy(direction = if (direction == ReadingDirection.RightToLeft) ReadingDirection.LeftToRight else ReadingDirection.RightToLeft)),
    )
    ReaderCommand.MirrorControls -> CommandEffect.Configure(copy(tapZones = tapZones.copy(mirrored = !mirrored)))
    ReaderCommand.StopOrder -> CommandEffect.Configure(copy(stopOrder = if (stopOrder == StopOrder.RowsFirst) StopOrder.ColumnsFirst else StopOrder.RowsFirst))
    ReaderCommand.Gutter -> CommandEffect.Configure(copy(gutter = gutter.next()))
    ReaderCommand.ShiftPairing -> CommandEffect.ShiftForward
    ReaderCommand.Orientation -> CommandEffect.Configure(copy(orientation = orientation.next()))
    ReaderCommand.Rotate -> CommandEffect.Configure(copy(rotation = rotation.next()))
    ReaderCommand.ImageAdjust -> CommandEffect.Show(ReaderSheet.Adjust)
    ReaderCommand.AutoPlay -> CommandEffect.StartAutoPlay
    ReaderCommand.SharePage -> CommandEffect.SharePage
    ReaderCommand.Trim, ReaderCommand.AddBookmark, ReaderCommand.Bookmarks,
    ReaderCommand.ReverseOrder, ReaderCommand.EditCommandBar, ReaderCommand.Breath,
    ReaderCommand.BookSettings, ReaderCommand.SetCover, ReaderCommand.Settings,
    -> CommandEffect.NotYet
}

private inline fun <reified E : Enum<E>> E.next(): E = enumValues<E>().let { it[(ordinal + 1) % it.size] }

sealed interface PanelItem {
    /** Closes the reader. */
    data object Close : PanelItem
    data object Menu : PanelItem
    data class Command(val command: ReaderCommand) : PanelItem
}

/**
 * The command panel below the command bar. The top row holds [PanelItem.Close] on the left end and
 * [PanelItem.Menu] on the right end. Below it the commands fill a grid of 4 columns by 2 rows in
 * portrait and 8 by 1 in landscape, row by row from the left. [bounds] is in screen dp.
 */
data class CommandPanel(val commands: List<ReaderCommand>, val orientation: ScreenOrientation, val bounds: Rect) {
    init {
        require(commands.size <= ReaderCommand.MAX_ITEMS) { "${commands.size} commands do not fit the panel" }
    }

    val columns: Int get() = if (orientation == ScreenOrientation.Portrait) 4 else 8
    val rows: Int get() = 1 + ReaderCommand.MAX_ITEMS / columns

    fun contains(point: Point): Boolean =
        point.x >= bounds.left && point.x < bounds.right && point.y >= bounds.top && point.y < bounds.bottom

    /** The item under [point], or `null` outside the panel and on empty cells. */
    fun itemAt(point: Point): PanelItem? {
        if (!contains(point)) return null
        val column = ((point.x - bounds.left) / bounds.width * columns).toInt()
        val row = ((point.y - bounds.top) / bounds.height * rows).toInt()
        return when {
            row == 0 && column == 0 -> PanelItem.Close
            row == 0 && column == columns - 1 -> PanelItem.Menu
            row == 0 -> null
            else -> commands.getOrNull((row - 1) * columns + column)?.let { PanelItem.Command(it) }
        }
    }

    /**
     * Press, slide, and release on the command bar. [Down] events come only from the bar while the
     * panel is closed, and from anywhere while it is open.
     */
    fun reduce(state: CommandBarState, event: CommandBarEvent, touchSlop: Float): CommandBarStep = when (event) {
        is CommandBarEvent.Down -> when (state) {
            CommandBarState.Open ->
                if (contains(event.point)) slide(event.point) else CommandBarStep(CommandBarState.Closed)
            else ->
                if (event.systemBarsHidden) CommandBarStep(CommandBarState.Holding(event.point, event.timeMs)) else slide(event.point)
        }
        is CommandBarEvent.Hold ->
            if (state is CommandBarState.Holding && event.timeMs - state.downAtMs >= LONG_PRESS_MS) {
                slide(state.origin)
            } else {
                CommandBarStep(state)
            }
        is CommandBarEvent.Move -> when (state) {
            is CommandBarState.Holding ->
                CommandBarStep(if (beyond(state.origin, event.point, touchSlop)) CommandBarState.Closed else state)
            is CommandBarState.Sliding -> {
                val item = itemAt(event.point)
                val next = state.copy(highlight = item, moved = state.moved || beyond(state.origin, event.point, touchSlop))
                CommandBarStep(next, if (item != null && item != state.highlight) CommandBarEffect.Haptic else null)
            }
            CommandBarState.Closed, CommandBarState.Open -> CommandBarStep(state)
        }
        is CommandBarEvent.Up -> when (state) {
            is CommandBarState.Sliding -> {
                val item = itemAt(event.point)
                val moved = state.moved || beyond(state.origin, event.point, touchSlop)
                when {
                    item != null -> CommandBarStep(CommandBarState.Closed, CommandBarEffect.Execute(item))
                    moved -> CommandBarStep(CommandBarState.Closed)
                    else -> CommandBarStep(CommandBarState.Open)
                }
            }
            is CommandBarState.Holding -> CommandBarStep(CommandBarState.Closed)
            CommandBarState.Closed, CommandBarState.Open -> CommandBarStep(state)
        }
        CommandBarEvent.Cancel -> CommandBarStep(CommandBarState.Closed)
    }

    private fun slide(origin: Point): CommandBarStep {
        val item = itemAt(origin)
        return CommandBarStep(CommandBarState.Sliding(origin, item, moved = false), if (item != null) CommandBarEffect.Haptic else null)
    }

    private fun beyond(origin: Point, point: Point, touchSlop: Float) = hypot(point.x - origin.x, point.y - origin.y) > touchSlop
}

sealed interface CommandBarState {
    val panelVisible: Boolean get() = this is Sliding || this is Open

    data object Closed : CommandBarState

    /** The system bars are hidden, so the panel waits for a [LONG_PRESS_MS] press before it opens. */
    data class Holding(val origin: Point, val downAtMs: Long) : CommandBarState

    /** The panel is open under a finger, with [highlight] under it. */
    data class Sliding(val origin: Point, val highlight: PanelItem?, val moved: Boolean) : CommandBarState

    /** A tap left the panel open. */
    data object Open : CommandBarState
}

sealed interface CommandBarEvent {
    data class Down(val point: Point, val timeMs: Long, val systemBarsHidden: Boolean) : CommandBarEvent
    data class Move(val point: Point) : CommandBarEvent
    data class Up(val point: Point) : CommandBarEvent

    /** Sent [LONG_PRESS_MS] after the press. */
    data class Hold(val timeMs: Long) : CommandBarEvent
    data object Cancel : CommandBarEvent
}

sealed interface CommandBarEffect {
    data object Haptic : CommandBarEffect
    data class Execute(val item: PanelItem) : CommandBarEffect
}

data class CommandBarStep(val state: CommandBarState, val effect: CommandBarEffect? = null)
