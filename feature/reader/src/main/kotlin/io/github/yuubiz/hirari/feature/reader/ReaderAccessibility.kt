package io.github.yuubiz.hirari.feature.reader

import android.os.SystemClock
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import io.github.yuubiz.hirari.core.model.CommandBarState
import io.github.yuubiz.hirari.core.model.PageView
import io.github.yuubiz.hirari.core.model.ReaderAction
import io.github.yuubiz.hirari.core.model.ReaderCommand
import io.github.yuubiz.hirari.core.model.Turn
import io.github.yuubiz.hirari.core.ui.BarSemantics

/**
 * What a screen reader can do on the page without a gesture (docs/spec.md section 6.7): the same turns, panels and
 * menu that the tap zones, the bars and the Menu key reach.
 */
internal enum class ReaderControl(@StringRes val label: Int) {
    NextPage(R.string.a11y_next_page),
    PreviousPage(R.string.a11y_previous_page),
    FirstPage(R.string.a11y_first_page),
    LastPage(R.string.a11y_last_page),
    OpenPages(R.string.a11y_open_pages),
    OpenCommands(R.string.a11y_open_commands),
    OpenMenu(R.string.a11y_open_menu),
}

/** What a [ReaderControl] asks the screen to do. */
internal sealed interface ControlEffect {
    data class Act(val action: ReaderAction) : ControlEffect

    /** Like a touch while 自動再生 plays: it stops, and the control does nothing else. */
    data object StopAutoPlay : ControlEffect

    data object Menu : ControlEffect

    data object Commands : ControlEffect

    data class Pages(val openedOn: Int) : ControlEffect
}

internal fun ReaderControl.effectIn(state: ReaderState): ControlEffect {
    if (state.autoPlay != null) return ControlEffect.StopAutoPlay
    return when (this) {
        ReaderControl.NextPage -> ControlEffect.Act(ReaderAction.TurnView(Turn.Forward))
        ReaderControl.PreviousPage -> ControlEffect.Act(ReaderAction.TurnView(Turn.Back))
        ReaderControl.FirstPage -> ControlEffect.Act(ReaderAction.First)
        ReaderControl.LastPage -> ControlEffect.Act(ReaderAction.Last)
        ReaderControl.OpenPages -> ControlEffect.Pages(state.current.pages.min())
        ReaderControl.OpenCommands -> ControlEffect.Commands
        ReaderControl.OpenMenu -> ControlEffect.Menu
    }
}

internal fun ControlEffect.applyTo(viewModel: ReaderViewModel, ui: ReaderUi) {
    when (this) {
        is ControlEffect.Act -> viewModel.act(action, SystemClock.uptimeMillis())
        ControlEffect.StopAutoPlay -> viewModel.dispatch(ReaderEvent.AutoPlayStopped)
        ControlEffect.Menu -> ui.toggleMenu()
        ControlEffect.Commands -> ui.commandBar = CommandBarState.Open
        is ControlEffect.Pages -> ui.sheet = ReaderSheet.Pages(openedOn)
    }
}

/**
 * Runs a panel item of the command bar: 閉じる closes the reader, メニュー opens the menu, an empty slot does nothing,
 * and a command runs, except 組ずらし away from a spread, which only says when it works (docs/spec.md section 4.8).
 */
internal fun runPanelItem(item: PanelItem, viewModel: ReaderViewModel, ui: ReaderUi, onClose: () -> Unit) {
    when (item) {
        PanelItem.Close -> onClose()
        PanelItem.Menu -> ui.toggleMenu()
        PanelItem.More -> ui.commandBar = CommandBarState.Open
        PanelItem.Blank -> Unit
        is PanelItem.Command ->
            if (item.command == ReaderCommand.ShiftPairing && viewModel.state?.shiftsPairs == false) ui.message = R.string.shift_needs_spread
            else ui.follow(viewModel.command(item.command, SystemClock.uptimeMillis()))
    }
}

/** The pages of [view] from 1, and how many the book has. */
internal data class PageSpan(val first: Int, val last: Int, val total: Int) {
    val single: Boolean get() = first == last
}

internal fun pageSpan(view: PageView, pageCount: Int): PageSpan {
    val pages = view.pages.sorted().map { it + 1 }
    return PageSpan(pages.first(), pages.last(), pageCount)
}

/** `12 ページ目、全 200 ページ`, or for a pair `12 から 13 ページ目、全 200 ページ`. */
@Composable
internal fun PageSpan.spoken(): String =
    if (single) stringResource(R.string.a11y_page_of, first, total) else stringResource(R.string.a11y_pages_of, first, last, total)

/**
 * The page as a node a screen reader can focus: it reads the page number, says it again whenever a turn changes it,
 * and takes a double tap as 次のページ. The other controls are its actions.
 */
@Composable
internal fun Modifier.pageAccessibility(state: ReaderState, onControl: (ReaderControl) -> Unit): Modifier {
    val description = pageSpan(state.current, state.pagination.shapes.size).spoken()
    val labels = ReaderControl.entries.associateWith { stringResource(it.label) }
    return semantics {
        contentDescription = description
        liveRegion = LiveRegionMode.Polite
        traversalIndex = 1f
        onClick(labels.getValue(ReaderControl.NextPage)) {
            onControl(ReaderControl.NextPage)
            true
        }
        customActions = ReaderControl.entries.filter { it != ReaderControl.NextPage }.map { control ->
            CustomAccessibilityAction(labels.getValue(control)) {
                onControl(control)
                true
            }
        }
    }
}

@Composable
internal fun BarAccessibility(barHeightDp: Float, onControl: (ReaderControl) -> Unit) {
    BarSemantics(
        barHeightDp,
        commandBar = stringResource(R.string.guide_command_bar),
        pageBar = stringResource(R.string.guide_page_bar),
        openCommands = stringResource(R.string.a11y_open_commands),
        openPages = stringResource(R.string.a11y_open_pages),
        onCommands = { onControl(ReaderControl.OpenCommands) },
        onPages = { onControl(ReaderControl.OpenPages) },
    )
}
