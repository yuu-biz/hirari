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
import io.github.yuubiz.hirari.core.model.Rotation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.floor
import kotlin.math.ceil

/**
 * The part of a page's image on screen, in image pixels, how many screen pixels show one image pixel, and whether
 * シャープ is on the tiles.
 */
data class TileWindow(val shown: PixelRect, val scale: Double, val sharpen: Boolean = false)

/**
 * The pages of 縦に連続 on screen that show as tiles (docs/spec.md sections 3.7 and 4.8): those narrower than 1:3, as
 * the book stores them, and the part of each on screen. A turned page keeps its prepared bitmap, since tiles come
 * in the image's own orientation.
 */
fun ReaderState.tileWindows(): Map<Int, TileWindow> {
    if (scroll == null || config.rotation != Rotation.None) return emptyMap()
    return stripPages().mapNotNull { (page, rect) ->
        val shape = pagination.shapes[page] as? PageShape.Known ?: return@mapNotNull null
        if (!isElongated(shape.width, shape.height)) return@mapNotNull null
        val scale = rect.width.toDouble() / shape.width
        val shown = PixelRect(
            floor(-rect.left / scale).toInt().coerceIn(0, shape.width),
            floor(-rect.top / scale).toInt().coerceIn(0, shape.height),
            ceil((viewport.width - rect.left) / scale).toInt().coerceIn(0, shape.width),
            ceil((viewport.height - rect.top) / scale).toInt().coerceIn(0, shape.height),
        )
        if (shown.isEmpty) null else page to TileWindow(shown, scale, config.adjust.sharpen)
    }.toMap()
}

/** A tile ready to draw: its [bitmap] covers [rect] of the page's image, in image pixels. */
class DrawnTile(val rect: PixelRect, val bitmap: Bitmap)

/** What reading a page for its tiles gave. */
internal sealed interface TileOpening {
    class Tiled(val source: TileSource) : TileOpening

    /** The page has no tiles, or cannot have them, so its prepared page alone shows. */
    data object Whole : TileOpening

    /** The read failed in a way that may pass, as a lost connection does. */
    data object Failed : TileOpening
}

/**
 * The tiles of the pages [tileWindows] names, read over their prepared pages while they are on screen. Each page
 * gets its [TileSource] once it shows; a page that leaves the screen closes it. A page whose read [TileOpening.Failed]
 * is read again when it comes back on screen and on [retry]. Tiles are never recycled here, as section 3.7 asks,
 * since a frame may still draw one. Everything runs in [scope], on the main thread; [open] and the tile reads move
 * their work off it, and [close] closes a source off it.
 */
internal class StripTiles(
    private val scope: CoroutineScope,
    private val open: suspend (page: Int) -> TileOpening,
    private val close: (TileSource) -> Unit,
    private val changed: () -> Unit,
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

    fun update(next: Map<Int, TileWindow>) {
        if (next == windows) return
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
        return plan.all.mapNotNull { key -> entry.tiles[key]?.let { DrawnTile(tileRect(key, source.size), it) } }
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
            TileOpening.Failed -> entry.failed = true
        }
    }

    private fun plan(entry: Held, source: TileSource, window: TileWindow) {
        val plan = source.plan(window.shown, window.scale)
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
