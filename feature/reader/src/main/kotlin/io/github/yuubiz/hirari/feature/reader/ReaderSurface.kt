package io.github.yuubiz.hirari.feature.reader

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.text.TextAutoSize
import io.github.yuubiz.hirari.core.ui.decorative
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import io.github.yuubiz.hirari.core.model.BookEndOutcome
import io.github.yuubiz.hirari.core.model.CommandBarState
import io.github.yuubiz.hirari.core.model.PageBarState
import io.github.yuubiz.hirari.core.model.bookEndOutcome
import io.github.yuubiz.hirari.core.model.pullFrame
import io.github.yuubiz.hirari.core.model.turnMovement
import kotlinx.coroutines.delay
import io.github.yuubiz.hirari.core.image.PixelSize
import io.github.yuubiz.hirari.core.model.PageResult
import io.github.yuubiz.hirari.core.model.PageShape
import io.github.yuubiz.hirari.core.model.PageView
import io.github.yuubiz.hirari.core.model.Point
import io.github.yuubiz.hirari.core.model.ReaderCommand
import io.github.yuubiz.hirari.core.model.Rect
import io.github.yuubiz.hirari.core.ui.SEAM_SHADOW_SHARE
import io.github.yuubiz.hirari.core.ui.drawSeamShadow
import io.github.yuubiz.hirari.core.ui.performTurnHaptic
import io.github.yuubiz.hirari.core.ui.readerBackgroundColor
import io.github.yuubiz.hirari.feature.reader.prefetch.PreparedPage
import kotlin.math.roundToInt
import androidx.compose.ui.unit.dp
import io.github.yuubiz.hirari.core.ui.PageBarGestureExclusion
import io.github.yuubiz.hirari.core.ui.systemGestureEdges

/** The panel background of docs/spec.md section 4.8 (コマンドバー). */
private val PanelBackground = Color(0x80000070)
private val CardBackground = Color(0xFF303030)

/** A view drawn at [position], shifted by [shift] pixels, for one frame. */
private data class Layer(val view: PageView, val position: Point, val shift: Float, val alpha: Float)

/** The views to draw at [nowMs], bottom first: one at rest, two during a turn, a return, or a pull. */
private fun ReaderState.layersAt(nowMs: Long): List<Layer> {
    fun at(view: PageView, place: Place): Point? = geometry(view)?.let { positionOf(it, place) }
    val rest = positionAt(nowMs)
    val (frame, outgoing, incoming) = when {
        pull?.neighbor != null -> {
            val pull = pull
            val frame = pullFrame(config.turnEffect, turnMovement(pull.turn, config.direction), pull.pulledDp * viewport.density / viewport.width)
            Triple(frame, current to rest, pull.neighbor!! to at(pull.neighbor, Place.Entered(pull.turn)))
        }
        transition != null -> {
            val t = transition
            val incomingAt = if (t.incoming == current) rest else at(t.incoming, t.incomingPlace)
            Triple(t.animation.frameAt(nowMs), t.outgoing to at(t.outgoing, t.outgoingPlace), t.incoming to incomingAt)
        }
        else -> return listOfNotNull(rest?.let { Layer(current, it, bounceAt(nowMs), 1f) })
    }
    val out = outgoing.second?.let { Layer(outgoing.first, it, frame.outgoingShift * viewport.width, 1f) }
    val inc = incoming.second?.let { Layer(incoming.first, it, frame.incomingShift * viewport.width, frame.incomingAlpha) }
    return if (frame.incomingOnTop) listOfNotNull(out, inc) else listOfNotNull(inc, out)
}

/** The bounce of 止まる at [nowMs], in pixels, and nothing for the other 本の端での動作. */
private fun ReaderState.bounceAt(nowMs: Long): Float {
    val hit = edgeHit?.takeIf { bookEndOutcome(it.edge, config.atBookEnd, autoPlaying = false) == BookEndOutcome.Bounce } ?: return 0f
    return hit.bounceDpAt(nowMs, config.direction) * viewport.density
}

/** Every reader input and drawing for an open book. */
@Composable
internal fun ReaderSurface(viewModel: ReaderViewModel, state: ReaderState, ui: ReaderUi, onClose: () -> Unit) {
    val density = LocalDensity.current.density
    val haptics = LocalHapticFeedback.current
    var frameMs by remember { mutableLongStateOf(android.os.SystemClock.uptimeMillis()) }
    val latest by rememberUpdatedState(state)
    val close by rememberUpdatedState(onClose)
    val view = LocalView.current
    LaunchedEffect(viewModel, view) {
        for (tick in viewModel.turnHaptics) view.performTurnHaptic()
    }

    LaunchedEffect(state.transition, state.stopTravel, state.loupe?.move, state.edgeHit, state.scroll?.motion, (state.place as? Place.Gliding)?.inertia) {
        if (!state.animating) return@LaunchedEffect
        while (true) {
            val now = withFrameMillis { it }
            frameMs = now
            viewModel.dispatch(ReaderEvent.Tick(now))
        }
    }

    val edges by rememberUpdatedState(systemGestureEdges())
    val input = remember(viewModel) {
        ReaderInput(
            viewModel = viewModel,
            ui = ui,
            state = { latest },
            edges = { edges },
            haptic = { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) },
            holdHaptic = { haptics.performHapticFeedback(HapticFeedbackType.LongPress) },
            onClose = { close() },
        )
    }

    val background = readerBackgroundColor(viewModel.background)
    val adjust = state.config.adjust
    val colorFilter = remember(adjust) { adjust.colorMatrix()?.let { ColorFilter.colorMatrix(ColorMatrix(it)) } }
    val control = { control: ReaderControl -> control.effectIn(latest).applyTo(viewModel, ui) }
    Canvas(
        Modifier
            .fillMaxSize()
            .pageAccessibility(state, control)
            .pointerInput(input) { with(input) { handle() } },
    ) {
        viewModel.prepared
        viewModel.tilesDrawn
        val now = if (state.animating) frameMs else Long.MAX_VALUE
        if (state.scroll != null) drawStrip(viewModel, state, colorFilter) else for (layer in state.layersAt(now)) drawLayer(viewModel, state, layer, colorFilter)
        viewModel.traces.drawn(state.current, viewModel.preparedView(state.current) != null)
        state.loupeView(now)?.let { drawLoupe(viewModel, state, it, now, colorFilter, background) }
    }

    PageBarGestureExclusion(state.config.barHeightDp)

    for ((page, rect) in state.stripPages()) {
        val result = errorOf(viewModel, PageView.One(page), 0, page) ?: continue
        key(page) {
            ErrorCard(
                result = result,
                name = viewModel.pageNames.getOrNull(page).orEmpty(),
                modifier = Modifier
                    .offset { IntOffset(rect.left.roundToInt(), rect.top.roundToInt()) }
                    .requiredSize(with(LocalDensity.current) { rect.width.toDp() }, with(LocalDensity.current) { rect.height.toDp() }),
                onRetry = { viewModel.retry(page) },
            )
        }
    }

    val rest = if (state.transition == null && state.pull == null) state.positionAt(Long.MAX_VALUE) else null
    val geometry = state.geometry()
    if (rest != null && geometry != null) {
        state.current.pages.forEachIndexed { i, page ->
            val result = errorOf(viewModel, state.current, i, page) ?: return@forEachIndexed
            val rect = geometry.pages[i]
            key(page) {
                ErrorCard(
                    result = result,
                    name = viewModel.pageNames.getOrNull(page).orEmpty(),
                    modifier = Modifier
                        .offset { IntOffset((rect.left - rest.x).roundToInt(), (rect.top - rest.y).roundToInt()) }
                        .requiredSize(with(LocalDensity.current) { rect.width.toDp() }, with(LocalDensity.current) { rect.height.toDp() }),
                    onRetry = { viewModel.retry(page) },
                )
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        BarAccessibility(state.config.barHeightDp, control)
        val ready = viewModel.prepared.let { viewModel.preparedView(state.current) != null }
        if (!ready) {
            key(state.current) {
                DelayedProgress(Modifier.align(Alignment.Center))
                if (viewModel.fetchedBytes != null) FetchStatus(viewModel, state.current, Modifier.align(Alignment.Center).padding(top = 96.dp))
            }
        }
        if (state.connection.reconnecting) {
            OutlinedText(stringResource(R.string.reader_reconnecting), Modifier.align(Alignment.TopCenter).padding(top = 24.dp), size = 16)
        }
        state.zoom.pinch?.takeIf { it.active }?.let {
            OutlinedText("${state.zoom.percent}%", Modifier.align(Alignment.Center), size = 28)
        }
        (ui.pageBar as? PageBarState.Dragging)?.let { drag ->
            val name = viewModel.pageNames.getOrNull(drag.preview).orEmpty()
            val color = if (drag.cancelled) Color.Gray else Color.White
            Column(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .background(Color(0xCC000000))
                    .padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("${drag.preview + 1} / ${state.pagination.shapes.size}", color = color, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                Text(viewModel.bookPath, color = color, maxLines = 1, fontSize = 13.sp)
                Text(name, color = color, maxLines = 1)
            }
            Thumbnail(
                viewModel,
                drag.preview,
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = (state.config.barHeightDp + 8f).dp)
                    .alpha(if (drag.cancelled) 0.4f else 1f),
            )
        }
        if (ui.commandBar.panelVisible) {
            CommandPanelView(state.commandPanel(), ui.commandBar) { item ->
                ui.commandBar = CommandBarState.Closed
                runPanelItem(item, viewModel, ui, close)
            }
        }
    }
}

/** Whether a frame moves something, so the frames tick. */
private val ReaderState.animating: Boolean
    get() = transition != null || stopTravel != null || loupe?.move != null || edgeHit != null || scroll?.motion != null || place is Place.Gliding

/**
 * 取得の状況 under the progress indicator for a book on a server (docs/spec.md section 4.8): where the view's data
 * comes from and how far its fetch has come, sampled every [FETCH_SAMPLE_MS] once the indicator shows.
 */
@Composable
private fun FetchStatus(viewModel: ReaderViewModel, view: PageView, modifier: Modifier) {
    var progress by remember { mutableStateOf<FetchProgress?>(null) }
    LaunchedEffect(view) {
        delay(PROGRESS_DELAY_MS)
        val start = FetchSample(android.os.SystemClock.uptimeMillis(), viewModel.fetchedBytes ?: return@LaunchedEffect)
        val need = viewModel.fetchNeed(view)
        var previous = start
        while (true) {
            val now = FetchSample(android.os.SystemClock.uptimeMillis(), viewModel.fetchedBytes ?: return@LaunchedEffect)
            progress = fetchProgress(start, previous, now, need)
            previous = now
            delay(FETCH_SAMPLE_MS)
        }
    }
    val shown = progress ?: return
    val text = when (shown.source) {
        FetchSource.Memory -> stringResource(R.string.fetch_memory)
        FetchSource.Cache -> stringResource(R.string.fetch_cache)
        FetchSource.Server -> stringResource(R.string.fetch_server, shown.percent, shown.bytesPerSecond / 1024)
    }
    OutlinedText(text, modifier, size = 16)
}

private const val FETCH_SAMPLE_MS = 500L

private fun errorOf(viewModel: ReaderViewModel, view: PageView, index: Int, page: Int): PageResult<Nothing>? {
    val prepared = viewModel.preparedView(view)?.pages?.getOrNull(index)
    return (prepared as? PreparedPage.Error)?.result ?: viewModel.failedPages[page]
}

/**
 * The loupe of [view]: the current view at rest, scaled about the finger into the loupe's frame, with the page read
 * at its own size in place of its prepared pixels once that has arrived.
 */
private fun DrawScope.drawLoupe(viewModel: ReaderViewModel, state: ReaderState, view: LoupeView, nowMs: Long, colorFilter: ColorFilter?, background: Color) {
    val position = state.positionAt(nowMs)
    if (position == null && state.scroll == null) return
    val rect = view.rect
    val topLeft = Offset(rect.left, rect.top)
    val size = androidx.compose.ui.geometry.Size(rect.width, rect.height)
    val m = view.magnification
    val detail = viewModel.loupeDetail?.takeIf { it.source.view == state.current || state.scroll != null }
    drawRect(background, topLeft, size)
    clipRect(rect.left, rect.top, rect.right, rect.bottom) {
        withTransform({
            translate(view.center.x - view.finger.x * m, view.center.y - view.finger.y * m)
            scale(m, m, pivot = Offset.Zero)
        }) {
            if (position == null) drawStrip(viewModel, state, colorFilter, detail) else drawLayer(viewModel, state, Layer(state.current, position, 0f, 1f), colorFilter, detail)
        }
    }
    drawRect(LoupeEdge, topLeft, size, style = Stroke(width = LOUPE_EDGE_DP * density))
}

private val LoupeEdge = Color(0xCCFFFFFF)
private const val LOUPE_EDGE_DP = 2f

private fun DrawScope.drawLayer(viewModel: ReaderViewModel, state: ReaderState, layer: Layer, colorFilter: ColorFilter?, detail: LoupeDetail? = null) {
    val geometry = state.geometry(layer.view) ?: return
    val prepared = viewModel.preparedView(layer.view)
    val wanted = state.shownParts(layer.view)
    geometry.pages.forEachIndexed { i, rect ->
        val left = rect.left - layer.position.x + layer.shift
        val top = rect.top - layer.position.y
        val sharper = detail?.takeIf { it.source.index == i }
        when (val page = prepared?.pages?.getOrNull(i)) {
            is PreparedPage.Image<*> -> {
                val bitmap = sharper?.bitmap ?: page.image as Bitmap
                val covered = sharper?.source?.steps?.part ?: prepared.target.steps[i].part
                val shown = Rect(left, top, left + rect.width, top + rect.height)
                drawPage(bitmap, wanted[i], covered, shown, layer.alpha, colorFilter)
            }
            is PreparedPage.Error -> drawRect(CardBackground, Offset(left, top), androidx.compose.ui.geometry.Size(rect.width, rect.height), layer.alpha)
            null -> Unit
        }
    }
    if (state.config.gutter == Gutter.Shadow && layer.view is PageView.Two) {
        val (leftPage, rightPage) = geometry.pages.sortedBy { it.left }
        val dx = layer.shift - layer.position.x
        val dy = -layer.position.y
        drawSeamShadow(leftPage.right + dx, leftPage.right + dx - leftPage.width * SEAM_SHADOW_SHARE, leftPage.top + dy, leftPage.height, layer.alpha)
        drawSeamShadow(rightPage.left + dx, rightPage.left + dx + rightPage.width * SEAM_SHADOW_SHARE, rightPage.top + dy, rightPage.height, layer.alpha)
    }
}

/**
 * The pages of 縦に連続 on screen, each from its prepared view, with [detail] in place of the page the loupe read at
 * the size of its own image. A page not prepared yet leaves the background, and a page that failed its card's.
 */
private fun DrawScope.drawStrip(viewModel: ReaderViewModel, state: ReaderState, colorFilter: ColorFilter?, detail: LoupeDetail? = null) {
    for ((page, rect) in state.stripPages()) {
        val view = PageView.One(page)
        val prepared = viewModel.preparedView(view)
        when (val shown = prepared?.pages?.single()) {
            is PreparedPage.Image<*> -> {
                val sharper = detail?.takeIf { it.source.view == view }
                drawPage(sharper?.bitmap ?: shown.image as Bitmap, PagePart.Whole, prepared.target.steps.single().part, rect, 1f, colorFilter)
            }
            is PreparedPage.Error -> drawRect(CardBackground, Offset(rect.left, rect.top), androidx.compose.ui.geometry.Size(rect.width, rect.height))
            null -> Unit
        }
        val shape = state.pagination.shapes[page] as? PageShape.Known ?: continue
        if (detail?.source?.view != view) drawTiles(viewModel.drawnTiles(page), rect, rect.width / shape.width, colorFilter)
    }
}

/**
 * [tiles] of a page drawn at [rect] with [scale] screen pixels for each pixel of its image. Each edge is rounded from
 * the image's own position, so neighboring tiles meet without a seam.
 */
private fun DrawScope.drawTiles(tiles: List<DrawnTile>, rect: Rect, scale: Float, colorFilter: ColorFilter?) {
    fun x(at: Int) = (rect.left + at * scale).roundToInt()
    fun y(at: Int) = (rect.top + at * scale).roundToInt()
    for (tile in tiles) {
        if (tile.bitmap.isRecycled) continue
        val left = x(tile.rect.left)
        val top = y(tile.rect.top)
        drawImage(
            tile.bitmap.asImageBitmap(),
            dstOffset = IntOffset(left, top),
            dstSize = IntSize((x(tile.rect.right) - left).coerceAtLeast(1), (y(tile.rect.bottom) - top).coerceAtLeast(1)),
            colorFilter = colorFilter,
            filterQuality = FilterQuality.Low,
        )
    }
}

/**
 * [bitmap], which shows [covered] of its page, into [rect], which shows [wanted]. A page prepared for another trim
 * keeps drawing at the right scale until its replacement is ready.
 */
private fun DrawScope.drawPage(bitmap: Bitmap, wanted: PagePart, covered: PagePart, rect: Rect, alpha: Float, colorFilter: ColorFilter?) {
    if (bitmap.isRecycled) return
    val draw = pageDraw(wanted, covered, PixelSize(bitmap.width, bitmap.height), rect) ?: return
    val source = draw.source
    val destination = draw.destination
    drawImage(
        bitmap.asImageBitmap(),
        srcOffset = IntOffset(source.left, source.top),
        srcSize = IntSize(source.width, source.height),
        dstOffset = IntOffset(destination.left.roundToInt(), destination.top.roundToInt()),
        dstSize = IntSize(destination.width.roundToInt().coerceAtLeast(1), destination.height.roundToInt().coerceAtLeast(1)),
        alpha = alpha,
        colorFilter = colorFilter,
        filterQuality = FilterQuality.Low,
    )
}

/** The error card of docs/spec.md section 4.8 (ページのエラー表示), in the page's place. */
@Composable
private fun ErrorCard(result: PageResult<Nothing>, name: String, modifier: Modifier, onRetry: () -> Unit) {
    val card = pageErrorCard(result) ?: return
    var details by remember { mutableStateOf(false) }
    Column(
        modifier.background(CardBackground).padding(16.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(card.text.message(), color = Color.White, textAlign = TextAlign.Center, style = MaterialTheme.typography.titleMedium)
        Text(name, color = Color.LightGray, textAlign = TextAlign.Center, maxLines = 2, modifier = Modifier.padding(top = 8.dp))
        Row(Modifier.padding(top = 8.dp)) {
            for (action in card.actions) {
                when (action) {
                    PageErrorAction.Retry -> TextButton(onClick = onRetry) { Text(stringResource(R.string.page_error_retry)) }
                    PageErrorAction.Details -> TextButton(onClick = { details = !details }) { Text(stringResource(R.string.page_error_details)) }
                }
            }
        }
        if (details) {
            SelectionContainer {
                Text("$name\n$result", color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun PageErrorText.message(): String = when (this) {
    PageErrorText.Corrupt -> stringResource(R.string.page_error_corrupt)
    PageErrorText.ReadFailed -> stringResource(R.string.page_error_read)
    PageErrorText.TimedOut -> stringResource(R.string.page_error_timeout)
    PageErrorText.Encrypted -> stringResource(R.string.page_error_encrypted)
    is PageErrorText.Compression -> stringResource(R.string.page_error_compression, method)
    is PageErrorText.ImageFeature -> stringResource(R.string.page_error_image_feature, detail)
    is PageErrorText.TooLarge ->
        if (width == 0 || height == 0) stringResource(R.string.page_error_too_large) else stringResource(R.string.page_error_too_large_size, width, height)
    PageErrorText.NotAnImage -> stringResource(R.string.page_error_not_an_image)
}

/** White text with a black outline, readable over light and dark pages (docs/spec.md section 4.8). */
@Composable
private fun OutlinedText(text: String, modifier: Modifier, size: Int) {
    Box(modifier) {
        Text(
            text,
            Modifier.decorative(),
            color = Color.Black,
            fontSize = size.sp,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.bodyLarge.copy(
                drawStyle = androidx.compose.ui.graphics.drawscope.Stroke(width = 6f),
            ),
        )
        Text(text, color = Color.White, fontSize = size.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun CommandPanelView(panel: CommandPanel, state: CommandBarState, onItem: (PanelItem) -> Unit) {
    val highlight = (state as? CommandBarState.Sliding)?.highlight
    val bounds = panel.bounds
    val title = stringResource(R.string.guide_command_bar)
    Box(
        Modifier
            .offset(bounds.left.dp, bounds.top.dp)
            .size(bounds.width.dp, bounds.height.dp)
            .background(PanelBackground)
            .semantics {
                paneTitle = title
                isTraversalGroup = true
                traversalIndex = -1f
            },
    ) {
        val cellWidth = bounds.width / panel.columns
        val cellHeight = bounds.height / panel.rows
        val items = buildList {
            add(Triple(0, 0, PanelItem.Close))
            add(Triple(0, panel.columns - 1, PanelItem.Menu))
            panel.commands.forEachIndexed { i, command -> add(Triple(1 + i / panel.columns, i % panel.columns, PanelItem.Command(command))) }
        }
        for ((index, cell) in items.withIndex()) {
            val (row, column, item) = cell
            Box(
                Modifier
                    .offset((column * cellWidth).dp, (row * cellHeight).dp)
                    .size(cellWidth.dp, cellHeight.dp)
                    .background(if (item == highlight) Color(0x66FFFFFF) else Color.Transparent)
                    .semantics(mergeDescendants = true) {
                        role = Role.Button
                        traversalIndex = index.toFloat()
                        onClick {
                            onItem(item)
                            true
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                // A 4 dp margin keeps a long name inside the cell; at a large font it shrinks to fit instead of clipping.
                Text(
                    item.label(),
                    Modifier.padding(horizontal = 4.dp),
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    fontSize = 13.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    autoSize = TextAutoSize.StepBased(minFontSize = 8.sp, maxFontSize = 13.sp),
                )
            }
        }
    }
}

@Composable
private fun PanelItem.label(): String = when (this) {
    PanelItem.Close -> stringResource(R.string.panel_close)
    PanelItem.Menu -> stringResource(R.string.panel_menu)
    is PanelItem.Command -> stringResource(command.label)
}

internal val ReaderCommand.label: Int
    get() = when (this) {
        ReaderCommand.Layout -> R.string.command_layout
        ReaderCommand.ShiftPairing -> R.string.command_shift_pairing
        ReaderCommand.ScaleMode -> R.string.command_scale_mode
        ReaderCommand.Trim -> R.string.command_trim
        ReaderCommand.Direction -> R.string.command_direction
        ReaderCommand.Rotate -> R.string.command_rotate
        ReaderCommand.Orientation -> R.string.command_orientation
        ReaderCommand.AddBookmark -> R.string.command_add_bookmark
        ReaderCommand.Bookmarks -> R.string.command_bookmarks
        ReaderCommand.AutoPlay -> R.string.command_auto_play
        ReaderCommand.ReverseOrder -> R.string.command_reverse_order
        ReaderCommand.ImageAdjust -> R.string.command_image_adjust
        ReaderCommand.MirrorControls -> R.string.command_mirror_controls
        ReaderCommand.StopOrder -> R.string.command_stop_order
        ReaderCommand.Gutter -> R.string.command_gutter
        ReaderCommand.EditCommandBar -> R.string.command_edit_command_bar
        ReaderCommand.Breath -> R.string.command_breath
        ReaderCommand.BookSettings -> R.string.command_book_settings
        ReaderCommand.SetCover -> R.string.command_set_cover
        ReaderCommand.SharePage -> R.string.command_share_page
        ReaderCommand.Settings -> R.string.command_settings
    }
