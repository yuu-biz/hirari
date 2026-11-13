package io.github.yuubiz.hirari.feature.reader

import io.github.yuubiz.hirari.core.model.CommandTargets
import io.github.yuubiz.hirari.core.model.CommandCells
import io.github.yuubiz.hirari.core.model.LayoutMode
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

/** The panel under the command bar, in dp: 表示 and the assigned `commandBarItems` slots (docs/spec.md section 4.8, コマンドバー). */
fun ReaderState.commandPanel(): CommandPanel {
    val width = screen.width / screen.density
    val top = config.barHeightDp
    val commands = (listOf(ReaderCommand.BookSettings) + config.commandBarItems.filterNotNull()).distinct()
    val panel = CommandPanel(commands, screen.orientation, Rect(0f, top, width, top))
    return panel.copy(bounds = Rect(0f, top, width, top + panel.rows * PANEL_ROW_DP))
}

/** A visible row: the fixed 表示, the three slots with [PanelItem.Blank] for an empty one, and the fixed メニュー. */
data class CommandRow(val slots: List<ReaderCommand?>, val bounds: Rect) {
    val layout = CommandCells<PanelItem>(
        listOf(PanelItem.Command(ReaderCommand.BookSettings)) + slots.map { slot -> slot?.let { PanelItem.Command(it) } ?: PanelItem.Blank },
        close = null, end = PanelItem.Menu, more = null, bounds = bounds,
    )

    /** Whether [item] is one of the assignable slots, which the row tints apart from the fixed buttons. */
    fun isSlot(item: PanelItem): Boolean = item != PanelItem.Menu && item != PanelItem.Command(ReaderCommand.BookSettings)
    val cells get() = layout.cells
    val cellWidth get() = layout.cellWidth
    fun itemAt(point: Point): PanelItem? = layout.itemAt(point)
    fun indexAt(point: Point): Int? = layout.indexAt(point)
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

    /** An empty slot of the visible row, which does nothing. */
    data object Blank : PanelItem
    data class Command(val command: ReaderCommand) : PanelItem
}

/** Whether 組ずらし does anything: the layout, once the orientation resolves it, is a spread (docs/spec.md section 2.5). */
val ReaderState.shiftsPairs: Boolean get() = pagination.settings.resolve(pagination.orientation).mode == LayoutMode.Spread

/**
 * The command panel below the command bar. The top row holds [PanelItem.Close] on the left end and
 * [PanelItem.Menu] on the right end. Below it the commands fill a grid of 4 columns in portrait and 8 in landscape,
 * row by row from the left, in as many rows as they need. [bounds] is in screen dp.
 */
data class CommandPanel(val commands: List<ReaderCommand>, val orientation: ScreenOrientation, val bounds: Rect) : CommandTargets {
    val columns: Int get() = if (orientation == ScreenOrientation.Portrait) 4 else 8
    val rows: Int get() = 1 + (commands.size + columns - 1) / columns

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

