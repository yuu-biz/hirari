package io.github.yuubiz.hirari.core.image

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Rect
import io.github.yuubiz.hirari.core.model.ImageType
import io.github.yuubiz.hirari.core.model.PageResult
import io.github.yuubiz.hirari.core.model.PageShape
import io.github.yuubiz.hirari.core.model.UnsupportedKind
import java.io.IOException
import java.nio.ByteBuffer
import kotlin.coroutines.cancellation.CancellationException

/**
 * Decodes [bytes] to fit [target] by the steps of docs/spec.md section 3.7, on the calling thread. [isActive]
 * is checked once the header is read, which is the last point `ImageDecoder` can stop. With [Tiling.AsPlanned],
 * an image narrower than 1:3 becomes tiles instead.
 */
internal fun decodePage(
    bytes: ByteArray,
    target: PixelSize,
    decoding: ImageDecoding,
    tiling: Tiling,
    isActive: () -> Boolean,
): PageResult<PreparedImage> {
    if (sniffImage(bytes) == ImageType.JpegXl) return decodeJxlPage(bytes, target, decoding.budgetBytes, isActive)
    val (type, codec) = when (val found = platformCodec(bytes)) {
        is Found.Decodable -> found
        is Found.Undecodable -> return found.result
    }
    val bytesPerPixel = if (isSixteenBitPng(bytes)) 8 else 4
    return converted(type, codec, bytes) {
        val shape = if (tiling == Tiling.AsPlanned) header(bytes) else null
        if (shape != null && isElongated(shape.width, shape.height)) {
            tiled(bytes, shape, codec, bytesPerPixel, decoding, isActive)
        } else {
            retryingOnce { extraSample ->
                decodeOnce(bytes, target, codec.averagingLimit, bytesPerPixel, decoding.budgetBytes, isActive, extraSample)
            }
        }
    }
}

/** The size of the image in [bytes] from its header alone. */
internal fun decodeShape(bytes: ByteArray): PageResult<PageShape.Known> {
    if (sniffImage(bytes) == ImageType.JpegXl) return decodeJxlShape(bytes)
    val (type, codec) = when (val found = platformCodec(bytes)) {
        is Found.Decodable -> found
        is Found.Undecodable -> return found.result
    }
    return converted(type, codec, bytes) { PageResult.Ready(header(bytes)) }
}

/** The size from the header of [bytes], or [Refused] when it cannot be real. */
private fun header(bytes: ByteArray): PageShape.Known {
    try {
        ImageDecoder.decodeBitmap(source(bytes)) { _, info, _ -> throw HeaderRead(info.size.width, info.size.height) }
    } catch (header: HeaderRead) {
        dimensionRefusal(header.width, header.height)?.let { throw Refused(it) }
        return PageShape.Known(header.width, header.height)
    }
    throw IOException("the decoder passed the header without reporting it")
}

/**
 * The image as tiles: read from [bytes] region by region when the platform can, and otherwise cut from one
 * bitmap of the whole image, scaled down only as far as the budget needs (docs/spec.md section 3.7).
 */
private fun tiled(
    bytes: ByteArray,
    shape: PageShape.Known,
    codec: Codec.Platform,
    bytesPerPixel: Int,
    decoding: ImageDecoding,
    isActive: () -> Boolean,
): PageResult<PreparedImage> {
    if (codec.regions) {
        RegionTiles.open(bytes, decoding)?.let { return PageResult.Ready(PreparedImage.Tiled(it, PageShape.Known(it.size.width, it.size.height))) }
    }
    val full = PixelSize(shape.width, shape.height)
    return when (
        val whole = retryingOnce { extraSample ->
            decodeOnce(bytes, full, codec.averagingLimit, bytesPerPixel, decoding.budgetBytes, isActive, extraSample, maxSide = Int.MAX_VALUE)
        }
    ) {
        is PageResult.Ready -> {
            val bitmap = (whole.image as PreparedImage.Whole).bitmap
            PageResult.Ready(PreparedImage.Tiled(SlicedTiles(bitmap, full, decoding), shape))
        }
        is PageResult.Failed -> whole
        is PageResult.Unsupported -> whole
    }
}

/** [decode] at the planned size, and once more at a reduction of 2 on each side when memory runs out (docs/spec.md section 6.1). */
internal inline fun <T> retryingOnce(decode: (reduction: Int) -> PageResult<T>): PageResult<T> = try {
    decode(1)
} catch (_: OutOfMemoryError) {
    try {
        decode(2)
    } catch (_: OutOfMemoryError) {
        readError
    }
}

private sealed interface Found {
    data class Decodable(val type: ImageType, val codec: Codec.Platform) : Found

    data class Undecodable(val result: PageResult.Unsupported) : Found
}

/** The type of [bytes] and the platform decoder for it, or why there is none. */
private fun platformCodec(bytes: ByteArray): Found {
    val type = sniffImage(bytes) ?: return Found.Undecodable(notAnImage)
    return when (val codec = codecFor(type)) {
        is Codec.Platform -> Found.Decodable(type, codec)
        is Codec.Missing -> Found.Undecodable(PageResult.Unsupported(UnsupportedKind.ImageFeature(codec.feature)))
    }
}

/**
 * Runs [block] and turns whatever the decoder throws into a page result (docs/spec.md section 2.4). Data the
 * decoder calls malformed is an unsupported feature when the device may lack the decoder for [type], or when
 * the JPEG frame header names a coding process 8-bit decoders lack (section 3.6).
 */
private inline fun <T> converted(type: ImageType, codec: Codec.Platform, bytes: ByteArray, block: () -> PageResult<T>): PageResult<T> {
    val result = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (refused: Refused) {
        PageResult.Unsupported(refused.kind)
    } catch (e: ImageDecoder.DecodeException) {
        decodeFailure(e.error)
    } catch (_: IOException) {
        corrupt
    } catch (_: RuntimeException) {
        corrupt
    }
    if (result != corrupt) return result
    val feature = when {
        codec.needsDeviceDecoder -> type.displayName
        type == ImageType.Jpeg -> jpegFeature(bytes)
        else -> null
    }
    return feature?.let { PageResult.Unsupported(UnsupportedKind.ImageFeature(it)) } ?: result
}

private fun decodeOnce(
    bytes: ByteArray,
    target: PixelSize,
    averagingLimit: Int,
    bytesPerPixel: Int,
    budgetBytes: Long,
    isActive: () -> Boolean,
    extraSample: Int,
    maxSide: Int = DecodeLimits.MAX_BITMAP_SIDE,
): PageResult<PreparedImage> {
    var planned: DecodePlan.Decode? = null
    var shape: PageShape.Known? = null
    val decoded = ImageDecoder.decodeBitmap(source(bytes)) { decoder, info, _ ->
        if (!isActive()) throw CancellationException("cancelled at the header")
        val width = info.size.width
        val height = info.size.height
        when (val plan = planDecode(width, height, target, averagingLimit, bytesPerPixel, budgetBytes, maxSide)) {
            is DecodePlan.Refuse -> throw Refused(plan.kind)
            is DecodePlan.Decode -> {
                planned = plan
                shape = PageShape.Known(width, height)
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.setTargetSampleSize(plan.sampleSize * extraSample)
                decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
            }
        }
    }
    var bitmap = decoded
    try {
        val from = PixelSize(decoded.width, decoded.height)
        val output = shrinkToFit(planned!!.output, from)
        val steps = downscaleSteps(from, output).ifEmpty { if (decoded.config == Bitmap.Config.ARGB_8888) emptyList() else listOf(from) }
        for (step in steps) {
            val next = scaled(bitmap, step)
            bitmap.recycle()
            bitmap = next
        }
        bitmap.prepareToDraw()
    } catch (e: Throwable) {
        bitmap.recycle()
        throw e
    }
    return PageResult.Ready(PreparedImage.Whole(bitmap, shape!!))
}

private fun source(bytes: ByteArray): ImageDecoder.Source = ImageDecoder.createSource(ByteBuffer.wrap(bytes))

/** [bitmap] scaled to [size] with a bilinear filter, in ARGB_8888. */
private fun scaled(bitmap: Bitmap, size: PixelSize): Bitmap {
    val out = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
    try {
        Canvas(out).drawBitmap(bitmap, null, Rect(0, 0, size.width, size.height), Paint(Paint.FILTER_BITMAP_FLAG))
    } catch (e: Throwable) {
        out.recycle()
        throw e
    }
    return out
}

/** Thrown from the header listener to stop before any pixel is allocated. */
private class Refused(val kind: UnsupportedKind.TooLarge) : RuntimeException(null, null, false, false)

private class HeaderRead(val width: Int, val height: Int) : RuntimeException(null, null, false, false)
