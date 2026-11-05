package io.github.yuubiz.hirari.feature.reader

import android.graphics.Bitmap
import io.github.yuubiz.hirari.core.image.PixelRect
import io.github.yuubiz.hirari.core.image.PixelSize
import io.github.yuubiz.hirari.core.image.TileKey
import io.github.yuubiz.hirari.core.image.TilePlan
import io.github.yuubiz.hirari.core.image.TileSource
import io.github.yuubiz.hirari.core.image.isElongated
import io.github.yuubiz.hirari.core.image.tileRect
import io.github.yuubiz.hirari.core.model.PageResult
import io.github.yuubiz.hirari.core.model.PageShape
import io.github.yuubiz.hirari.core.model.Rect
import io.github.yuubiz.hirari.core.model.Rotation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * The part of a page's image on screen, in the pixels of its [image], how many screen pixels show one of them, and
 * whether シャープ is on the tiles.
 */
data class TileWindow(val shown: PixelRect, val scale: Double, val image: PixelSize, val sharpen: Boolean = false) {
    /** [shown] in the pixels of an image of [size] that holds the same page, as the tiles of a PDF page are planned on. */
    fun inPixelsOf(size: PixelSize): PixelRect {
        if (size == image) return shown
        val x = size.width.toDouble() / image.width
        val y = size.height.toDouble() / image.height
        return PixelRect(
            floor(shown.left * x).toInt(),
            floor(shown.top * y).toInt(),
            ceil(shown.right * x).toInt(),
            ceil(shown.bottom * y).toInt(),
        ).clippedTo(PixelRect(0, 0, size.width, size.height))
    }
}

/**
 * The pages on screen that show as tiles over their prepared pages (docs/spec.md sections 3.7 and 4.8), and the part
 * of each on screen. In 縦に連続 they are the pages narrower than 1:3, as the book stores them, and while a zoom past
 * the width of the screen has settled, every page it shows sharper than its prepared page. In the other layouts they
 * are the pages of the current view at rest when the settled zoom reads them as [ZoomRead.Tiles]. A turned page
 * keeps its prepared bitmap, since tiles come in the image's own orientation.
 */
fun ReaderState.tileWindows(): Map<Int, TileWindow> {
    if (config.rotation != Rotation.None) return emptyMap()
    return if (scroll != null) stripWindows() else zoomWindows()
}

private fun ReaderState.stripWindows(): Map<Int, TileWindow> {
    val zoomed = zoom.settledFactor > 1f
    return stripPages().mapNotNull { (page, rect) ->
        val shape = pagination.shapes[page] as? PageShape.Known ?: return@mapNotNull null
        val tiled = isElongated(shape.width, shape.height) || zoomed && zoomedPastPrepared(shape, rect.width)
        if (tiled) window(shape, rect, rect)?.let { page to it } else null
    }.toMap()
}

private fun ReaderState.zoomWindows(): Map<Int, TileWindow> {
    if (zoomRead != ZoomRead.Tiles || transition != null || pull != null) return emptyMap()
    val geometry = geometry() ?: return emptyMap()
    val at = positionAt(Long.MAX_VALUE) ?: return emptyMap()
    val parts = shownParts(current)
    return current.pages.indices.mapNotNull { i ->
        val page = current.pages[i]
        val shape = pagination.shapes[page] as? PageShape.Known ?: return@mapNotNull null
        val laid = geometry.pages[i]
        val shown = Rect(laid.left - at.x, laid.top - at.y, laid.right - at.x, laid.bottom - at.y)
        window(shape, parts[i].wholeAt(shown), shown)?.let { page to it }
    }.toMap()
}

/** What the screen shows of [shown], part of a page of [shape] whose whole image lies at [whole], in screen pixels. */
private fun ReaderState.window(shape: PageShape.Known, whole: Rect, shown: Rect): TileWindow? {
    val scaleX = whole.width.toDouble() / shape.width
    val scaleY = whole.height.toDouble() / shape.height
    val pixels = PixelRect(
        floor((max(shown.left, 0f) - whole.left) / scaleX).toInt().coerceIn(0, shape.width),
        floor((max(shown.top, 0f) - whole.top) / scaleY).toInt().coerceIn(0, shape.height),
        ceil((min(shown.right, viewport.width) - whole.left) / scaleX).toInt().coerceIn(0, shape.width),
        ceil((min(shown.bottom, viewport.height) - whole.top) / scaleY).toInt().coerceIn(0, shape.height),
    )
    if (pixels.isEmpty) return null
    return TileWindow(pixels, max(scaleX, scaleY), PixelSize(shape.width, shape.height), config.adjust.sharpen)
}

/** A tile ready to draw: its [bitmap] covers [rect] of an image of [of] pixels, the page's own or finer. */
class DrawnTile(val rect: PixelRect, val bitmap: Bitmap, val of: PixelSize)

/** What reading a page for its tiles gave. */
internal sealed interface TileOpening {
    class Tiled(val source: TileSource) : TileOpening

    /** The page has no tiles, or cannot have them, so its prepared page alone shows. */
    data object Whole : TileOpening

    /**
     * The page would have tiles, but not within the share of the budget they may take (docs/spec.md section 3.7), so a
     * zoom reads it whole as large as fits instead.
     */
    data object Untiled : TileOpening

    /** The read failed in a way that may pass, as a lost connection does. */
    data object Failed : TileOpening
}

/**
 * The tiles of the pages [tileWindows] names, read over their prepared pages while they are on screen: the long pages
 * of 縦に連続, and the pages of a settled zoom (docs/spec.md section 4.8). Each page
 * gets its [TileSource] once it shows; a page that leaves the screen closes it. A page whose read [TileOpening.Failed]
 * is read again when it comes back on screen and on [retry]. Tiles are never recycled here, as section 3.7 asks,
 * since a frame may still draw one. Everything runs in [scope], on the main thread; [open] and the tile reads move
 * their work off it, and [close] closes a source off it.
 */
internal class PageTiles(
    private val scope: CoroutineScope,
    private val open: suspend (page: Int) -> TileOpening,
    private val close: (TileSource) -> Unit,
    private val changed: () -> Unit,
    private val untiled: (page: Int) -> Unit = {},
) {
    private class Held {
        var source: TileSource? = null
        var opening: Job? = null
        var failed = false
        var plan: TilePlan? = null
        var sharpen = false
        val tiles = HashMap<TileKey, Bitmap>()
        val reading = HashMap<TileKey, Job>()
    }

    private val held = HashMap<Int, Held>()

    /** Pages that came back whole, so they are not asked for tiles again. */
    private val whole = HashSet<Int>()

    private var windows: Map<Int, TileWindow> = emptyMap()

    /** Two fingers are changing the zoom, so what was being read for the last one stopped. */
    private var holding = false

    /**
     * Stops every read for the zoom that was settled, and uses none of their results, while a pinch changes the zoom;
     * the tiles already read stay, stretched (docs/spec.md section 4.8, ピンチと拡大率). The next [update] reads for
     * the zoom that settles.
     */
    fun hold() {
        if (holding) return
        holding = true
        for (entry in held.values) {
            entry.opening?.cancel()
            entry.opening = null
            entry.reading.values.forEach { it.cancel() }
            entry.reading.clear()
        }
    }

    fun update(next: Map<Int, TileWindow>) {
        if (next == windows && !holding) return
        holding = false
        windows = next
        for (page in held.keys - next.keys) drop(page)
        fill()
    }

    /** Reads again what failed of the pages on screen: their tiles, and the pages whose read failed. */
    fun retry() {
        held.values.forEach { it.failed = false }
        fill()
    }

    private fun fill() {
        for ((page, window) in windows) {
            if (page in whole) continue
            val entry = held.getOrPut(page, ::Held)
            val source = entry.source
            if (source == null) {
                if (entry.opening == null && !entry.failed) entry.opening = scope.launch { opened(page, open(page)) }
            } else {
                plan(entry, source, window)
            }
        }
    }

    /** The tiles of [page] that are read, of the plan for what the screen shows of it. */
    fun drawn(page: Int): List<DrawnTile> {
        val entry = held[page] ?: return emptyList()
        val source = entry.source ?: return emptyList()
        val plan = entry.plan ?: return emptyList()
        return plan.all.mapNotNull { key -> entry.tiles[key]?.let { DrawnTile(tileRect(key, source.size), it, source.size) } }
    }

    fun clear() {
        held.keys.toList().forEach(::drop)
        windows = emptyMap()
    }

    private fun opened(page: Int, opening: TileOpening) {
        val entry = held[page]
        if (entry == null) {
            if (opening is TileOpening.Tiled) close(opening.source)
            return
        }
        entry.opening = null
        when (opening) {
            is TileOpening.Tiled -> {
                entry.source = opening.source
                windows[page]?.let { plan(entry, opening.source, it) }
            }
            TileOpening.Whole -> {
                whole += page
                held.remove(page)
            }
            TileOpening.Untiled -> {
                whole += page
                held.remove(page)
                untiled(page)
            }
            TileOpening.Failed -> entry.failed = true
        }
    }

    private fun plan(entry: Held, source: TileSource, window: TileWindow) {
        val plan = source.plan(window.inPixelsOf(source.size), window.scale * window.image.width / source.size.width)
        entry.plan = plan
        if (entry.sharpen != window.sharpen) {
            entry.sharpen = window.sharpen
            entry.tiles.clear()
            entry.reading.values.forEach { it.cancel() }
            entry.reading.clear()
        }
        val sharpen = entry.sharpen
        val wanted = plan.all.toSet()
        entry.tiles.keys.retainAll(wanted)
        for ((key, job) in entry.reading.entries.toList()) {
            if (key !in wanted) {
                job.cancel()
                entry.reading.remove(key)
            }
        }
        for (key in plan.all) {
            if (key in entry.tiles || key in entry.reading) continue
            entry.reading[key] = scope.launch {
                val result = source.tile(key, sharpen)
                entry.reading.remove(key)
                if (result is PageResult.Ready && entry.sharpen == sharpen && entry.plan?.all?.contains(key) == true) {
                    entry.tiles[key] = result.image
                    changed()
                }
            }
        }
    }

    private fun drop(page: Int) {
        val entry = held.remove(page) ?: return
        entry.opening?.cancel()
        entry.reading.values.forEach { it.cancel() }
        entry.source?.let(close)
        if (entry.tiles.isNotEmpty()) changed()
    }
}

/** The size of a page as wide as [width] pixels, for the decode that opens its tiles. */
internal fun PageShape.Known.atWidth(width: Float): PixelSize =
    PixelSize(width.toInt().coerceAtLeast(1), (height.toDouble() * width / this.width).toInt().coerceAtLeast(1))
