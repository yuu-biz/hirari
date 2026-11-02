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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.yuubiz.hirari.core.model.PageResult
import io.github.yuubiz.hirari.core.model.PageView
import io.github.yuubiz.hirari.core.model.Point
import io.github.yuubiz.hirari.core.model.Rect
import io.github.yuubiz.hirari.core.model.ScreenOrientation
import io.github.yuubiz.hirari.feature.reader.prefetch.PreparedPage
import kotlin.math.roundToInt

/** The panel background of docs/spec.md section 4.8 (コマンドバー). */
private val PanelBackground = Color(0x80000070)
private val CardBackground = Color(0xFF303030)
private const val PANEL_ROW_DP = 64f

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
        else -> return listOfNotNull(rest?.let { Layer(current, it, 0f, 1f) })
    }
    val out = outgoing.second?.let { Layer(outgoing.first, it, frame.outgoingShift * viewport.width, 1f) }
    val inc = incoming.second?.let { Layer(incoming.first, it, frame.incomingShift * viewport.width, frame.incomingAlpha) }
    return if (frame.incomingOnTop) listOfNotNull(out, inc) else listOfNotNull(inc, out)
}

/** Every reader input and drawing for an open book. */
@Composable
internal fun ReaderSurface(viewModel: ReaderViewModel, state: ReaderState, ui: ReaderUi, onClose: () -> Unit) {
    val density = LocalDensity.current.density
    val haptics = LocalHapticFeedback.current
    var frameMs by remember { mutableLongStateOf(android.os.SystemClock.uptimeMillis()) }
    val latest by rememberUpdatedState(state)
    val close by rememberUpdatedState(onClose)

    LaunchedEffect(state.transition, state.stopTravel) {
        if (state.transition == null && state.stopTravel == null) return@LaunchedEffect
        while (true) {
            val now = withFrameMillis { it }
            frameMs = now
            viewModel.dispatch(ReaderEvent.Tick(now))
        }
    }

    val input = remember(viewModel) {
        ReaderInput(
            viewModel = viewModel,
            ui = ui,
            state = { latest },
            haptic = { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) },
            onClose = { close() },
        )
    }

    val label = pageLabel(state.current, state.pagination.shapes.size)
    Canvas(
        Modifier
            .fillMaxSize()
            .semantics { contentDescription = label }
            .pointerInput(input) { with(input) { handle() } },
    ) {
        viewModel.prepared
        val now = if (state.transition != null || state.stopTravel != null) frameMs else Long.MAX_VALUE
        for (layer in state.layersAt(now)) drawLayer(viewModel, state, layer)
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
        val ready = viewModel.prepared.let { viewModel.preparedView(state.current) != null }
        if (!ready) key(state.current) { DelayedProgress(Modifier.align(Alignment.Center)) }
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
        if (ui.commandBar.panelVisible) CommandPanelView(state.commandPanel(), ui.commandBar)
    }
}

private fun errorOf(viewModel: ReaderViewModel, view: PageView, index: Int, page: Int): PageResult<Nothing>? {
    val prepared = viewModel.preparedView(view)?.pages?.getOrNull(index)
    return (prepared as? PreparedPage.Error)?.result ?: viewModel.failedPages[page]
}

private fun DrawScope.drawLayer(viewModel: ReaderViewModel, state: ReaderState, layer: Layer) {
    val geometry = state.geometry(layer.view) ?: return
    val prepared = viewModel.preparedView(layer.view)
    geometry.pages.forEachIndexed { i, rect ->
        val left = rect.left - layer.position.x + layer.shift
        val top = rect.top - layer.position.y
        when (val page = prepared?.pages?.getOrNull(i)) {
            is PreparedPage.Image<*> -> drawPage(page.image as Bitmap, left, top, rect, layer.alpha)
            is PreparedPage.Error -> drawRect(CardBackground, Offset(left, top), androidx.compose.ui.geometry.Size(rect.width, rect.height), layer.alpha)
            null -> Unit
        }
    }
    if (state.config.gutter == Gutter.Shadow && layer.view is PageView.Two) {
        val (leftPage, rightPage) = geometry.pages.sortedBy { it.left }
        val dx = layer.shift - layer.position.x
        val dy = -layer.position.y
        drawSeamShadow(leftPage.right + dx, leftPage.right + dx - leftPage.width * SHADOW_SHARE, leftPage.top + dy, leftPage.height, layer.alpha)
        drawSeamShadow(rightPage.left + dx, rightPage.left + dx + rightPage.width * SHADOW_SHARE, rightPage.top + dy, rightPage.height, layer.alpha)
    }
}

/** The share of a page's width that the shadow of 綴じ目の表示 covers (docs/spec.md section 4.8, 見開き). */
private const val SHADOW_SHARE = 0.05f
private val SeamShadow = Color(0x59000000)

/** A shadow darkest at [seamX] that fades out toward [fadeX]. */
private fun DrawScope.drawSeamShadow(seamX: Float, fadeX: Float, top: Float, height: Float, alpha: Float) {
    val brush = Brush.horizontalGradient(listOf(SeamShadow, Color.Transparent), startX = seamX, endX = fadeX)
    drawRect(brush, Offset(minOf(seamX, fadeX), top), androidx.compose.ui.geometry.Size(kotlin.math.abs(fadeX - seamX), height), alpha)
}

private fun DrawScope.drawPage(bitmap: Bitmap, left: Float, top: Float, rect: Rect, alpha: Float) {
    if (bitmap.isRecycled) return
    drawImage(
        bitmap.asImageBitmap(),
        dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
        dstSize = IntSize(rect.width.roundToInt().coerceAtLeast(1), rect.height.roundToInt().coerceAtLeast(1)),
        alpha = alpha,
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
private fun CommandPanelView(panel: CommandPanel, state: CommandBarState) {
    val highlight = (state as? CommandBarState.Sliding)?.highlight
    val bounds = panel.bounds
    Box(
        Modifier
            .offset(bounds.left.dp, bounds.top.dp)
            .size(bounds.width.dp, bounds.height.dp)
            .background(PanelBackground),
    ) {
        val cellWidth = bounds.width / panel.columns
        val cellHeight = bounds.height / panel.rows
        val items = buildList {
            add(Triple(0, 0, PanelItem.Close))
            add(Triple(0, panel.columns - 1, PanelItem.Menu))
            panel.commands.forEachIndexed { i, command -> add(Triple(1 + i / panel.columns, i % panel.columns, PanelItem.Command(command))) }
        }
        for ((row, column, item) in items) {
            Box(
                Modifier
                    .offset((column * cellWidth).dp, (row * cellHeight).dp)
                    .size(cellWidth.dp, cellHeight.dp)
                    .background(if (item == highlight) Color(0x66FFFFFF) else Color.Transparent),
                contentAlignment = Alignment.Center,
            ) {
                Text(item.label(), color = Color.White, textAlign = TextAlign.Center, fontSize = 13.sp)
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

/** The panel under the command bar, in dp. */
internal fun ReaderState.commandPanel(): CommandPanel {
    val width = viewport.width / viewport.density
    val columns = if (viewport.orientation == ScreenOrientation.Portrait) 4 else 8
    val rows = 1 + ReaderCommand.MAX_ITEMS / columns
    val top = config.barHeightDp
    return CommandPanel(ReaderCommand.Available, viewport.orientation, Rect(0f, top, width, top + rows * PANEL_ROW_DP))
}
