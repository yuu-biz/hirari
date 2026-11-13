package io.github.yuubiz.hirari.feature.reader

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.yuubiz.hirari.core.image.PixelSize
import io.github.yuubiz.hirari.core.model.PageShape
import io.github.yuubiz.hirari.core.model.ReaderAction
import io.github.yuubiz.hirari.core.model.ReadingDirection
import io.github.yuubiz.hirari.core.ui.blockTouches
import io.github.yuubiz.hirari.core.ui.decorative
import kotlinx.coroutines.delay

internal const val PAGE_STRIP_TAG = "page-strip"

/** The number of the pages on screen under their cells. */
internal val SheetMark = Color(0xFFFF8A80)
private val CellPlaceholder = Color(0xFF404048)

/** A strip as wide as this or wider has the slider and all controls on one row. */
private val ONE_ROW_WIDTH = 600.dp

/**
 * The thumbnails the strip shows. One is made at a time, in [PageStrip.madeOrder] from the page in the middle of the
 * strip, and a change of the strip's pages starts the order again from its new middle.
 */
@Stable
internal class StripThumbnails(
    private val cached: (Int) -> ImageBitmap?,
    private val load: suspend (Int) -> ImageBitmap?,
    val sourceShape: (Int) -> PageShape.Known? = { null },
) {
    private val shown = mutableStateMapOf<Int, ImageBitmap>()
    private val failed = mutableStateMapOf<Int, Unit>()
    private var order by mutableStateOf(emptyList<Int>())

    operator fun get(page: Int): ImageBitmap? = shown[page]

    /** The strip's pages in the order to make them; the thumbnails of other pages are let go. */
    fun want(pages: List<Int>) {
        order = pages
        val kept = pages.toSet()
        shown.keys.filter { it !in kept }.forEach(shown::remove)
    }

    suspend fun make() {
        snapshotFlow { order.firstOrNull { it !in shown && it !in failed } }.collect { page ->
            if (page == null) return@collect
            val image = cached(page) ?: load(page)
            if (image == null) failed[page] = Unit else if (page in order) shown[page] = image
        }
    }
}

/** ページパネル (docs/spec.md section 4.8, ページバー). */
@Composable
internal fun PagePanelSheet(viewModel: ReaderViewModel, state: ReaderState, sheet: ReaderSheet.Pages, onClose: () -> Unit) {
    val head = state.current.pages.min()
    fun goTo(page: Int) = viewModel.dispatch(ReaderEvent.GoTo(page))

    DisposableEffect(viewModel) {
        viewModel.showStrip(true)
        onDispose { viewModel.showStrip(false) }
    }
    val size = stripDecodeSize()
    val thumbnails = remember(viewModel, size) {
        StripThumbnails(
            { viewModel.cachedThumbnail(it)?.asImageBitmap() },
            { viewModel.thumbnail(it, size)?.asImageBitmap() },
            { viewModel.thumbnailShape(it) },
        )
    }

    Scrim(stringResource(R.string.page_panel_title), onClose) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val cellHeight = PageStrip.cellHeightDp(maxWidth.value, maxHeight.value).dp
            val oneRow = maxWidth >= ONE_ROW_WIDTH
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(SheetBackground)
                    .blockTouches()
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 8.dp),
            ) {
                PagePanelContent(
                    shapes = state.pagination.shapes,
                    head = head,
                    current = state.current.pages,
                    direction = state.config.direction,
                    cellHeight = cellHeight,
                    oneRow = oneRow,
                    thumbnails = thumbnails,
                    goTo = ::goTo,
                    onPick = { page ->
                        goTo(page)
                        onClose()
                    },
                ) {
                    PagePanelControls(state, sheet.openedOn, viewModel::dispatch)
                }
            }
        }
    }
}

/** The bottom actions keep the panel open; only picking a thumbnail closes it. */
@Composable
internal fun PagePanelControls(state: ReaderState, openedOn: Int, dispatch: (ReaderEvent) -> Unit) {
    PageNumberInput(state.current.pages.min(), state.pagination.shapes.size) { dispatch(ReaderEvent.GoTo(it)) }
    SheetButton(R.string.page_panel_undo) { dispatch(ReaderEvent.GoTo(openedOn)) }
    val pairs = state.shiftsPairs
    for (forward in listOf(false, true)) {
        val description = stringResource(if (forward) R.string.menu_shift_forward else R.string.menu_shift_back)
        TextButton(
            onClick = { dispatch(ReaderEvent.Act(ReaderAction.Shift(forward), android.os.SystemClock.uptimeMillis())) },
            enabled = pairs,
            contentPadding = PaddingValues(0.dp),
            modifier = Modifier.size(48.dp).semantics { contentDescription = description },
        ) {
            Text(
                stringResource(if (forward) R.string.page_panel_shift_forward else R.string.page_panel_shift_back),
                modifier = Modifier.decorative(),
                color = if (pairs) SheetText else SheetDisabled,
            )
        }
    }
}

/**
 * The strip of thumbnails, the slider, and [controls], for the pages of [shapes] with [head] on screen. Scrolling
 * the strip moves the slider to the page in its middle and leaves the reader where it is; the slider and
 * [controls] move the reader through [goTo], and the strip follows to put [head] in its middle. A tap on a cell
 * is [onPick].
 */
@Composable
internal fun PagePanelContent(
    shapes: List<PageShape>,
    head: Int,
    current: Collection<Int>,
    direction: ReadingDirection,
    cellHeight: Dp,
    oneRow: Boolean,
    thumbnails: StripThumbnails,
    goTo: (Int) -> Unit,
    onPick: (Int) -> Unit,
    controls: @Composable () -> Unit,
) {
    var middle by remember { mutableIntStateOf(head) }
    ReadingOrder(direction) {
        Strip(shapes, head, current, cellHeight, thumbnails, onMiddle = { middle = it }, onPick = onPick)
    }
    if (oneRow) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { ReadingOrder(direction) { PageSlider(middle, shapes.size, goTo) } }
            controls()
        }
    } else {
        ReadingOrder(direction) { PageSlider(middle, shapes.size, goTo) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) { controls() }
    }
}

/** The strip and the slider run in the reading direction, so a right-to-left book starts at the right. */
@Composable
private fun ReadingOrder(direction: ReadingDirection, content: @Composable () -> Unit) {
    val layout = if (direction == ReadingDirection.RightToLeft) LayoutDirection.Rtl else LayoutDirection.Ltr
    CompositionLocalProvider(LocalLayoutDirection provides layout) { content() }
}

@Composable
private fun Strip(
    shapes: List<PageShape>,
    head: Int,
    current: Collection<Int>,
    cellHeight: Dp,
    thumbnails: StripThumbnails,
    onMiddle: (Int) -> Unit,
    onPick: (Int) -> Unit,
) {
    val density = LocalDensity.current
    val widths = shapes.mapIndexed { page, shape ->
        PageStrip.cellWidthDp(if (shape == PageShape.Unknown) thumbnails.sourceShape(page) ?: shape else shape, cellHeight.value).dp
    }
    val upright = cellHeight * PageStrip.UPRIGHT_ASPECT
    // With the side padding of half the strip less half an upright cell, a cell scrolled to this offset is in the middle.
    fun middleOffset(page: Int) = with(density) { ((widths[page] - upright) / 2).roundToPx() }
    val list = rememberLazyListState(head, middleOffset(head))
    val reportMiddle by rememberUpdatedState(onMiddle)
    val middle by remember(list) {
        derivedStateOf {
            val layout = list.layoutInfo
            PageStrip.centre(layout.visibleItemsInfo.map { PageStrip.Cell(it.index, it.offset, it.size) }, layout.viewportStartOffset, layout.viewportEndOffset)
        }
    }
    LaunchedEffect(list) {
        snapshotFlow { middle }.collect { page -> page?.let(reportMiddle) }
    }
    var followedHead by remember { mutableIntStateOf(head) }
    var previousWidths by remember { mutableStateOf(widths) }
    LaunchedEffect(head, widths) {
        if (previousWidths != widths) {
            val anchor = if (followedHead != head) head else middle ?: head
            list.scrollToItem(anchor, middleOffset(anchor))
        } else if (middle != null && middle != head) {
            list.animateScrollToItem(head, middleOffset(head))
        }
        followedHead = head
        previousWidths = widths
    }
    LaunchedEffect(list, thumbnails) {
        snapshotFlow { list.layoutInfo.visibleItemsInfo.map { it.index } to middle }.collect { (visible, centre) ->
            if (visible.isEmpty() || centre == null) return@collect
            val around = (visible.min() - PageStrip.MARGIN_PAGES).coerceAtLeast(0)..(visible.max() + PageStrip.MARGIN_PAGES).coerceAtMost(shapes.lastIndex)
            thumbnails.want(PageStrip.madeOrder(centre, around.toList()))
        }
    }
    LaunchedEffect(thumbnails) { thumbnails.make() }

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val side = ((maxWidth - upright) / 2).coerceAtLeast(0.dp)
        LazyRow(
            state = list,
            contentPadding = PaddingValues(horizontal = side, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(PageStrip.GAP_DP.dp),
            flingBehavior = rememberSnapFlingBehavior(list, SnapPosition.Center),
            modifier = Modifier.testTag(PAGE_STRIP_TAG),
        ) {
            items(shapes.size, key = { it }) { page ->
                StripCell(page, widths[page], cellHeight, page in current, thumbnails[page]) { onPick(page) }
            }
        }
    }
}

@Composable
private fun StripCell(page: Int, width: Dp, height: Dp, onScreen: Boolean, image: ImageBitmap?, onClick: () -> Unit) {
    val spoken = stringResource(R.string.a11y_page_number, page + 1)
    Column(
        Modifier
            .width(width)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = spoken
                selected = onScreen
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(width, height)
                .background(CellPlaceholder)
                .then(if (onScreen) Modifier.border(2.dp, Color.White) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            image?.let { Image(it, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
        }
        Text(
            "${page + 1}",
            Modifier.decorative().padding(top = 2.dp),
            color = if (onScreen) SheetMark else SheetText,
            fontWeight = if (onScreen) FontWeight.Bold else null,
            fontSize = 13.sp,
        )
    }
}

/**
 * At rest the slider shows [middle], the page in the middle of the strip. Dragging shows the page under the thumb
 * every [PagePanel.PREVIEW_MS], and letting go settles there.
 */
@Composable
private fun PageSlider(middle: Int, pageCount: Int, goTo: (Int) -> Unit) {
    var dragged by remember { mutableStateOf<Float?>(null) }
    val dragging = dragged != null
    val pageLabel = stringResource(R.string.a11y_page_slider)
    val shownPage = stringResource(R.string.a11y_page_of, (dragged?.let { PagePanel.atSlider(it, pageCount) } ?: middle) + 1, pageCount)
    LaunchedEffect(dragging) {
        var shown = middle
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
        value = dragged ?: middle.toFloat(),
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
private fun PageNumberInput(head: Int, pageCount: Int, goTo: (Int) -> Unit) {
    val focus = LocalFocusManager.current
    val numberLabel = stringResource(R.string.page_panel_number)
    val field = remember { PageNumberField(head) }
    LaunchedEffect(head) { field.show(head) }
    OutlinedTextField(
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
        modifier = Modifier.width(PAGE_FIELD_WIDTH).padding(horizontal = 8.dp).semantics { contentDescription = numberLabel },
    )
}

private val PAGE_FIELD_WIDTH = 176.dp

/** The box the thumbnails of the strip and the page bar are decoded into, so the two share them. */
@Composable
internal fun stripDecodeSize(): PixelSize {
    val density = LocalDensity.current.density
    return remember(density) { PageStrip.decodeSize(density).let { (width, height) -> PixelSize(width, height) } }
}
