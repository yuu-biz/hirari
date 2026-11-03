package io.github.yuubiz.hirari.feature.reader

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.yuubiz.hirari.core.image.PixelSize
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
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

internal val SheetBackground = Color(0xF0202028)
internal val SheetText = Color.White
internal val SheetDisabled = Color(0xFF9C9CA4)
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

/** [page] fitted into the thumbnail size, decoded small the first time it is asked for. */
@Composable
internal fun Thumbnail(viewModel: ReaderViewModel, page: Int, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val size = with(density) { PixelSize(ThumbnailWidth.roundToPx(), ThumbnailHeight.roundToPx()) }
    val slot = remember(viewModel, size) { ThumbnailSlot(viewModel::cachedThumbnail) { viewModel.thumbnail(it, size) } }
    LaunchedEffect(slot, page) { slot.show(page) }
    Box(modifier.size(ThumbnailWidth, ThumbnailHeight).background(Color(0xFF404048)), contentAlignment = Alignment.Center) {
        slot.shown?.takeUnless { it.isRecycled }?.let {
            Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        }
    }
}

/** ページパネル (docs/spec.md section 4.8, ページバー). Each control moves the reader at once. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PagePanelSheet(
    viewModel: ReaderViewModel,
    state: ReaderState,
    sheet: ReaderSheet.Pages,
    onClose: () -> Unit,
    onNeighbor: (BookEdge) -> Unit,
    onBookmarks: () -> Unit,
) {
    val pageCount = state.pagination.shapes.size
    val head = state.current.pages.min()
    fun goTo(page: Int) = viewModel.dispatch(ReaderEvent.GoTo(page))
    fun act(action: ReaderAction) = viewModel.act(action, android.os.SystemClock.uptimeMillis())

    DisposableEffect(viewModel) {
        viewModel.showStrip(true)
        onDispose { viewModel.showStrip(false) }
    }

    Scrim(stringResource(R.string.page_panel_title), onClose) {
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(SheetBackground)
                .blockTouches()
                .verticalScroll(rememberScrollState())
                .padding(vertical = 8.dp),
        ) {
            ReadingOrder(state.config.direction) {
                ThumbnailStrip(viewModel, state, head, ::goTo)
                PageSlider(head, pageCount, ::goTo)
            }
            PageNumberRow(head, pageCount, ::goTo)
            FlowRow(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.Center) {
                SheetButton(R.string.page_panel_first) { act(ReaderAction.First) }
                SheetButton(R.string.page_panel_last) { act(ReaderAction.Last) }
                SheetButton(R.string.page_panel_shift_back) { act(ReaderAction.Shift(forward = false)) }
                SheetButton(R.string.page_panel_shift_forward) { act(ReaderAction.Shift(forward = true)) }
                SheetButton(R.string.page_panel_previous_book) { onNeighbor(BookEdge.Start) }
                SheetButton(R.string.page_panel_next_book) { onNeighbor(BookEdge.End) }
                SheetButton(R.string.command_bookmarks, onClick = onBookmarks)
                SheetButton(R.string.page_panel_undo) { goTo(sheet.openedOn) }
                SheetButton(R.string.panel_close, onClick = onClose)
            }
        }
    }
}

/** The strip and the slider run in the reading direction, so a right-to-left book starts at the right. */
@Composable
private fun ReadingOrder(direction: ReadingDirection, content: @Composable () -> Unit) {
    val layout = if (direction == ReadingDirection.RightToLeft) LayoutDirection.Rtl else LayoutDirection.Ltr
    CompositionLocalProvider(LocalLayoutDirection provides layout) { content() }
}

@Composable
private fun ThumbnailStrip(viewModel: ReaderViewModel, state: ReaderState, head: Int, goTo: (Int) -> Unit) {
    val pages = state.pagination.shapes.indices.toList()
    val list = rememberLazyListState(initialFirstVisibleItemIndex = (head - 2).coerceAtLeast(0))
    LaunchedEffect(head) {
        val visible = list.layoutInfo.visibleItemsInfo.map { it.index }
        if (head !in visible.dropLast(1).drop(1)) list.animateScrollToItem((head - 2).coerceAtLeast(0))
    }
    LazyRow(state = list, contentPadding = PaddingValues(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        itemsIndexed(pages, key = { _, page -> page }) { _, page ->
            val current = page in state.current.pages
            val spoken = stringResource(R.string.a11y_page_number, page + 1)
            Column(
                Modifier
                    .clickable(role = Role.Button) { goTo(page) }
                    .semantics {
                        contentDescription = spoken
                        selected = current
                    }
                    .padding(2.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Thumbnail(viewModel, page, if (current) Modifier.border(2.dp, Color.White) else Modifier)
                Text("${page + 1}", Modifier.decorative(), color = if (current) SheetText else SheetDisabled, fontSize = 12.sp)
            }
        }
    }
}

/** Dragging shows the page under the thumb every [PagePanel.PREVIEW_MS], and letting go settles there. */
@Composable
private fun PageSlider(head: Int, pageCount: Int, goTo: (Int) -> Unit) {
    var dragged by remember { mutableStateOf<Float?>(null) }
    val dragging = dragged != null
    val pageLabel = stringResource(R.string.a11y_page_slider)
    val shownPage = stringResource(R.string.a11y_page_of, (dragged?.let { PagePanel.atSlider(it, pageCount) } ?: head) + 1, pageCount)
    LaunchedEffect(dragging) {
        var shown = head
        while (true) {
            val page = dragged?.let { PagePanel.atSlider(it, pageCount) } ?: break
            if (page != shown) {
                goTo(page)
                shown = page
            }
            delay(PagePanel.PREVIEW_MS)
        }
    }
    Slider(
        value = dragged ?: head.toFloat(),
        onValueChange = { dragged = it },
        onValueChangeFinished = {
            dragged?.let { goTo(PagePanel.atSlider(it, pageCount)) }
            dragged = null
        },
        valueRange = 0f..(pageCount - 1).coerceAtLeast(1).toFloat(),
        enabled = pageCount > 1,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).semantics {
            contentDescription = pageLabel
            stateDescription = shownPage
        },
    )
}

@Composable
private fun PageNumberRow(head: Int, pageCount: Int, goTo: (Int) -> Unit) {
    val focus = LocalFocusManager.current
    val numberLabel = stringResource(R.string.page_panel_number)
    val field = remember { PageNumberField(head) }
    LaunchedEffect(head) { field.show(head) }
    @Composable
    fun RowScope.Field(modifier: Modifier) = OutlinedTextField(
        value = field.text,
        onValueChange = field::edit,
        singleLine = true,
        suffix = { Text("/ $pageCount", color = SheetDisabled) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Go),
        keyboardActions = KeyboardActions(onGo = {
            field.confirm(pageCount, head)?.let(goTo)
            focus.clearFocus()
        }),
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = SheetText, textAlign = TextAlign.End),
        modifier = modifier.semantics { contentDescription = numberLabel },
    )
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        // Six buttons of the touch-target size and the field need 480 dp; a narrower screen puts the field above them.
        if (maxWidth >= WIDE_PAGE_ROW) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                for (step in PagePanel.STEPS.take(3)) StepButton(step, Modifier.weight(1f)) { goTo(PagePanel.stepped(head, step, pageCount)) }
                Field(Modifier.weight(2.2f))
                for (step in PagePanel.STEPS.drop(3)) StepButton(step, Modifier.weight(1f)) { goTo(PagePanel.stepped(head, step, pageCount)) }
            }
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row { Field(Modifier.weight(1f).padding(horizontal = 24.dp)) }
                Row(Modifier.fillMaxWidth()) {
                    for (step in PagePanel.STEPS) StepButton(step, Modifier.weight(1f)) { goTo(PagePanel.stepped(head, step, pageCount)) }
                }
            }
        }
    }
}

private val WIDE_PAGE_ROW = 480.dp

@Composable
private fun StepButton(step: Int, modifier: Modifier, onClick: () -> Unit) {
    val spoken = stringResource(if (step < 0) R.string.a11y_step_back else R.string.a11y_step_forward, kotlin.math.abs(step))
    TextButton(onClick = onClick, modifier = modifier.semantics { contentDescription = spoken }, contentPadding = PaddingValues(horizontal = 2.dp)) {
        Text(
            if (step < 0) "−${-step}" else "+$step",
            Modifier.decorative(),
            color = SheetText,
            fontSize = 13.sp,
            maxLines = 1,
            autoSize = TextAutoSize.StepBased(minFontSize = 8.sp, maxFontSize = 13.sp),
        )
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
        sheet.fromPanel -> NeighborStart.entries
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
                Sibling.NoFolder -> if (sheet.fromPanel) Text(stringResource(R.string.book_end_no_folder), color = SheetDisabled, modifier = Modifier.padding(12.dp))
                is Sibling.Found, null -> for (start in starts) {
                    SheetButton(
                        if (atEnd) R.string.book_end_next else R.string.book_end_previous,
                        enabled = found != null,
                        args = arrayOf(stringResource(start.label)),
                    ) { (found as? Sibling.Found)?.let { onOpenBook(it.opening(start).request()) } }
                }
            }
            if (!sheet.fromPanel) {
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

/** メニュー (docs/spec.md section 4.8). Items of later milestones show disabled under their names. */
@Composable
internal fun MenuSheet(
    viewModel: ReaderViewModel,
    state: ReaderState,
    onCloseMenu: () -> Unit,
    onCloseReader: () -> Unit,
    onEffect: (CommandEffect?) -> Unit,
    onGuide: () -> Unit,
    onCoverRange: () -> Unit,
) {
    fun run(item: MenuItem) {
        val command = when (item) {
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
            .clickable(enabled = item.available, role = Role.Button, onClick = onClick)
            .minTouchTarget()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(item.label), color = if (item.available) SheetText else SheetDisabled, modifier = Modifier.weight(1f))
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

    val levelLabel = stringResource(R.string.adjust_level)
    val levelText = if (adjust.level > 0) "+${adjust.level}" else "${adjust.level}"
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
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) {
                    SheetButton(R.string.adjust_global, enabled = adjust != viewModel.globalAdjust) { set(viewModel.globalAdjust) }
                    SheetButton(R.string.panel_close, onClick = onClose)
                }
            }
        }
    }
}

private val ADJUST_LEVELS = -5..5

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
internal fun SwitchRow(label: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .minTouchTarget()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(label), color = SheetText, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null)
    }
}
