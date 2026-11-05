package io.github.yuubiz.hirari.feature.library

import androidx.compose.ui.semantics.contentDescription
import androidx.compose.material3.Icon
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridItemInfo
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.yuubiz.hirari.core.model.LibraryTextSize
import io.github.yuubiz.hirari.core.model.LibraryView
import io.github.yuubiz.hirari.core.model.ThumbSize
import io.github.yuubiz.hirari.core.ui.LibraryColors
import io.github.yuubiz.hirari.core.ui.LocalLibraryColors
import io.github.yuubiz.hirari.core.ui.decorative
import io.github.yuubiz.hirari.core.ui.minTouchTarget
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * The rows in the chosen view (docs/spec.md section 4.3), after the parent item when [onUp] is given. Binding a row
 * reads only the row: no query and no decode, so a 1000-entry folder scrolls on what the listing already holds; the
 * covers come from [CoverBox]. An empty folder, or a filter that matches nothing, says so under the parent item.
 */
@Composable
@OptIn(ExperimentalFoundationApi::class)
internal fun FileList(content: FolderContent.Shown, reveal: String?, onOpen: (FileRow) -> Unit, onLongPress: (FileRow) -> Unit, onUp: (() -> Unit)? = null, folderKey: FolderKey? = null) {
    val scope = rememberCoroutineScope()
    var folded by rememberSaveable(folderKey) { mutableStateOf(arrayListOf<String>()) }
    val cells = remember(content.rows, content.look.grouping, onUp != null, folded) {
        fileCells(content.rows, content.look.grouping, onUp != null, folded.toSet())
    }
    fun toggle(header: ListCell.Header) {
        folded = ArrayList(if (header.key in folded) folded - header.key else folded + header.key)
    }
    val empty = if (content.rows.isEmpty()) (if (content.total == 0) R.string.folder_empty else R.string.folder_no_match) else null
    var revealed by rememberSaveable(folderKey, reveal) { mutableStateOf(reveal == null) }
    suspend fun revealOnce(scrollTo: suspend (Int) -> Unit) {
        if (revealed) return
        revealed = true
        val at = cells.indexOfFirst { it is ListCell.Entry && it.row.path == reveal }
        if (at >= 0) scrollTo(at)
    }
    val focus = remember { CoverFocus() }
    val held by content.covers.covers.held.collectAsState(initial = false)
    Column(Modifier.fillMaxSize()) {
        if (held && content.view != LibraryView.List) HeldCovers(onResume = content.covers.covers::resume)
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            CompositionLocalProvider(LocalDensity provides rememberTextScaled(content.look.textSize)) {
                when (content.view) {
                    LibraryView.Grid -> {
                        // Save a cell key, not the header-dependent lazy index. Compose retains keys on live updates.
                        val grid = rememberSaveable(folderKey, saver = listSaver<LazyGridState, Any>(
                            save = { listOf(cells.getOrNull(it.firstVisibleItemIndex)?.key.orEmpty(), it.firstVisibleItemScrollOffset) },
                            restore = { saved -> LazyGridState(cells.indexOfFirst { it.key == saved[0] }.coerceAtLeast(0), saved[1] as Int) },
                        )) { LazyGridState() }
                        LaunchedEffect(Unit) { revealOnce { grid.scrollToItem(it) } }
                        val landscape = isLandscapeWindow()
                        val columns = content.look.columns(maxWidth, landscape)
                        val size = content.look.gridThumbSize(maxWidth / columns, landscape)
                        val covers = remember(content.covers, size) { content.covers.copy(sizePx = content.covers.covers.sizePx(size)) }
                        val fitter = rememberTitleFitter(constraints.maxWidth / columns, content.look.nameLines)
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(columns),
                            state = grid,
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            cells.forEach { cell ->
                                if (cell is ListCell.Header) {
                                    stickyHeader(key = cell.key) { GroupHeader(cell, cell.key in folded) { toggle(cell) } }
                                } else item(key = cell.key) {
                                    when (cell) {
                                        ListCell.Parent -> GridParentCell(fitter, content.look, onUp ?: {})
                                        is ListCell.Entry -> GridCell(cell.row, covers, focus, fitter, content.look, onOpen, onLongPress)
                                        is ListCell.Header -> Unit
                                    }
                                }
                            }
                            if (empty != null) item(span = { GridItemSpan(maxLineSpan) }) { EmptyRow(empty) }
                        }
                        PrefetchCovers(cells.coverRefs(covers), covers.covers, focus, { grid.isScrollInProgress }) { grid.layoutInfo.visibleItemsInfo.visibleRange() }
                        FastScroller(cells.size, { grid.firstVisibleItemIndex }, { grid.isScrollInProgress }, { scope.launch { grid.scrollToItem(it) } }, Modifier.align(Alignment.CenterEnd))
                    }
                    LibraryView.ThumbnailList, LibraryView.List -> {
                        val list = rememberSaveable(folderKey, saver = listSaver<LazyListState, Any>(
                            save = { listOf(cells.getOrNull(it.firstVisibleItemIndex)?.key.orEmpty(), it.firstVisibleItemScrollOffset) },
                            restore = { saved -> LazyListState(cells.indexOfFirst { it.key == saved[0] }.coerceAtLeast(0), saved[1] as Int) },
                        )) { LazyListState() }
                        LaunchedEffect(Unit) { revealOnce { list.scrollToItem(it) } }
                        val covers = content.covers.takeIf { content.view == LibraryView.ThumbnailList }
                        LazyColumn(state = list, modifier = Modifier.fillMaxSize()) {
                            cells.forEach { cell ->
                                if (cell is ListCell.Header) {
                                    stickyHeader(key = cell.key) { GroupHeader(cell, cell.key in folded) { toggle(cell) } }
                                } else item(key = cell.key) {
                                    when (cell) {
                                        ListCell.Parent -> ListParentRow(covers != null, content.look, onUp ?: {})
                                        is ListCell.Entry -> ListRow(cell.row, covers, focus, content.look, onOpen, onLongPress)
                                        is ListCell.Header -> Unit
                                    }
                                }
                            }
                            if (empty != null) item { EmptyRow(empty) }
                        }
                        if (covers != null) {
                            PrefetchCovers(cells.coverRefs(content.covers), content.covers.covers, focus, { list.isScrollInProgress }) { list.layoutInfo.visibleItemsInfo.visibleRange() }
                        }
                        FastScroller(cells.size, { list.firstVisibleItemIndex }, { list.isScrollInProgress }, { scope.launch { list.scrollToItem(it) } }, Modifier.align(Alignment.CenterEnd))
                    }
                }
            }
        }
    }
}

/** A full-width header with one accessible toggle; it never binds a CoverBox. */
@Composable
private fun GroupHeader(header: ListCell.Header, folded: Boolean, toggle: () -> Unit) {
    val name = header.author ?: stringResource(R.string.group_no_author)
    val action = stringResource(if (folded) R.string.group_unfold else R.string.group_fold)
    val description = stringResource(R.string.group_description, name, header.count, action)
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClick = toggle).minTouchTarget()
            .clearAndSetSemantics {
                heading()
                contentDescription = description
                onClick(action) { toggle(); true }
            }.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(name, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
        Text(stringResource(R.string.group_count, header.count), style = MaterialTheme.typography.labelLarge)
        Text(if (folded) "+" else "−", Modifier.padding(start = 12.dp))
    }
}

/** The density of the list's text (docs/spec.md section 4.3, 文字の大きさ): the device's text size times [size]'s scale. */
@Composable
private fun rememberTextScaled(size: LibraryTextSize): Density {
    val density = LocalDensity.current
    return remember(density, size) { if (size.scale == 1f) density else Density(density.density, density.fontScale * size.scale) }
}

@Composable
private fun EmptyRow(@StringRes text: Int) {
    Text(
        stringResource(text),
        Modifier.fillMaxWidth().padding(24.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

/**
 * The item that goes up to the parent folder, first in a grid (docs/spec.md section 4.3, 親フォルダの項目): an arrow
 * the size of a cover and `..` where a name goes, as tall as the other cells.
 */
@Composable
private fun GridParentCell(fitter: TitleFitter, look: ListLook, onUp: () -> Unit) {
    val colors = LocalLibraryColors.current
    val label = stringResource(R.string.folder_up)
    Column(Modifier.rowPress(onUp, null).semantics { contentDescription = label }.padding(CELL_PADDING)) {
        Box(Modifier.fillMaxWidth().aspectRatio(look.aspect.ratio).decorative(), contentAlignment = Alignment.Center) {
            Icon(ListIcons.Up, null, Modifier.fillMaxSize(0.7f), tint = colors.stateFolder)
        }
        Text(PARENT_KEY, Modifier.fillMaxWidth().padding(top = 4.dp).decorative(), color = colors.stateFolder, style = fitter.style, minLines = fitter.lines, maxLines = fitter.lines)
        if (look.noteLine) Text("", Modifier.fillMaxWidth().decorative(), style = fitter.style, minLines = 1, maxLines = 1)
    }
}

/** The parent item of a list: the arrow where a cover or a mark goes, then its name. */
@Composable
private fun ListParentRow(withCovers: Boolean, look: ListLook, onUp: () -> Unit) {
    val colors = LocalLibraryColors.current
    Row(
        Modifier.fillMaxWidth().rowPress(onUp, null).minTouchTarget().padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val slot = if (withCovers) look.listCoverSlot() else Modifier.width(32.dp)
        Box(slot.decorative(), contentAlignment = Alignment.Center) { Icon(ListIcons.Up, null, tint = colors.stateFolder) }
        Spacer(Modifier.width(12.dp))
        Text(stringResource(R.string.folder_up), Modifier.weight(1f), color = colors.stateFolder, maxLines = 1)
    }
}

/** docs/spec.md section 4.4: covers are held after the app ended while it made one, until the user asks for them again. */
@Composable
private fun HeldCovers(onResume: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.secondaryContainer).padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.covers_held),
            Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            style = MaterialTheme.typography.bodySmall,
        )
        TextButton(onClick = onResume) { Text(stringResource(R.string.covers_resume)) }
    }
}

/** The cover of a thumbnail list row: as wide as the thumbnail size says, in the chosen box (docs/spec.md section 4.10). */
private fun ListLook.listCoverSlot(): Modifier {
    val width = when (thumbSize) {
        ThumbSize.Small -> 36.dp
        ThumbSize.Medium -> 44.dp
        ThumbSize.Large -> 56.dp
    }
    return Modifier.width(width).aspectRatio(aspect.ratio)
}

/** The fitter of the titles of cells [cellWidthPx] wide, after their padding, on [lines] lines. */
@Composable
private fun rememberTitleFitter(cellWidthPx: Int, lines: Int): TitleFitter {
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.bodySmall.copy(textAlign = TextAlign.Center)
    val widthPx = cellWidthPx - with(LocalDensity.current) { (CELL_PADDING * 2).roundToPx() }
    return remember(measurer, style, widthPx, lines) { TitleFitter(measurer, style, widthPx.coerceAtLeast(1), lines) }
}

/**
 * A grid cell (docs/spec.md section 4.3, グリッドの名前): the cover, the title on the fitter's lines and, with
 * the author line, the note on one more, each kept even when empty so the cells of a row line up. Without the author
 * line the title keeps its brackets. A screen reader and a search by text get the whole name.
 */
@Composable
private fun GridCell(row: FileRow, covers: CoverLayout, focus: CoverFocus, fitter: TitleFitter, look: ListLook, onOpen: (FileRow) -> Unit, onLongPress: (FileRow) -> Unit) {
    val colors = LocalLibraryColors.current
    val name = row.gridName
    val whole = if (look.noteLine) name.title else row.item.gridBase()
    val title = remember(whole, fitter) { fitter.fit(whole) }
    Column(
        Modifier.rowPress({ onOpen(row) }, { onLongPress(row) }).rowState(row).semantics { text = AnnotatedString(row.item.entry.name) }.padding(CELL_PADDING),
    ) {
        Cover(row, covers, focus, Modifier.fillMaxWidth().aspectRatio(look.aspect.ratio))
        Text(
            title,
            Modifier.fillMaxWidth().padding(top = 4.dp).decorative(),
            color = row.nameColor(colors),
            style = fitter.style,
            minLines = fitter.lines,
            maxLines = fitter.lines,
            overflow = TextOverflow.Clip,
        )
        if (look.noteLine) Text(
            name.note,
            Modifier.fillMaxWidth().decorative(),
            color = colors.itemDetail,
            style = fitter.style,
            minLines = 1,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ListRow(row: FileRow, covers: CoverLayout?, focus: CoverFocus, look: ListLook, onOpen: (FileRow) -> Unit, onLongPress: (FileRow) -> Unit) {
    val colors = LocalLibraryColors.current
    Row(
        Modifier.fillMaxWidth().rowPress({ onOpen(row) }, { onLongPress(row) }).rowState(row).minTouchTarget().padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (covers != null) {
            Cover(row, covers, focus, look.listCoverSlot())
        } else {
            Box(Modifier.width(32.dp).decorative(), contentAlignment = Alignment.Center) { MarkGlyph(row.mark) }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(row.item.entry.name, color = row.nameColor(colors), maxLines = 2, overflow = TextOverflow.Ellipsis)
            val pages = row.pageCount?.let { stringResource(R.string.folder_pages, it) }
            val detail = listOfNotNull(row.dateText, row.sizeText, pages).joinToString("  ")
            if (detail.isNotEmpty()) Text(detail, color = colors.itemDetail, style = MaterialTheme.typography.bodySmall, maxLines = 2)
        }
    }
}

/** The thumbnail slot with the kind label and the read-state mark (docs/spec.md sections 4.3 and 4.4). */
@Composable
private fun Cover(row: FileRow, covers: CoverLayout, focus: CoverFocus, modifier: Modifier) {
    val mark = row.mark
    Box(modifier.decorative()) {
        val ref = remember(row.cover, covers.sizePx) { row.cover?.let { CoverRef(it, covers.sizePx) } }
        CoverBox(ref, covers.covers, row.kindBadge(), Modifier.fillMaxSize(), faded = mark == ReadMark.Read, focus = focus)
        Box(Modifier.align(Alignment.TopStart).padding(4.dp)) { MarkGlyph(mark) }
        if (mark is ReadMark.Reading) {
            LinearProgressIndicator(
                progress = { mark.percent / 100f },
                modifier = Modifier.fillMaxWidth().height(4.dp).align(Alignment.BottomCenter),
            )
        }
    }
}

@Composable
private fun List<ListCell>.coverRefs(covers: CoverLayout): List<CoverRef?> =
    remember(this, covers.sizePx) { map { cell -> (cell as? ListCell.Entry)?.row?.cover?.let { CoverRef(it, covers.sizePx) } } }

private fun List<LazyGridItemInfo>.visibleRange(): IntRange {
    val entries = filterNot { (it.key as? String)?.startsWith('/') == true }
    return if (entries.isEmpty()) IntRange.EMPTY else entries.minOf { it.index }..entries.maxOf { it.index }
}

@JvmName("visibleListRange")
private fun List<LazyListItemInfo>.visibleRange(): IntRange {
    val entries = filterNot { (it.key as? String)?.startsWith('/') == true }
    return if (entries.isEmpty()) IntRange.EMPTY else entries.minOf { it.index }..entries.maxOf { it.index }
}

/** Shapes as well as colors tell the states apart: nothing for unread, a percentage, a check. */
@Composable
private fun MarkGlyph(mark: ReadMark?) {
    val colors = LocalLibraryColors.current
    when (mark) {
        null, ReadMark.Unread -> Unit
        is ReadMark.Reading -> Text(
            "${mark.percent}%",
            Modifier.background(MaterialTheme.colorScheme.inverseSurface, RoundedCornerShape(2.dp)).padding(horizontal = 3.dp),
            color = MaterialTheme.colorScheme.inverseOnSurface,
            fontSize = 10.sp,
        )
        ReadMark.Read -> Text("✓", color = colors.stateRead, fontSize = 14.sp)
    }
}

private fun FileRow.kindBadge(): CoverBadge? = when (val item = item) {
    is FileItem.Archive -> CoverBadge.Label(item.kind.label)
    is FileItem.Text -> CoverBadge.Label("TXT")
    is FileItem.Folder -> CoverBadge.Folder
    is FileItem.Image -> null
}

private fun FileRow.nameColor(colors: LibraryColors): Color = when (item) {
    is FileItem.Folder -> colors.stateFolder
    is FileItem.Image -> colors.stateImage
    is FileItem.Archive, is FileItem.Text -> when (mark) {
        is ReadMark.Reading -> colors.stateReading
        ReadMark.Read -> colors.stateRead
        ReadMark.Unread, null -> colors.stateUnread
    }
}

private val FAST_SCROLL_WIDTH = 48.dp
private const val FAST_SCROLL_MIN_ROWS = 30
private const val FAST_SCROLL_HIDE_MS = 1500L
private val THUMB_HEIGHT = 48.dp

/**
 * The fast-scroll thumb over the right edge of the list (docs/spec.md section 4.3, スクロール). It shows while the
 * list moves and for [FAST_SCROLL_HIDE_MS] after, and only the thumb takes touches, so the list keeps its full width
 * and a tap beside the thumb reaches the row under it. Dragging the thumb moves the list to the same share of its rows.
 */
@Composable
private fun FastScroller(rows: Int, first: () -> Int, scrolling: () -> Boolean, scrollTo: (Int) -> Unit, modifier: Modifier) {
    if (rows < FAST_SCROLL_MIN_ROWS) return
    var dragged by remember { mutableFloatStateOf(-1f) }
    var shown by remember { mutableStateOf(false) }
    val active by remember { derivedStateOf { scrolling() || dragged >= 0f } }
    LaunchedEffect(active) {
        if (active) {
            shown = true
        } else {
            delay(FAST_SCROLL_HIDE_MS)
            shown = false
        }
    }
    if (!shown) return
    // A pointer shortcut. A screen reader scrolls the list itself, so the thumb stays out of its way.
    BoxWithConstraints(modifier.width(FAST_SCROLL_WIDTH).fillMaxHeight().decorative()) {
        val density = LocalDensity.current
        val trackPx = constraints.maxHeight.toFloat()
        val thumbPx = with(density) { THUMB_HEIGHT.toPx() }
        val travel = (trackPx - thumbPx).coerceAtLeast(1f)
        val share = if (dragged >= 0f) dragged else first().toFloat() / (rows - 1)
        Box(
            Modifier.align(Alignment.TopCenter)
                .offset { IntOffset(0, (share * travel).roundToInt()) }
                .size(FAST_SCROLL_WIDTH, THUMB_HEIGHT)
                .pointerInput(rows, travel) {
                    detectVerticalDragGestures(
                        onDragStart = { dragged = first().toFloat() / (rows - 1) },
                        onDragEnd = { dragged = -1f },
                        onDragCancel = { dragged = -1f },
                    ) { change, amount ->
                        change.consume()
                        dragged = (dragged + amount / travel).coerceIn(0f, 1f)
                        scrollTo((dragged * (rows - 1)).roundToInt())
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(6.dp, THUMB_HEIGHT).background(MaterialTheme.colorScheme.outline, RoundedCornerShape(3.dp)))
        }
    }
}
