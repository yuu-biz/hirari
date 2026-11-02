package io.github.yuubiz.hirari.core.image

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Rect
import io.github.yuubiz.hirari.core.model.PageResult
import io.github.yuubiz.hirari.core.model.PageShape
import io.github.yuubiz.hirari.core.model.UnsupportedKind
import java.io.IOException
import java.nio.ByteBuffer
import kotlin.coroutines.cancellation.CancellationException

/**
 * Decodes [bytes] to fit [target] by the steps of docs/spec.md section 3.7, on the calling thread. [isActive]
 * is checked once the header is read, which is the last point `ImageDecoder` can stop.
 */
internal fun decodePage(
    bytes: ByteArray,
    target: PixelSize,
    budgetBytes: Long,
    isActive: () -> Boolean,
): PageResult<PreparedImage> {
    val type = sniffImage(bytes) ?: return notAnImage
    val averagingLimit = AVERAGING_SAMPLE_LIMIT[type] ?: return unsupportedType(type)
    val bytesPerPixel = if (isSixteenBitPng(bytes)) 8 else 4
    return decoding {
        try {
            decodeOnce(bytes, target, averagingLimit, bytesPerPixel, budgetBytes, isActive, extraSample = 1)
        } catch (_: OutOfMemoryError) {
            try {
                decodeOnce(bytes, target, averagingLimit, bytesPerPixel, budgetBytes, isActive, extraSample = 2)
            } catch (_: OutOfMemoryError) {
                readError
            }
        }
    }
}

/** The size of the image in [bytes] from its header alone. */
internal fun decodeShape(bytes: ByteArray): PageResult<PageShape.Known> {
    val type = sniffImage(bytes) ?: return notAnImage
    if (type !in AVERAGING_SAMPLE_LIMIT) return unsupportedType(type)
    return decoding {
        try {
            ImageDecoder.decodeBitmap(source(bytes)) { _, info, _ -> throw HeaderRead(info.size.width, info.size.height) }
            corrupt
        } catch (header: HeaderRead) {
            dimensionRefusal(header.width, header.height)?.let { PageResult.Unsupported(it) }
                ?: PageResult.Ready(PageShape.Known(header.width, header.height))
        }
    }
}

/** Runs [block] and turns whatever the decoder throws into a page result (docs/spec.md section 2.4). */
private inline fun <T> decoding(block: () -> PageResult<T>): PageResult<T> = try {
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

private fun decodeOnce(
    bytes: ByteArray,
    target: PixelSize,
    averagingLimit: Int,
    bytesPerPixel: Int,
    budgetBytes: Long,
    isActive: () -> Boolean,
    extraSample: Int,
): PageResult<PreparedImage> {
    var planned: DecodePlan.Decode? = null
    var shape: PageShape.Known? = null
    val decoded = ImageDecoder.decodeBitmap(source(bytes)) { decoder, info, _ ->
        if (!isActive()) throw CancellationException("cancelled at the header")
        val width = info.size.width
        val height = info.size.height
        when (val plan = planDecode(width, height, target, averagingLimit, bytesPerPixel, budgetBytes)) {
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
