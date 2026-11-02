package io.github.yuubiz.hirari.core.image

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import io.github.yuubiz.hirari.core.model.FailureReason
import io.github.yuubiz.hirari.core.model.PageResult
import io.github.yuubiz.hirari.core.model.PageShape
import io.github.yuubiz.hirari.core.model.UnsupportedKind
import kotlin.coroutines.cancellation.CancellationException

/** The size of the JPEG XL image in [bytes] from its header alone. */
internal fun decodeJxlShape(bytes: ByteArray): PageResult<PageShape.Known> =
    when (val read = JxlDecoder.header(bytes)) {
        is JxlHeaderResult.Failed -> read.failure.pageResult(width = 0, height = 0)
        is JxlHeaderResult.Read -> {
            val (width, height) = read.header
            dimensionRefusal(width, height)?.let { PageResult.Unsupported(it) }
                ?: PageResult.Ready(PageShape.Known(width, height))
        }
    }

/**
 * Decodes the JPEG XL image in [bytes] to fit [target] by the steps of docs/spec.md section 3.7. libjxl cannot
 * decode at a lower resolution, so it decodes every pixel and the JNI side averages them down by the planned
 * sample size, which averages at any power of two. Half of [budgetBytes] goes to the output and the rest, less a
 * reserve for what the limit cannot count, to libjxl ([jxlMemoryLimit]).
 */
internal fun decodeJxlPage(
    bytes: ByteArray,
    target: PixelSize,
    budgetBytes: Long,
    isActive: () -> Boolean,
): PageResult<PreparedImage> {
    val header = when (val read = JxlDecoder.header(bytes)) {
        is JxlHeaderResult.Failed -> return read.failure.pageResult(width = 0, height = 0)
        is JxlHeaderResult.Read -> read.header
    }
    val plan = when (val plan = planDecode(header.width, header.height, target, Int.MAX_VALUE, 4, budgetBytes)) {
        is DecodePlan.Refuse -> return PageResult.Unsupported(plan.kind)
        is DecodePlan.Decode -> plan
    }
    if (!isActive()) throw CancellationException("cancelled at the header")
    fun decodeOnce(extraSample: Int): PageResult<PreparedImage> =
        when (val decoded = JxlDecoder.decode(bytes, header, plan.sampleSize * extraSample, jxlMemoryLimit(budgetBytes), isActive)) {
            is JxlDecodeResult.Failed -> decoded.failure.pageResult(header.width, header.height)
            is JxlDecodeResult.Decoded -> PageResult.Ready(
                PreparedImage.Whole(scaledToOutput(decoded.bitmap, plan.output), PageShape.Known(header.width, header.height)),
            )
        }
    return try {
        decodeOnce(extraSample = 1)
    } catch (_: OutOfMemoryError) {
        try {
            decodeOnce(extraSample = 2)
        } catch (_: OutOfMemoryError) {
            readError
        }
    }
}

private fun JxlFailure.pageResult(width: Int, height: Int): PageResult<Nothing> = when (this) {
    JxlFailure.Corrupt -> corrupt
    JxlFailure.Truncated -> PageResult.Failed(FailureReason.Truncated, retryable = false)
    JxlFailure.OverLimit -> PageResult.Unsupported(UnsupportedKind.TooLarge(width, height, width.toLong() * height * 4))
    JxlFailure.Cancelled -> throw CancellationException("cancelled while decoding")
}

/** [decoded] brought down to [output] by halving and one last bilinear step (docs/spec.md section 5.3). */
private fun scaledToOutput(decoded: Bitmap, output: PixelSize): Bitmap {
    var bitmap = decoded
    try {
        val from = PixelSize(decoded.width, decoded.height)
        for (step in downscaleSteps(from, shrinkToFit(output, from))) {
            val next = Bitmap.createBitmap(step.width, step.height, Bitmap.Config.ARGB_8888)
            Canvas(next).drawBitmap(bitmap, null, Rect(0, 0, step.width, step.height), Paint(Paint.FILTER_BITMAP_FLAG))
            bitmap.recycle()
            bitmap = next
        }
        bitmap.prepareToDraw()
    } catch (e: Throwable) {
        bitmap.recycle()
        throw e
    }
    return bitmap
}
