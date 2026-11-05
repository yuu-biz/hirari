package io.github.yuubiz.hirari.core.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.ColorSpace
import android.graphics.Matrix
import android.graphics.Rect
import android.media.ExifInterface
import android.os.Build
import io.github.yuubiz.hirari.core.model.PageResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.ByteArrayInputStream
import java.io.Closeable
import kotlin.coroutines.cancellation.CancellationException

/**
 * The tiles of one page shown as tiles (docs/spec.md section 3.7). Whoever holds the [PreparedImage.Tiled]
 * closes it. Tiles stay valid after their source closes or drops them from its cache; they are never recycled
 * here, because the screen may still draw them.
 */
interface TileSource : Closeable {
    /** The size of the image as shown, after EXIF rotation, in image pixels. */
    val size: PixelSize

    /** The tiles to hold while the screen shows [viewport], in image pixels, at [scale] screen pixels per image pixel. */
    fun plan(viewport: PixelRect, scale: Double): TilePlan

    /**
     * The bitmap of [key], of the size of its [tileRect] at its sample size, or smaller when memory ran out on
     * the first read (docs/spec.md section 6.1). With [sharpen], シャープ is on it, its edges read as the edges of
     * an image (docs/spec.md section 4.8).
     */
    suspend fun tile(key: TileKey, sharpen: Boolean = false): PageResult<Bitmap>
}

/** A tile as the cache holds it: sharpened tiles apart from plain ones. */
private data class Held(val key: TileKey, val sharpen: Boolean)

/**
 * What the tile sources share: the plan, the bound on tiles held, and the cache. The cache keeps the tiles most
 * recently asked for, up to [maxTiles] plain and sharpened together, which is also what a plan asks for at most,
 * so a viewport's tiles stay while the screen keeps asking for them. Tiles are read on [dispatcher].
 */
internal abstract class CachedTiles(
    override val size: PixelSize,
    decoding: ImageDecoding,
    private val dispatcher: CoroutineDispatcher = decoding.dispatcher,
) : TileSource {
    private val maxTiles = tileLimit(decoding.budgetBytes)
    private val cache = object : LinkedHashMap<Held, Bitmap>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Held, Bitmap>) = size > maxTiles
    }
    private val scope = CoroutineScope(SupervisorJob())

    override fun plan(viewport: PixelRect, scale: Double): TilePlan = planTiles(size, viewport, scale, maxTiles)

    override suspend fun tile(key: TileKey, sharpen: Boolean): PageResult<Bitmap> {
        val held = Held(key, sharpen)
        synchronized(cache) { cache[held] }?.let { return PageResult.Ready(it) }
        val rect = tileRect(key, size)
        if (rect.isEmpty) return corrupt
        val result = try {
            decodeWithTimeout(scope, dispatcher) { _ ->
                retryingOnce { reduction ->
                    decodeTile(rect, key.sample, reduction)?.let { PageResult.Ready(if (sharpen) it.sharpenedTile() else it) } ?: corrupt
                }
            }
        } catch (_: CancellationException) {
            currentCoroutineContext().ensureActive()
            readError
        } catch (_: OutOfMemoryError) {
            readError
        } catch (_: Exception) {
            corrupt
        }
        if (result is PageResult.Ready) synchronized(cache) { cache[held] = result.image }
        return result
    }

    /** [sharpened], with this tile recycled when that made another or ran out of memory. */
    private fun Bitmap.sharpenedTile(): Bitmap = try {
        sharpened(this).also { if (it !== this) recycle() }
    } catch (e: Throwable) {
        recycle()
        throw e
    }

    /**
     * The bitmap of [rect], in shown pixels, at 1/[sample] of its size, on the calling thread. A [reduction] of 2
     * reads it again after memory ran out, at twice the reduction (docs/spec.md section 6.1).
     */
    protected abstract fun decodeTile(rect: PixelRect, sample: Int, reduction: Int): Bitmap?

    override fun close() {
        scope.cancel()
        synchronized(cache) { cache.clear() }
        release()
    }

    protected abstract fun release()
}

/** Tiles read from the encoded bytes by `BitmapRegionDecoder`, turned by the EXIF orientation it ignores. */
internal class RegionTiles private constructor(
    private val decoder: BitmapRegionDecoder,
    private val orientation: Orientation,
    private val stored: PixelSize,
    private val averagingLimit: Int,
    decoding: ImageDecoding,
) : CachedTiles(orientation.shown(stored), decoding) {
    private val budgetBytes = decoding.budgetBytes

    override fun decodeTile(rect: PixelRect, sample: Int, reduction: Int): Bitmap? {
        val source = orientation.toStored(rect, stored)
        val read = planTileRead(PixelSize(source.width, source.height), sample, averagingLimit, budgetBytes, reduction)
        val options = BitmapFactory.Options().apply {
            inSampleSize = read.decodeSample
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
        }
        val region = synchronized(decoder) {
            if (decoder.isRecycled) return null
            decoder.decodeRegion(Rect(source.left, source.top, source.right, source.bottom), options)
        } ?: return null
        val scaled = scaledInSteps(region, null, shrinkToFit(read.output, PixelSize(region.width, region.height)), recycleSource = true, orientation)
        val matrix = orientation.matrix() ?: return scaled
        return try {
            Bitmap.createBitmap(scaled, 0, 0, scaled.width, scaled.height, matrix, true)
        } finally {
            scaled.recycle()
        }
    }

    override fun release() = synchronized(decoder) { decoder.recycle() }

    companion object {
        /**
         * The region decoder over [bytes], or null when the platform cannot read regions of them.
         * [averagingLimit] is the largest sample size at which it averages pixels (docs/spec.md section 5.3).
         */
        fun open(bytes: ByteArray, averagingLimit: Int, decoding: ImageDecoding): RegionTiles? {
            val decoder = try {
                if (Build.VERSION.SDK_INT >= 31) {
                    BitmapRegionDecoder.newInstance(bytes, 0, bytes.size)
                } else {
                    @Suppress("DEPRECATION")
                    BitmapRegionDecoder.newInstance(bytes, 0, bytes.size, false)
                }
            } catch (_: Exception) {
                null
            } ?: return null
            val orientation = try {
                Orientation.of(ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1))
            } catch (_: Exception) {
                Orientation.Normal
            }
            return RegionTiles(decoder, orientation, PixelSize(decoder.width, decoder.height), averagingLimit, decoding)
        }
    }
}

/**
 * Tiles cut from one bitmap of the whole image, for the formats `BitmapRegionDecoder` cannot read (docs/spec.md
 * section 3.7). [whole] is already turned and may be smaller than [size] when the budget made it so.
 */
internal class SlicedTiles(private val whole: Bitmap, size: PixelSize, decoding: ImageDecoding) : CachedTiles(size, decoding) {
    override fun decodeTile(rect: PixelRect, sample: Int, reduction: Int): Bitmap? {
        val across = whole.width / size.width.toDouble()
        val down = whole.height / size.height.toDouble()
        val left = (rect.left * across).toInt().coerceIn(0, whole.width - 1)
        val top = (rect.top * down).toInt().coerceIn(0, whole.height - 1)
        val right = (rect.right * across).toInt().coerceIn(left + 1, whole.width)
        val bottom = (rect.bottom * down).toInt().coerceIn(top + 1, whole.height)
        val output = PixelSize(sampled(rect.width, sample * reduction), sampled(rect.height, sample * reduction))
        return synchronized(whole) {
            if (whole.isRecycled) return null
            scaledInSteps(whole, Rect(left, top, right, bottom), output, recycleSource = false)
        }
    }

    override fun release() = synchronized(whole) { whole.recycle() }
}

/** The matrix that turns the stored image to show it, or null when it shows as stored. */
private fun Orientation.matrix(): Matrix? = when (this) {
    Orientation.Normal -> null
    Orientation.FlipHorizontal -> Matrix().apply { postScale(-1f, 1f) }
    Orientation.Rotate180 -> Matrix().apply { postRotate(180f) }
    Orientation.FlipVertical -> Matrix().apply { postScale(1f, -1f) }
    Orientation.Transpose -> Matrix().apply { postRotate(90f); postScale(-1f, 1f) }
    Orientation.Rotate90 -> Matrix().apply { postRotate(90f) }
    Orientation.Transverse -> Matrix().apply { postRotate(90f); postScale(1f, -1f) }
    Orientation.Rotate270 -> Matrix().apply { postRotate(270f) }
}
