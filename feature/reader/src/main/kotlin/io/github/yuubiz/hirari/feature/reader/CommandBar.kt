package io.github.yuubiz.hirari.feature.reader

import io.github.yuubiz.hirari.core.model.CommandTargets
import io.github.yuubiz.hirari.core.model.PanelTarget
import io.github.yuubiz.hirari.core.model.Point
import io.github.yuubiz.hirari.core.model.Rect
import io.github.yuubiz.hirari.core.model.ReaderCommand
import io.github.yuubiz.hirari.core.model.ReadingDirection
import io.github.yuubiz.hirari.core.model.ScreenOrientation
import io.github.yuubiz.hirari.core.model.StopOrder

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

    /** 息で操作, which the screen runs since turning it on may ask for the microphone. */
    data object ToggleBreath : CommandEffect

    /** A screen of the settings that the app opens over the reader. */
    data class OpenSettings(val target: SettingsTarget) : CommandEffect

    /** A command of a later milestone. */
    data object NotYet : CommandEffect
}

/** Where 設定, コマンドバーの編集, and このアプリについて go: the settings screen, its `commandBarItems` dialog, or its about dialog. */
enum class SettingsTarget { Settings, CommandBar, About }

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
    ReaderCommand.Trim -> CommandEffect.Configure(copy(trim = trim.next()))
    ReaderCommand.ImageAdjust -> CommandEffect.Show(ReaderSheet.Adjust)
    ReaderCommand.AutoPlay -> CommandEffect.StartAutoPlay
    ReaderCommand.SharePage -> CommandEffect.SharePage
    ReaderCommand.Breath -> CommandEffect.ToggleBreath
    ReaderCommand.Settings -> CommandEffect.OpenSettings(SettingsTarget.Settings)
    ReaderCommand.EditCommandBar -> CommandEffect.OpenSettings(SettingsTarget.CommandBar)
    ReaderCommand.AddBookmark, ReaderCommand.Bookmarks,
    ReaderCommand.ReverseOrder,
    ReaderCommand.BookSettings, ReaderCommand.SetCover,
    -> CommandEffect.NotYet
}

private inline fun <reified E : Enum<E>> E.next(): E = enumValues<E>().let { it[(ordinal + 1) % it.size] }

sealed interface PanelItem : PanelTarget {
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
data class CommandPanel(val commands: List<ReaderCommand>, val orientation: ScreenOrientation, val bounds: Rect) : CommandTargets {
    init {
        require(commands.size <= ReaderCommand.MAX_ITEMS) { "${commands.size} commands do not fit the panel" }
    }

    val columns: Int get() = if (orientation == ScreenOrientation.Portrait) 4 else 8
    val rows: Int get() = 1 + ReaderCommand.MAX_ITEMS / columns

    override fun contains(point: Point): Boolean =
        point.x >= bounds.left && point.x < bounds.right && point.y >= bounds.top && point.y < bounds.bottom

    override fun itemAt(point: Point): PanelItem? {
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
}

