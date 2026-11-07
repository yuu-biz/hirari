package io.github.yuubiz.hirari.feature.reader

import io.github.yuubiz.hirari.core.model.CommandTargets
import io.github.yuubiz.hirari.core.model.CommandCells
import io.github.yuubiz.hirari.core.model.OpenBookRequest
import io.github.yuubiz.hirari.core.model.PageView
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
    val width = screen.width / screen.density
    val columns = if (screen.orientation == ScreenOrientation.Portrait) 4 else 8
    val rows = 1 + ReaderCommand.MAX_ITEMS / columns
    val top = config.barHeightDp
    return CommandPanel(config.commandBarItems, screen.orientation, Rect(0f, top, width, top + rows * PANEL_ROW_DP))
}

/** A visible row keeps the two end buttons and reserves its last middle cell for その他 when needed. */
data class CommandRow(val commands: List<ReaderCommand>, val bounds: Rect) {
    val layout = CommandCells<PanelItem>(
        commands.map { PanelItem.Command(it) }, PanelItem.Close, PanelItem.Menu, PanelItem.More, bounds,
    )
    val cells get() = layout.cells
    val cellWidth get() = layout.cellWidth
    fun itemAt(point: Point): PanelItem? = layout.itemAt(point)
    fun tapped(origin: Point, released: Point, moved: Boolean): PanelItem? = layout.tapped(origin, released, moved)
}

fun ReaderState.commandRow(): CommandRow? = area.regions.commandRow?.let { CommandRow(config.commandBarItems, it) }

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

    /** しおりを追加 at the view on screen, which [ReaderViewModel.command] makes into the sheet that names it. */
    data object AddBookmark : CommandEffect

    /** 表紙にする of the view on screen, which [coverChoice] makes into [Show] or [Cover]. */
    data object SetCover : CommandEffect

    /** The screen makes [page] the book's cover. */
    data class Cover(val page: Int) : CommandEffect

    /** 並びを逆に, which [ReaderViewModel.command] stores and makes into [Reopen]. */
    data object ReverseOrder : CommandEffect

    /** The screen opens the book again as [request] asks, after its order or names changed. */
    data class Reopen(val request: OpenBookRequest) : CommandEffect

    /** A screen of the settings that the app opens over the reader. */
    data class OpenSettings(val target: SettingsTarget) : CommandEffect
}

/** What 表紙にする does on [view]: a pair asks which page, any other view makes its one page the cover. */
fun coverChoice(view: PageView): CommandEffect = when (view) {
    is PageView.Two -> CommandEffect.Show(ReaderSheet.CoverPick(view, range = false))
    is PageView.One -> CommandEffect.Cover(view.page)
    is PageView.Half -> CommandEffect.Cover(view.page)
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
    ReaderCommand.MirrorControls -> CommandEffect.Configure(copy(tapZones = tapZones.copy(mirrored = !mirrored), switched = switched.flipped(SessionSwitch.MirrorControls)))
    ReaderCommand.StopOrder -> CommandEffect.Configure(
        copy(stopOrder = if (stopOrder == StopOrder.RowsFirst) StopOrder.ColumnsFirst else StopOrder.RowsFirst, switched = switched + SessionSwitch.StopOrder),
    )
    ReaderCommand.Gutter -> CommandEffect.Configure(copy(gutter = gutter.next(), switched = switched + SessionSwitch.Gutter))
    ReaderCommand.ShiftPairing -> CommandEffect.ShiftForward
    ReaderCommand.Orientation -> CommandEffect.Configure(copy(orientation = orientation.next(), switched = switched + SessionSwitch.Orientation))
    ReaderCommand.Rotate -> CommandEffect.Configure(copy(rotation = rotation.next()))
    ReaderCommand.Trim -> CommandEffect.Configure(copy(trim = trim.next()))
    ReaderCommand.ImageAdjust -> CommandEffect.Show(ReaderSheet.Adjust)
    ReaderCommand.AutoPlay -> CommandEffect.StartAutoPlay
    ReaderCommand.SharePage -> CommandEffect.SharePage
    ReaderCommand.Breath -> CommandEffect.ToggleBreath
    ReaderCommand.AddBookmark -> CommandEffect.AddBookmark
    ReaderCommand.Bookmarks -> CommandEffect.Show(ReaderSheet.Bookmarks)
    ReaderCommand.ReverseOrder -> CommandEffect.ReverseOrder
    ReaderCommand.BookSettings -> CommandEffect.Show(ReaderSheet.BookSettings(this))
    ReaderCommand.SetCover -> CommandEffect.SetCover
    ReaderCommand.Settings -> CommandEffect.OpenSettings(SettingsTarget.Settings)
    ReaderCommand.EditCommandBar -> CommandEffect.OpenSettings(SettingsTarget.CommandBar)
}

private fun Set<SessionSwitch>.flipped(switch: SessionSwitch): Set<SessionSwitch> = if (switch in this) this - switch else this + switch

private inline fun <reified E : Enum<E>> E.next(): E = enumValues<E>().let { it[(ordinal + 1) % it.size] }

sealed interface PanelItem : PanelTarget {
    /** Closes the reader. */
    data object Close : PanelItem
    data object Menu : PanelItem
    data object More : PanelItem
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

