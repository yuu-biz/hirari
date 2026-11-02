package io.github.yuubiz.hirari.feature.reader.prototype

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

private enum class Gesture { TAP, DRAG, PINCH }

/** Prototype A of docs/spec.md section 8.4: `Canvas` draws the views, `graphicsLayer` moves and scales them. */
@Composable
internal fun ComposeSurface(mode: PixelMode) {
    val content = remember { mutableIntStateOf(0) }
    val motion = remember { mutableIntStateOf(0) }
    val pager = remember(mode) {
        val listener = object : Pager.Listener {
            override fun onContentChanged() {
                content.intValue++
            }

            override fun onMotionChanged() {
                motion.intValue++
            }
        }
        Pager(PageStore(mode) { listener.onContentChanged() }, listener)
    }
    DisposableEffect(pager) {
        pager.start()
        onDispose { pager.close() }
    }
    val scope = rememberCoroutineScope()
    val frames = remember { arrayOfNulls<Job>(1) }
    fun animate() {
        if (!pager.animating || frames[0]?.isActive == true) return
        frames[0] = scope.launch {
            while (withFrameNanos { pager.step(it) }) Unit
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(pager) {
                awaitEachGesture {
                    awaitFirstDown()
                    var gesture = Gesture.TAP
                    var dx = 0f
                    var last: PointerInputChange
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.count { it.pressed }
                        if (pressed >= 2) {
                            if (gesture == Gesture.DRAG) pager.cancelDrag()
                            gesture = Gesture.PINCH
                            val centroid = event.calculateCentroid()
                            val pan = event.calculatePan()
                            pager.pinch(
                                event.calculateZoom(), centroid.x, centroid.y, pan.x, pan.y,
                                size.width.toFloat(), size.height.toFloat(),
                            )
                        } else if (gesture != Gesture.PINCH) {
                            dx += event.changes.first().positionChange().x
                            if (gesture == Gesture.TAP && abs(dx) > viewConfiguration.touchSlop) gesture = Gesture.DRAG
                            if (gesture == Gesture.DRAG) pager.drag(dx, size.width.toFloat())
                        }
                        event.changes.forEach { it.consume() }
                        if (event.changes.none { it.pressed }) {
                            last = event.changes.first()
                            break
                        }
                    }
                    when (gesture) {
                        Gesture.TAP -> pager.tap(last.position.x, size.width.toFloat(), last.uptimeMillis)
                        Gesture.DRAG -> pager.release(last.uptimeMillis)
                        Gesture.PINCH -> Unit
                    }
                    animate()
                }
            },
    ) {
        PageLayer(pager, incoming = false, content, motion)
        PageLayer(pager, incoming = true, content, motion)
    }
}

@Composable
private fun PageLayer(pager: Pager, incoming: Boolean, content: MutableIntState, motion: MutableIntState) {
    Canvas(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                motion.intValue
                content.intValue
                val slide = if (incoming) pager.offset * size.width else 0f
                translationX = pager.panX + slide
                translationY = pager.panY
                scaleX = pager.scale
                scaleY = pager.scale
            },
    ) {
        content.intValue
        val index = (if (incoming) pager.incoming else pager.current) ?: return@Canvas
        val bitmap = pager.store[index] ?: return@Canvas
        val fit = minOf(size.width / bitmap.width, size.height / bitmap.height)
        val w = (bitmap.width * fit).toInt()
        val h = (bitmap.height * fit).toInt()
        drawImage(
            bitmap.asImageBitmap(),
            dstOffset = IntOffset(((size.width - w) / 2).toInt(), ((size.height - h) / 2).toInt()),
            dstSize = IntSize(w, h),
            filterQuality = FilterQuality.Low,
        )
        pager.onDrawn(index)
    }
}
