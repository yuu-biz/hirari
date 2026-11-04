package io.github.yuubiz.hirari.feature.library

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
 * The rows in the chosen view (docs/spec.md section 4.3). Binding a row reads only the row: no query and no
 * decode, so a 1000-entry folder scrolls on what the listing already holds; the covers come from [CoverBox].
 */
@Composable
internal fun FileList(content: FolderContent.Shown, reveal: String?, onOpen: (FileRow) -> Unit, onLongPress: (FileRow) -> Unit) {
    val scope = rememberCoroutineScope()
    var revealed by rememberSaveable { mutableStateOf(reveal == null) }
    suspend fun revealOnce(scrollTo: suspend (Int) -> Unit) {
        if (revealed) return
        revealed = true
        val at = content.rows.indexOfFirst { it.path == reveal }
        if (at >= 0) scrollTo(at)
    }
    val focus = remember { CoverFocus() }
    val held by content.covers.covers.held.collectAsState(initial = false)
    Column(Modifier.fillMaxSize()) {
        if (held && content.view != LibraryView.List) HeldCovers(onResume = content.covers.covers::resume)
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            when (content.view) {
                LibraryView.Grid -> {
                    val grid = rememberLazyGridState()
                    LaunchedEffect(Unit) { revealOnce { grid.scrollToItem(it) } }
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(gridColumns(maxWidth, content.thumbSize)),
                        state = grid,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(content.rows, key = { it.path }) { row -> GridCell(row, content.covers, focus, onOpen, onLongPress) }
                    }
                    PrefetchCovers(content.rows.coverRefs(content.covers), content.covers.covers, focus) { grid.layoutInfo.visibleItemsInfo.visibleRange() }
                    FastScroller(content.rows.size, { grid.firstVisibleItemIndex }, { grid.isScrollInProgress }, { scope.launch { grid.scrollToItem(it) } }, Modifier.align(Alignment.CenterEnd))
                }
                LibraryView.ThumbnailList, LibraryView.List -> {
                    val list = rememberLazyListState()
                    LaunchedEffect(Unit) { revealOnce { list.scrollToItem(it) } }
                    LazyColumn(state = list, modifier = Modifier.fillMaxSize()) {
                        items(content.rows, key = { it.path }) { row ->
                            ListRow(row, content.covers.takeIf { content.view == LibraryView.ThumbnailList }, focus, content.thumbSize, onOpen, onLongPress)
                        }
                    }
                    if (content.view == LibraryView.ThumbnailList) {
                        PrefetchCovers(content.rows.coverRefs(content.covers), content.covers.covers, focus) { list.layoutInfo.visibleItemsInfo.visibleRange() }
                    }
                    FastScroller(content.rows.size, { list.firstVisibleItemIndex }, { list.isScrollInProgress }, { scope.launch { list.scrollToItem(it) } }, Modifier.align(Alignment.CenterEnd))
                }
            }
        }
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

private val ThumbSize.listCoverWidth get() = when (this) {
    ThumbSize.Small -> 36.dp
    ThumbSize.Medium -> 44.dp
    ThumbSize.Large -> 56.dp
}

@Composable
private fun GridCell(row: FileRow, covers: CoverLayout, focus: CoverFocus, onOpen: (FileRow) -> Unit, onLongPress: (FileRow) -> Unit) {
    Column(Modifier.rowPress({ onOpen(row) }, { onLongPress(row) }).rowState(row).padding(6.dp)) {
        Cover(row, covers, focus, Modifier.fillMaxWidth().aspectRatio(COVER_RATIO))
        Text(
            row.item.entry.name,
            Modifier.padding(top = 4.dp),
            color = row.nameColor(LocalLibraryColors.current),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ListRow(row: FileRow, covers: CoverLayout?, focus: CoverFocus, size: ThumbSize, onOpen: (FileRow) -> Unit, onLongPress: (FileRow) -> Unit) {
    val colors = LocalLibraryColors.current
    Row(
        Modifier.fillMaxWidth().rowPress({ onOpen(row) }, { onLongPress(row) }).rowState(row).minTouchTarget().padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (covers != null) {
            Cover(row, covers, focus, Modifier.width(size.listCoverWidth).aspectRatio(COVER_RATIO))
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
        CoverBox(ref, covers.covers, row.kindLabel(), Modifier.fillMaxSize(), faded = mark == ReadMark.Read, focus = focus)
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
private fun List<FileRow>.coverRefs(covers: CoverLayout): List<CoverRef?> =
    remember(this, covers.sizePx) { map { row -> row.cover?.let { CoverRef(it, covers.sizePx) } } }

private fun List<LazyGridItemInfo>.visibleRange(): IntRange = if (isEmpty()) IntRange.EMPTY else first().index..last().index

@JvmName("visibleListRange")
private fun List<LazyListItemInfo>.visibleRange(): IntRange = if (isEmpty()) IntRange.EMPTY else first().index..last().index

/** Shapes as well as colors tell the states apart: a dot, a percentage, a check. */
@Composable
private fun MarkGlyph(mark: ReadMark?) {
    val colors = LocalLibraryColors.current
    when (mark) {
        null -> Unit
        ReadMark.Unread -> Box(Modifier.size(8.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
        is ReadMark.Reading -> Text(
            "${mark.percent}%",
            Modifier.background(MaterialTheme.colorScheme.inverseSurface, RoundedCornerShape(2.dp)).padding(horizontal = 3.dp),
            color = MaterialTheme.colorScheme.inverseOnSurface,
            fontSize = 10.sp,
        )
        ReadMark.Read -> Text("✓", color = colors.stateRead, fontSize = 14.sp)
    }
}

@Composable
private fun FileRow.kindLabel(): String? = when (val item = item) {
    is FileItem.Archive -> item.kind.label
    is FileItem.Text -> "TXT"
    is FileItem.Folder -> stringResource(R.string.folder_label)
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
