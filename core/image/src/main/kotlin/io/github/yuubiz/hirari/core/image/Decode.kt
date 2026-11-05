package io.github.yuubiz.hirari.core.image

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
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
 * an image narrower than 1:3 becomes tiles instead, and with [Tiling.Always] every image does.
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
        val shape = if (tiling != Tiling.Never) header(bytes) else null
        if (shape != null && (tiling == Tiling.Always || isElongated(shape.width, shape.height))) {
            tiled(bytes, shape, target, codec, bytesPerPixel, decoding, isActive)
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
 * bitmap of the whole image, read at the size [slicedWholeSize] gives. When even that would be no sharper than the
 * image read to fit [target], the image is [PreparedImage.Untiled] (docs/spec.md section 3.7).
 */
private fun tiled(
    bytes: ByteArray,
    shape: PageShape.Known,
    target: PixelSize,
    codec: Codec.Platform,
    bytesPerPixel: Int,
    decoding: ImageDecoding,
    isActive: () -> Boolean,
): PageResult<PreparedImage> {
    if (codec.regions) {
        RegionTiles.open(bytes, codec.averagingLimit, decoding)?.let { return PageResult.Ready(PreparedImage.Tiled(it, PageShape.Known(it.size.width, it.size.height))) }
    }
    val prepared = when (val plan = planDecode(shape.width, shape.height, target, codec.averagingLimit, bytesPerPixel, decoding.budgetBytes)) {
        is DecodePlan.Decode -> plan.output
        is DecodePlan.Refuse -> return PageResult.Ready(PreparedImage.Untiled(shape))
    }
    val read = slicedWholeSize(shape.width, shape.height, decoding.budgetBytes, prepared)
        ?: return PageResult.Ready(PreparedImage.Untiled(shape))
    val full = PixelSize(shape.width, shape.height)
    return when (
        val whole = retryingOnce { extraSample ->
            decodeOnce(bytes, read, codec.averagingLimit, bytesPerPixel, decoding.budgetBytes, isActive, extraSample, maxSide = Int.MAX_VALUE)
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
    val from = PixelSize(decoded.width, decoded.height)
    val bitmap = scaledInSteps(decoded, null, shrinkToFit(planned!!.output, from), recycleSource = true)
    bitmap.prepareToDraw()
    return PageResult.Ready(PreparedImage.Whole(bitmap, shape!!))
}

private fun source(bytes: ByteArray): ImageDecoder.Source = ImageDecoder.createSource(ByteBuffer.wrap(bytes))

/**
 * [source], or its [part], scaled to [to] in ARGB_8888 by the steps of [downscaleSteps] for [turn], the
 * orientation the result is shown in afterwards (docs/spec.md section 5.3). Intermediate bitmaps are recycled;
 * [source] is recycled only with [recycleSource]. The result is [source] itself when [part] is null, it is
 * already [to] and ARGB_8888.
 */
internal fun scaledInSteps(source: Bitmap, part: Rect?, to: PixelSize, recycleSource: Boolean, turn: Orientation = Orientation.Normal): Bitmap {
    var current = source
    var currentPart = part
    try {
        val from = part?.let { PixelSize(it.width(), it.height()) } ?: PixelSize(source.width, source.height)
        val steps = downscaleSteps(from, to, turn).ifEmpty {
            if (part == null && source.config == Bitmap.Config.ARGB_8888) return source else listOf(ScaleStep.Bilinear(to))
        }
        for (step in steps) {
            val next = drawn(step.size) { canvas ->
                when (step) {
                    is ScaleStep.Halve -> canvas.drawPaint(halving(current, currentPart, step))
                    is ScaleStep.Bilinear -> canvas.drawBitmap(current, currentPart, Rect(0, 0, step.size.width, step.size.height), Paint(Paint.FILTER_BITMAP_FLAG))
                }
            }
            if (current !== source || recycleSource) current.recycle()
            current = next
            currentPart = null
        }
    } catch (e: Throwable) {
        if (current !== source || recycleSource) current.recycle()
        throw e
    }
    return current
}

/** A bitmap of [size] in ARGB_8888 that [draw] draws. */
private inline fun drawn(size: PixelSize, draw: (Canvas) -> Unit): Bitmap {
    val out = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
    try {
        draw(Canvas(out))
    } catch (e: Throwable) {
        out.recycle()
        throw e
    }
    return out
}

/**
 * The paint that fills the output of [step] from [part] of [bitmap], or all of it. A bilinear filter that
 * samples on the edge between the two pixels of a pair averages them exactly, and past the end of an odd side
 * the clamped edge repeats the end pixel.
 */
private fun halving(bitmap: Bitmap, part: Rect?, step: ScaleStep.Halve): Paint {
    val toOutput = Matrix().apply {
        setTranslate(step.across.lead - (part?.left ?: 0), step.down.lead - (part?.top ?: 0))
        postScale(step.across.factor, step.down.factor)
    }
    val shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { setLocalMatrix(toOutput) }
    return Paint(Paint.FILTER_BITMAP_FLAG).apply { this.shader = shader }
}

/** The pixels this side is padded by before its first pixel. */
private val SideStep.lead: Float get() = if (this == SideStep.HalvedFromEnd) 1f else 0f

private val SideStep.factor: Float get() = if (this == SideStep.Kept) 1f else 0.5f

/** Thrown from the header listener to stop before any pixel is allocated. */
private class Refused(val kind: UnsupportedKind.TooLarge) : RuntimeException(null, null, false, false)

private class HeaderRead(val width: Int, val height: Int) : RuntimeException(null, null, false, false)
