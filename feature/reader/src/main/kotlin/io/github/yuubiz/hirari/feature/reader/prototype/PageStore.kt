package io.github.yuubiz.hirari.feature.reader.prototype

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.os.Trace
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.random.Random

/** How a prepared view keeps its pixels (Q2 in docs/spec.md section 10). */
internal enum class PixelMode(val key: String) {
    /** `Bitmap.copy(HARDWARE)`: the GPU upload happens while preparing, off the main thread. */
    HARDWARE("hardware"),

    /** Software pixels: the RenderThread uploads them on the first frame that draws the view. */
    ARGB_8888("argb8888"),

    /** Software pixels plus `prepareToDraw`, which asks the RenderThread to upload ahead of the draw. */
    ARGB_8888_PREPARED("argb8888prepared"),
}

internal const val PAGE_WIDTH = 1080
internal const val PAGE_HEIGHT = 2400

/**
 * Prepared views of an endless synthetic book. Keeps the window [focus - 1, focus + 2] prepared, so every
 * forward tap turns to a view that was prepared before the input, as P1 assumes.
 */
internal class PageStore(private val mode: PixelMode, private val onReady: () -> Unit) {
    private val pages = HashMap<Int, Bitmap>()
    private val requested = HashSet<Int>()
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var window = IntRange.EMPTY

    operator fun get(index: Int): Bitmap? = pages[index]

    fun focus(index: Int) {
        window = (index - 1)..(index + 2)
        pages.keys.retainAll { it in window }
        requested.retainAll { it in window }
        for (i in listOf(index, index + 1, index - 1, index + 2)) {
            if (i !in pages && requested.add(i)) worker.execute { prepare(i) }
        }
    }

    fun close() {
        worker.shutdownNow()
        main.removeCallbacksAndMessages(null)
    }

    private fun prepare(index: Int) {
        Trace.beginSection("hirari.prepare")
        val bitmap = try {
            val software = draw(index)
            when (mode) {
                PixelMode.HARDWARE -> software.copy(Bitmap.Config.HARDWARE, false).also { software.recycle() }
                PixelMode.ARGB_8888 -> software
                PixelMode.ARGB_8888_PREPARED -> software.also { it.prepareToDraw() }
            }
        } finally {
            Trace.endSection()
        }
        main.post {
            if (index in window && index in requested) {
                pages[index] = bitmap
                onReady()
            }
        }
    }

    private fun draw(index: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(PAGE_WIDTH, PAGE_HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val hue = ((index % 12 + 12) % 12) * 30f
        canvas.drawColor(Color.HSVToColor(floatArrayOf(hue, 0.12f, 0.98f)))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val random = Random(index)
        repeat(240) {
            paint.color = Color.rgb(random.nextInt(256), random.nextInt(256), random.nextInt(256))
            val x = random.nextFloat() * PAGE_WIDTH
            val y = random.nextFloat() * PAGE_HEIGHT
            canvas.drawCircle(x, y, 10f + random.nextFloat() * 90f, paint)
        }
        paint.color = Color.BLACK
        paint.textSize = 360f
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText(index.toString(), PAGE_WIDTH / 2f, PAGE_HEIGHT / 2f, paint)
        return bitmap
    }
}
