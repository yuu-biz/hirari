package io.github.yuubiz.hirari.feature.reader

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.yuubiz.hirari.core.model.BookEdge
import io.github.yuubiz.hirari.core.model.BookAddress
import io.github.yuubiz.hirari.core.model.ImageAdjust
import io.github.yuubiz.hirari.core.model.NeighborStart
import io.github.yuubiz.hirari.core.model.OpenBookRequest
import io.github.yuubiz.hirari.core.model.PageView
import io.github.yuubiz.hirari.core.model.ReaderAction
import io.github.yuubiz.hirari.core.model.ReaderCommand
import io.github.yuubiz.hirari.core.model.ReaderOrientation
import io.github.yuubiz.hirari.core.model.ReadingDirection
import io.github.yuubiz.hirari.core.model.Sibling
import io.github.yuubiz.hirari.core.model.opening
import io.github.yuubiz.hirari.core.ui.blockTouches
import io.github.yuubiz.hirari.core.ui.decorative
import io.github.yuubiz.hirari.core.ui.minTouchTarget
import kotlin.math.roundToInt

internal val SheetBackground = Color(0xF0202028)
internal val SheetText = Color.White
internal val SheetDisabled = Color(0xFF9C9CA4)

/** The page bar's preview of the page under the finger. */
internal val ThumbnailWidth = 60.dp
internal val ThumbnailHeight = 85.dp

/**
 * A tap outside the sheet closes it, and the reader under it gets no touch. For a screen reader the sheet is a pane
 * named [title], and the scrim is the last stop in it, a button named 閉じる; the sheet's own area takes no tap.
 */
@Composable
internal fun Scrim(title: String, onClose: () -> Unit, content: @Composable BoxScope.() -> Unit) {
    val close = stringResource(R.string.panel_close)
    Box(Modifier.fillMaxSize().semantics {
        paneTitle = title
        isTraversalGroup = true
    }) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0x33000000))
                .clickable(onClickLabel = close, role = Role.Button, interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose)
                .semantics {
                    contentDescription = close
                    traversalIndex = 1f
                },
        )
        content()
    }
}

/**
 * [page] fitted into the page bar's preview. The thumbnail is the page panel's, decoded small the first time either
 * asks for it.
 */
@Composable
internal fun Thumbnail(viewModel: ReaderViewModel, page: Int, modifier: Modifier = Modifier) {
    val size = stripDecodeSize()
    val slot = remember(viewModel, size) { ThumbnailSlot(viewModel::cachedThumbnail) { viewModel.thumbnail(it, size) } }
    LaunchedEffect(slot, page) { slot.show(page) }
    Box(modifier.size(ThumbnailWidth, ThumbnailHeight).background(Color(0xFF404048)), contentAlignment = Alignment.Center) {
        slot.shown?.takeUnless { it.isRecycled }?.let {
            Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        }
    }
}

/** [description] is what a screen reader says in place of the label, for a label such as `p. 12` that it would spell out. */
@Composable
internal fun SheetButton(label: Int, enabled: Boolean = true, args: Array<Any> = emptyArray(), description: String? = null, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled, modifier = if (description == null) Modifier else Modifier.semantics { contentDescription = description }) {
        Text(stringResource(label, *args), Modifier.then(if (description == null) Modifier else Modifier.decorative()), color = if (enabled) SheetText else SheetDisabled)
    }
}

/**
 * The neighboring book toward [sheet]'s edge (docs/spec.md section 4.8, 本の端での動作 and ページバー). Until the
 * library has answered, its items show disabled; a book with no folder has none.
 */
@Composable
internal fun BookEndSheet(
    viewModel: ReaderViewModel,
    sheet: ReaderSheet.BookEnd,
    onClose: () -> Unit,
    onCloseReader: () -> Unit,
    onOpenBook: (OpenBookRequest) -> Unit,
) {
    var sibling by remember(sheet.edge) { mutableStateOf<Sibling<BookAddress>?>(null) }
    LaunchedEffect(sheet.edge) { sibling = viewModel.sibling(sheet.edge) }
    val atEnd = sheet.edge == BookEdge.End
    val starts = when {
        sheet.fromMenu -> NeighborStart.entries
        atEnd -> listOf(NeighborStart.Saved, NeighborStart.First)
        else -> listOf(NeighborStart.Saved, NeighborStart.Last)
    }
    Scrim(stringResource(R.string.neighbor_title), onClose) {
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(SheetBackground, RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                .blockTouches()
                .verticalScroll(rememberScrollState())
                .padding(vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when (val found = sibling) {
                Sibling.Last -> Text(
                    stringResource(if (atEnd) R.string.book_end_last_in_folder else R.string.book_end_first_in_folder),
                    color = SheetDisabled,
                    modifier = Modifier.padding(12.dp),
                )
                Sibling.NoFolder -> if (sheet.fromMenu) Text(stringResource(R.string.book_end_no_folder), color = SheetDisabled, modifier = Modifier.padding(12.dp))
                is Sibling.Found, null -> for (start in starts) {
                    SheetButton(
                        if (atEnd) R.string.book_end_next else R.string.book_end_previous,
                        enabled = found != null,
                        args = arrayOf(stringResource(start.label)),
                    ) { (found as? Sibling.Found)?.let { onOpenBook(it.opening(start).request()) } }
                }
            }
            if (!sheet.fromMenu) {
                if (atEnd) {
                    SheetButton(R.string.book_end_from_first) {
                        viewModel.act(ReaderAction.First, android.os.SystemClock.uptimeMillis())
                        onClose()
                    }
                }
                SheetButton(R.string.book_end_close, onClick = onCloseReader)
                SheetButton(R.string.menu_restore_and_close) {
                    viewModel.restorePosition()
                    onCloseReader()
                }
            } else {
                SheetButton(R.string.panel_close, onClick = onClose)
            }
        }
    }
}

private val NeighborStart.label: Int
    get() = when (this) {
        NeighborStart.Saved -> R.string.neighbor_saved
        NeighborStart.First -> R.string.neighbor_first
        NeighborStart.Last -> R.string.neighbor_last
    }

/** メニュー (docs/spec.md section 4.8). */
@Composable
internal fun MenuSheet(
    viewModel: ReaderViewModel,
    state: ReaderState,
    onCloseMenu: () -> Unit,
    onCloseReader: () -> Unit,
    onEffect: (CommandEffect?) -> Unit,
    onGuide: () -> Unit,
    onCoverRange: () -> Unit,
    onNeighbor: (BookEdge) -> Unit,
) {
    fun act(action: ReaderAction) {
        viewModel.act(action, android.os.SystemClock.uptimeMillis())
        onCloseMenu()
    }

    fun run(item: MenuItem) {
        val command = when (item) {
            MenuItem.FirstPage -> return act(ReaderAction.First)
            MenuItem.LastPage -> return act(ReaderAction.Last)
            MenuItem.ShiftBack -> return act(ReaderAction.Shift(forward = false))
            MenuItem.ShiftForward -> return act(ReaderAction.Shift(forward = true))
            MenuItem.PreviousBook -> return onNeighbor(BookEdge.Start)
            MenuItem.NextBook -> return onNeighbor(BookEdge.End)
            MenuItem.RestoreAndClose -> {
                viewModel.restorePosition()
                onCloseReader()
                return
            }
            MenuItem.SetCoverFromRange -> {
                onCloseMenu()
                onCoverRange()
                return
            }
            MenuItem.Guide -> {
                onCloseMenu()
                onGuide()
                return
            }
            MenuItem.About -> {
                onCloseMenu()
                onEffect(CommandEffect.OpenSettings(SettingsTarget.About))
                return
            }
            MenuItem.SharePage -> ReaderCommand.SharePage
            MenuItem.SetCover -> ReaderCommand.SetCover
            MenuItem.AddBookmark -> ReaderCommand.AddBookmark
            MenuItem.Bookmarks -> ReaderCommand.Bookmarks
            MenuItem.BookSettings -> ReaderCommand.BookSettings
            MenuItem.AutoPlay -> ReaderCommand.AutoPlay
            MenuItem.Breath -> ReaderCommand.Breath
            MenuItem.Orientation -> ReaderCommand.Orientation
            MenuItem.MirrorControls -> ReaderCommand.MirrorControls
            MenuItem.Settings -> ReaderCommand.Settings
            MenuItem.EditCommandBar -> ReaderCommand.EditCommandBar
            else -> return
        }
        val effect = viewModel.command(command, android.os.SystemClock.uptimeMillis())
        onCloseMenu()
        onEffect(effect)
    }

    Scrim(stringResource(R.string.panel_menu), onCloseMenu) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val width: Dp = menuWidthDp(maxWidth.value, maxHeight.value).dp
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .width(width)
                    .background(SheetBackground, RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                    .blockTouches()
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 8.dp),
            ) {
                MenuItem.entries.groupBy { it.group }.values.forEachIndexed { i, items ->
                    if (i > 0) HorizontalDivider(color = Color(0x33FFFFFF), modifier = Modifier.padding(vertical = 4.dp))
                    for (item in items) MenuRow(item, item.value(viewModel, state)) { run(item) }
                }
            }
        }
    }
}

@Composable
private fun MenuRow(item: MenuItem, value: String?, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .minTouchTarget()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(item.label), color = SheetText, modifier = Modifier.weight(1f))
        if (value != null) Text(value, color = SheetDisabled)
    }
}

@Composable
private fun MenuItem.value(viewModel: ReaderViewModel, state: ReaderState): String? = when (this) {
    MenuItem.Orientation -> stringResource(
        when (state.config.orientation) {
            ReaderOrientation.Portrait -> R.string.orientation_portrait
            ReaderOrientation.Landscape -> R.string.orientation_landscape
            ReaderOrientation.System -> R.string.orientation_device
        },
    )
    MenuItem.MirrorControls -> stringResource(if (state.config.mirrored) R.string.menu_on else R.string.menu_off)
    else -> null
}

private val MenuItem.label: Int
    get() = when (this) {
        MenuItem.AddBookmark -> R.string.command_add_bookmark
        MenuItem.Bookmarks -> R.string.command_bookmarks
        MenuItem.BookSettings -> R.string.command_book_settings
        MenuItem.SetCover -> R.string.command_set_cover
        MenuItem.SetCoverFromRange -> R.string.menu_set_cover_from_range
        MenuItem.SharePage -> R.string.command_share_page
        MenuItem.RestoreAndClose -> R.string.menu_restore_and_close
        MenuItem.FirstPage -> R.string.menu_first_page
        MenuItem.LastPage -> R.string.menu_last_page
        MenuItem.ShiftBack -> R.string.menu_shift_back
        MenuItem.ShiftForward -> R.string.menu_shift_forward
        MenuItem.PreviousBook -> R.string.menu_previous_book
        MenuItem.NextBook -> R.string.menu_next_book
        MenuItem.AutoPlay -> R.string.command_auto_play
        MenuItem.Breath -> R.string.command_breath
        MenuItem.Orientation -> R.string.command_orientation
        MenuItem.MirrorControls -> R.string.command_mirror_controls
        MenuItem.Guide -> R.string.menu_guide
        MenuItem.Settings -> R.string.command_settings
        MenuItem.EditCommandBar -> R.string.command_edit_command_bar
        MenuItem.About -> R.string.menu_about
    }

/**
 * 画像補正 (docs/spec.md section 4.8). Every change shows at once and is kept as this book's override.
 * 全体の設定に戻す goes back to the global `adjust*` settings.
 */
@Composable
internal fun AdjustSheet(viewModel: ReaderViewModel, state: ReaderState, onClose: () -> Unit) {
    val adjust = state.config.adjust
    fun set(next: ImageAdjust) = viewModel.dispatch(ReaderEvent.ConfigChanged(state.config.copy(adjust = next)))

    Scrim(stringResource(R.string.command_image_adjust), onClose) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .width(menuWidthDp(maxWidth.value, maxHeight.value).dp)
                    .background(SheetBackground, RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                    .blockTouches()
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 8.dp),
            ) {
                AdjustControls(adjust, ::set)
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) {
                    SheetButton(R.string.adjust_global, enabled = adjust != viewModel.globalAdjust) { set(viewModel.globalAdjust) }
                    SheetButton(R.string.panel_close, onClick = onClose)
                }
            }
        }
    }
}

private val ADJUST_LEVELS = -5..5

/** 明るさ, シャープ, グレースケール, and 白黒反転, each change shown at once. */
@Composable
internal fun AdjustControls(adjust: ImageAdjust, set: (ImageAdjust) -> Unit) {
    val levelLabel = stringResource(R.string.adjust_level)
    val levelText = if (adjust.level > 0) "+${adjust.level}" else "${adjust.level}"
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp).decorative(), verticalAlignment = Alignment.CenterVertically) {
        Text(levelLabel, color = SheetText, modifier = Modifier.weight(1f))
        Text(levelText, color = SheetDisabled)
    }
    Slider(
        value = adjust.level.toFloat(),
        onValueChange = { value ->
            val level = value.roundToInt().coerceIn(ADJUST_LEVELS)
            if (level != adjust.level) set(adjust.copy(level = level))
        },
        valueRange = ADJUST_LEVELS.first.toFloat()..ADJUST_LEVELS.last.toFloat(),
        steps = ADJUST_LEVELS.last - ADJUST_LEVELS.first - 1,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).semantics {
            contentDescription = levelLabel
            stateDescription = levelText
        },
    )
    SwitchRow(R.string.adjust_sharpen, adjust.sharpen) { set(adjust.copy(sharpen = it)) }
    SwitchRow(R.string.adjust_mono, adjust.mono) { set(adjust.copy(mono = it)) }
    SwitchRow(R.string.adjust_negative, adjust.negative) { set(adjust.copy(negative = it)) }
}

/** ページを共有 or 範囲を選んで表紙にする ([title] asks) on a pair: the two pages as they sit on screen, the earlier one on the right in a right-to-left book. */
@Composable
internal fun SharePickSheet(view: PageView.Two, direction: ReadingDirection, title: Int, onPick: (Int) -> Unit, onClose: () -> Unit) {
    val onScreen = if (direction == ReadingDirection.RightToLeft) listOf(view.later, view.earlier) else listOf(view.earlier, view.later)
    Scrim(stringResource(title), onClose) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .width(menuWidthDp(maxWidth.value, maxHeight.value).dp)
                    .background(SheetBackground, RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                    .blockTouches()
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(stringResource(title), color = SheetText, modifier = Modifier.padding(12.dp).semantics { heading() })
                FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    for (page in onScreen) {
                        SheetButton(R.string.share_page_number, args = arrayOf(page + 1), description = stringResource(R.string.a11y_page_number, page + 1)) { onPick(page) }
                    }
                }
                SheetButton(R.string.panel_close, onClick = onClose)
            }
        }
    }
}

@Composable
internal fun SwitchRow(label: Int, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .minTouchTarget()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(label), color = if (enabled) SheetText else SheetDisabled, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}
