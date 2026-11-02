package io.github.yuubiz.hirari.feature.reader.prototype

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.RenderNode
import android.os.Build
import android.view.Choreographer
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.abs

/** Prototype B of docs/spec.md section 8.4, hosted in the Compose screen like the real reader would be. */
@Composable
internal fun ViewSurface(mode: PixelMode) {
    AndroidView(
        factory = { PrototypeView(it, mode) },
        modifier = Modifier.fillMaxSize(),
        onRelease = { it.close() },
    )
}

/**
 * Draws in `onDraw`. From API 29 each view is recorded once into a `RenderNode`, and moving or scaling
 * only changes the node's properties. API 28 has no public `RenderNode`, so there it transforms the
 * canvas and draws the bitmap on every frame.
 */
@SuppressLint("ViewConstructor")
private class PrototypeView(context: Context, mode: PixelMode) : View(context), Pager.Listener {
    private val pager = Pager(PageStore(mode, ::onContentChanged), this)
    private val nodes = if (Build.VERSION.SDK_INT >= 29) Nodes() else null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dst = RectF()
    private var contentDirty = true
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var dragging = false
    private var pinching = false
    private var lastFocusX = 0f
    private var lastFocusY = 0f

    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (pager.step(frameTimeNanos)) Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                lastFocusX = detector.focusX
                lastFocusY = detector.focusY
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                pager.pinch(
                    detector.scaleFactor, detector.focusX, detector.focusY,
                    detector.focusX - lastFocusX, detector.focusY - lastFocusY,
                    width.toFloat(), height.toFloat(),
                )
                lastFocusX = detector.focusX
                lastFocusY = detector.focusY
                return true
            }
        },
    )

    init {
        pager.start()
    }

    fun close() {
        Choreographer.getInstance().removeFrameCallback(frame)
        pager.close()
    }

    override fun onContentChanged() {
        contentDirty = true
        invalidate()
    }

    override fun onMotionChanged() = invalidate()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        contentDirty = true
    }

    override fun onDraw(canvas: Canvas) {
        if (Build.VERSION.SDK_INT >= 29 && nodes != null && canvas.isHardwareAccelerated) {
            nodes.draw(canvas)
        } else {
            drawDirect(canvas, pager.current, 0f)
            pager.incoming?.let { drawDirect(canvas, it, pager.offset * width) }
        }
    }

    private fun drawDirect(canvas: Canvas, index: Int, slide: Float) {
        val bitmap = pager.store[index] ?: return
        canvas.save()
        canvas.translate(pager.panX + slide, pager.panY)
        canvas.scale(pager.scale, pager.scale, width / 2f, height / 2f)
        canvas.drawBitmap(bitmap, null, fit(bitmap), paint)
        canvas.restore()
        pager.onDrawn(index)
    }

    private fun fit(bitmap: Bitmap): RectF {
        val fit = minOf(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height)
        val w = bitmap.width * fit
        val h = bitmap.height * fit
        dst.set((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
        return dst
    }

    @RequiresApi(29)
    private inner class Nodes {
        private val current = RenderNode("current")
        private val incoming = RenderNode("incoming")

        fun draw(canvas: Canvas) {
            if (contentDirty) {
                record(current, pager.current)
                record(incoming, pager.incoming)
                contentDirty = false
            }
            place(current, 0f)
            place(incoming, pager.offset * width)
            canvas.drawRenderNode(current)
            canvas.drawRenderNode(incoming)
        }

        private fun record(node: RenderNode, index: Int?) {
            node.setPosition(0, 0, width, height)
            val recording = node.beginRecording()
            val bitmap = index?.let { pager.store[it] }
            if (bitmap != null) recording.drawBitmap(bitmap, null, fit(bitmap), paint)
            node.endRecording()
            if (bitmap != null && index != null) pager.onDrawn(index)
        }

        private fun place(node: RenderNode, slide: Float) {
            node.translationX = pager.panX + slide
            node.translationY = pager.panY
            node.pivotX = width / 2f
            node.pivotY = height / 2f
            node.scaleX = pager.scale
            node.scaleY = pager.scale
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                dragging = false
                pinching = false
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (dragging) pager.cancelDrag()
                pinching = true
            }
            MotionEvent.ACTION_MOVE -> if (!pinching) {
                val dx = event.x - downX
                if (!dragging && abs(dx) > slop) dragging = true
                if (dragging) pager.drag(dx, width.toFloat())
            }
            MotionEvent.ACTION_UP -> {
                when {
                    pinching -> Unit
                    dragging -> pager.release(event.eventTime)
                    else -> pager.tap(event.x, width.toFloat(), event.eventTime)
                }
                if (pager.animating) {
                    Choreographer.getInstance().removeFrameCallback(frame)
                    Choreographer.getInstance().postFrameCallback(frame)
                }
            }
            MotionEvent.ACTION_CANCEL -> pager.cancelDrag()
        }
        return true
    }
}
