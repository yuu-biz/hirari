package io.github.yuubiz.hirari.core.image

import kotlin.math.abs

/** A rectangle of pixels from [left] and [top] inclusive to [right] and [bottom] exclusive. */
data class PixelRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val isEmpty: Boolean get() = width <= 0 || height <= 0

    fun intersects(other: PixelRect): Boolean =
        left < other.right && other.left < right && top < other.bottom && other.top < bottom

    fun clippedTo(bound: PixelRect): PixelRect =
        PixelRect(maxOf(left, bound.left), maxOf(top, bound.top), minOf(right, bound.right), minOf(bottom, bound.bottom))
}

/**
 * One tile of an image (docs/spec.md section 3.7). At [sample] the image is read at 1/[sample] of its size, and
 * the tile in [column] and [row] covers [TILE_SIDE] output pixels a side, fewer at the right and bottom edges.
 */
data class TileKey(val sample: Int, val column: Int, val row: Int)

/** The tiles to hold for one viewport: those it shows, then those of the margin around it, nearest first. */
data class TilePlan(val sample: Int, val visible: List<TileKey>, val margin: List<TileKey>) {
    val all: List<TileKey> get() = visible + margin
}

/** The side of a tile in output pixels (docs/spec.md section 3.7, 要検証). */
const val TILE_SIDE: Int = 1024

/** The tiles a tile source holds at most: a quarter of [budgetBytes], at 4 bytes a pixel. */
fun tileLimit(budgetBytes: Long, tileSide: Int = TILE_SIDE): Int =
    (budgetBytes / 4 / (tileSide.toLong() * tileSide * 4)).toInt().coerceAtLeast(1)

/** The sample size to read tiles at when the screen shows [scale] screen pixels per image pixel. */
fun tileSample(scale: Double): Int {
    require(scale > 0) { "scale $scale" }
    var sample = 1
    while (sample * 2 * scale <= 1.0) sample *= 2
    return sample
}

/** The part of an image of [image] pixels that the tile [key] covers, in image pixels. */
fun tileRect(key: TileKey, image: PixelSize, tileSide: Int = TILE_SIDE): PixelRect {
    val span = tileSide * key.sample
    return PixelRect(key.column * span, key.row * span, (key.column + 1) * span, (key.row + 1) * span)
        .clippedTo(PixelRect(0, 0, image.width, image.height))
}

/**
 * The tiles of an image of [image] pixels to hold while the screen shows [viewport], in image pixels, at
 * [scale] screen pixels per image pixel (docs/spec.md section 3.7). They cover the viewport and one viewport of
 * margin on every side. Visible tiles are always planned. Margin tiles follow nearest first, up to [maxTiles] in
 * all.
 */
fun planTiles(image: PixelSize, viewport: PixelRect, scale: Double, maxTiles: Int, tileSide: Int = TILE_SIDE): TilePlan {
    val sample = tileSample(scale)
    val bounds = PixelRect(0, 0, image.width, image.height)
    val shown = viewport.clippedTo(bounds)
    if (shown.isEmpty) return TilePlan(sample, emptyList(), emptyList())
    val around = PixelRect(
        viewport.left - viewport.width,
        viewport.top - viewport.height,
        viewport.right + viewport.width,
        viewport.bottom + viewport.height,
    ).clippedTo(bounds)
    val span = tileSide * sample
    val centerX = (shown.left + shown.right) / 2.0
    val centerY = (shown.top + shown.bottom) / 2.0
    val visible = ArrayList<TileKey>()
    val margin = ArrayList<TileKey>()
    for (row in around.top / span..(around.bottom - 1) / span) {
        for (column in around.left / span..(around.right - 1) / span) {
            val key = TileKey(sample, column, row)
            if (tileRect(key, image, tileSide).intersects(shown)) visible += key else margin += key
        }
    }
    val nearest = margin.sortedBy { key ->
        val rect = tileRect(key, image, tileSide)
        abs((rect.left + rect.right) / 2.0 - centerX) + abs((rect.top + rect.bottom) / 2.0 - centerY)
    }
    return TilePlan(sample, visible, nearest.take((maxTiles - visible.size).coerceAtLeast(0)))
}

/**
 * The EXIF orientations, by their tag values 1 to 8. Each maps a rectangle of the image as shown back to the
 * stored image, which `BitmapRegionDecoder` reads without turning it (docs/spec.md section 3.6).
 */
enum class Orientation(val exif: Int, val swapsSides: Boolean) {
    Normal(1, false),
    FlipHorizontal(2, false),
    Rotate180(3, false),
    FlipVertical(4, false),
    Transpose(5, true),
    Rotate90(6, true),
    Transverse(7, true),
    Rotate270(8, true),
    ;

    /** The size the stored [source] shows at. */
    fun shown(source: PixelSize): PixelSize = if (swapsSides) PixelSize(source.height, source.width) else source

    /** The rectangle of the stored image of [source] pixels that shows as [shown]. */
    fun toStored(shown: PixelRect, source: PixelSize): PixelRect {
        val w = source.width
        val h = source.height
        return when (this) {
            Normal -> shown
            FlipHorizontal -> PixelRect(w - shown.right, shown.top, w - shown.left, shown.bottom)
            Rotate180 -> PixelRect(w - shown.right, h - shown.bottom, w - shown.left, h - shown.top)
            FlipVertical -> PixelRect(shown.left, h - shown.bottom, shown.right, h - shown.top)
            Transpose -> PixelRect(shown.top, shown.left, shown.bottom, shown.right)
            Rotate90 -> PixelRect(shown.top, h - shown.right, shown.bottom, h - shown.left)
            Transverse -> PixelRect(w - shown.bottom, h - shown.right, w - shown.top, h - shown.left)
            Rotate270 -> PixelRect(w - shown.bottom, shown.left, w - shown.top, shown.right)
        }
    }

    companion object {
        /** Unknown and missing values show the image as stored. */
        fun of(exif: Int): Orientation = entries.firstOrNull { it.exif == exif } ?: Normal
    }
}

/** Whether an image of [width] x [height] is narrower than 1:3 either way, which always shows as tiles (section 3.7). */
fun isElongated(width: Int, height: Int): Boolean =
    maxOf(width, height).toLong() > 3L * minOf(width, height)
