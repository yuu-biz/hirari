package io.github.yuubiz.hirari.feature.library

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.yuubiz.hirari.core.ui.GlyphButton
import io.github.yuubiz.hirari.core.ui.announced
import kotlin.math.min
import kotlin.math.roundToInt

/** 範囲を選んで表紙にする (docs/spec.md section 4.4). [onClose] leaves the screen, after a choice and without one. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoverRangeScreen(model: CoverRangeViewModel, onClose: () -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    val save by model.save.collectAsStateWithLifecycle()
    LaunchedEffect(save) {
        if (save == CoverSave.Saved) onClose()
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.cover_range_title), maxLines = 1) },
                navigationIcon = { GlyphButton("←", stringResource(R.string.nav_back), onClose) },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (val content = state) {
                CoverRangeUi.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                is CoverRangeUi.Failed -> Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(content.reason.message()), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (content.reason == CoverRangeFailure.Unavailable) {
                        TextButton(onClick = model::retry) { Text(stringResource(R.string.cover_range_retry)) }
                    }
                }
                is CoverRangeUi.Ready -> RangeEditor(
                    content,
                    save,
                    onChoose = model::choose,
                    onDropRange = model::dropRange,
                    onCancel = onClose,
                )
            }
        }
    }
}

private fun CoverRangeFailure.message(): Int = when (this) {
    CoverRangeFailure.NotFound -> R.string.cover_range_not_found
    CoverRangeFailure.NoImages -> R.string.cover_range_no_images
    CoverRangeFailure.PageMissing -> R.string.cover_range_page_missing
    CoverRangeFailure.Unreadable -> R.string.cover_range_unreadable
    CoverRangeFailure.Unavailable -> R.string.cover_range_unavailable
}

private val FrameSaver = listSaver<CropFrame, Float>(
    save = { listOf(it.left, it.top, it.right, it.bottom) },
    restore = { CropFrame(it[0], it[1], it[2], it[3]) },
)

private val Dim = Color(0x99000000)
private val Edge = Color.White
private val HandleFill = Color.White
private val HandleLine = Color(0xFF202028)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RangeEditor(ready: CoverRangeUi.Ready, save: CoverSave, onChoose: (CropFrame) -> Unit, onDropRange: () -> Unit, onCancel: () -> Unit) {
    val saving = save == CoverSave.Saving
    var frame by rememberSaveable(ready, stateSaver = FrameSaver) { mutableStateOf(ready.frame) }
    val image = remember(ready.page) { ready.page.asImageBitmap() }
    val density = LocalDensity.current
    val grabPx = with(density) { GRAB_DP.dp.toPx() }
    val frameDescription = stringResource(R.string.cover_range_frame)
    val sizeText = stringResource(R.string.cover_range_size, (frame.width * ready.shape.width).roundToInt(), (frame.height * ready.shape.height).roundToInt())
    val stepLabels = FrameStep.entries.associateWith { stringResource(it.label()) }
    Column(Modifier.fillMaxSize().background(Color(0xFF101014))) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(16.dp)) {
            val boxWidth = constraints.maxWidth.toFloat()
            val boxHeight = constraints.maxHeight.toFloat()
            val scale = min(boxWidth / image.width, boxHeight / image.height)
            val shown = Size(image.width * scale, image.height * scale)
            val origin = Offset((boxWidth - shown.width) / 2, (boxHeight - shown.height) / 2)
            Canvas(
                Modifier
                    .fillMaxSize()
                    .semantics {
                        contentDescription = frameDescription
                        stateDescription = sizeText
                        customActions = FrameStep.entries.map { step ->
                            CustomAccessibilityAction(stepLabels.getValue(step)) {
                                frame = frame.stepped(step)
                                true
                            }
                        }
                    }
                    .pointerInput(shown, origin) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val start = frame
                            val grip = start.gripAt(
                                (down.position.x - origin.x) / shown.width,
                                (down.position.y - origin.y) / shown.height,
                                grabPx / shown.width,
                                grabPx / shown.height,
                            ) ?: return@awaitEachGesture
                            down.consume()
                            var total = Offset.Zero
                            drag(down.id) { change ->
                                total += change.position - change.previousPosition
                                frame = start.resized(grip, total.x / shown.width, total.y / shown.height)
                                change.consume()
                            }
                        }
                    },
            ) {
                drawImage(
                    image,
                    dstOffset = IntOffset(origin.x.roundToInt(), origin.y.roundToInt()),
                    dstSize = IntSize(shown.width.roundToInt(), shown.height.roundToInt()),
                )
                drawFrame(frame, origin, shown)
            }
        }
        Column(Modifier.fillMaxWidth().heightIn(max = CONTROLS_MAX_HEIGHT).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(
                sizeText,
                Modifier.announced(),
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(stringResource(R.string.cover_range_hint), color = Color(0xFFB0B0B8), style = MaterialTheme.typography.bodySmall)
            if (save == CoverSave.Failed) {
                Text(stringResource(R.string.cover_range_save_failed), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onCancel) { Text(stringResource(R.string.cover_range_cancel)) }
                TextButton(onClick = { frame = ready.frame }) { Text(stringResource(R.string.cover_range_reset)) }
                TextButton(onClick = { frame = CropFrame.Whole }) { Text(stringResource(R.string.cover_range_whole)) }
                if (ready.ranged) TextButton(onClick = onDropRange, enabled = !saving) { Text(stringResource(R.string.cover_range_drop)) }
                Button(onClick = { onChoose(frame) }, enabled = !saving) { Text(stringResource(R.string.cover_range_confirm)) }
            }
        }
    }
}

/** Dims the page outside [frame], draws the frame and a handle on each corner and each side. [origin] and [shown] place the page. */
private fun DrawScope.drawFrame(frame: CropFrame, origin: Offset, shown: Size) {
    val left = origin.x + frame.left * shown.width
    val top = origin.y + frame.top * shown.height
    val right = origin.x + frame.right * shown.width
    val bottom = origin.y + frame.bottom * shown.height
    drawRect(Dim, Offset(origin.x, origin.y), Size(shown.width, top - origin.y))
    drawRect(Dim, Offset(origin.x, bottom), Size(shown.width, origin.y + shown.height - bottom))
    drawRect(Dim, Offset(origin.x, top), Size(left - origin.x, bottom - top))
    drawRect(Dim, Offset(right, top), Size(origin.x + shown.width - right, bottom - top))
    drawRect(Edge, Offset(left, top), Size(right - left, bottom - top), style = Stroke(2.dp.toPx()))
    val middleX = (left + right) / 2
    val middleY = (top + bottom) / 2
    val radius = HANDLE_DP.dp.toPx()
    for (point in listOf(
        Offset(left, top), Offset(right, top), Offset(left, bottom), Offset(right, bottom),
        Offset(middleX, top), Offset(middleX, bottom), Offset(left, middleY), Offset(right, middleY),
    )) {
        drawCircle(HandleFill, radius, point)
        drawCircle(HandleLine, radius, point, style = Stroke(1.dp.toPx()))
    }
}

private fun FrameStep.label(): Int = when (this) {
    FrameStep.Left -> R.string.cover_range_left
    FrameStep.Right -> R.string.cover_range_right
    FrameStep.Up -> R.string.cover_range_up
    FrameStep.Down -> R.string.cover_range_down
    FrameStep.Larger -> R.string.cover_range_larger
    FrameStep.Smaller -> R.string.cover_range_smaller
}

/** The hint and the buttons scroll past this, so the page keeps its room at a large font. */
private val CONTROLS_MAX_HEIGHT = 240.dp

/** How far from an edge a finger still takes it. */
private const val GRAB_DP = 28

private const val HANDLE_DP = 6
