package io.github.yuubiz.hirari.core.image

import android.graphics.Bitmap
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
 * sample size, which averages at any power of two. Half of the budget goes to the output and the rest, less a
 * reserve for what the limit cannot count, to libjxl ([jxlMemoryLimit]).
 */
internal fun decodeJxlPage(
    bytes: ByteArray,
    target: PixelSize,
    decoding: ImageDecoding,
    tiling: Tiling,
    isActive: () -> Boolean,
): PageResult<PreparedImage> {
    val header = when (val read = JxlDecoder.header(bytes)) {
        is JxlHeaderResult.Failed -> return read.failure.pageResult(width = 0, height = 0)
        is JxlHeaderResult.Read -> read.header
    }
    return prepareJxlPage(header, target, decoding, tiling, isActive) { sample ->
        JxlDecoder.decode(bytes, header, sample, jxlMemoryLimit(decoding.budgetBytes), isActive)
    }
}

/** Plans and prepares a JPEG XL page, keeping the native decode separate from the whole/tile choice. */
internal fun prepareJxlPage(
    header: JxlHeader,
    target: PixelSize,
    decoding: ImageDecoding,
    tiling: Tiling,
    isActive: () -> Boolean,
    decode: (sample: Int) -> JxlDecodeResult,
): PageResult<PreparedImage> {
    dimensionRefusal(header.width, header.height)?.let { return PageResult.Unsupported(it) }
    if (!isActive()) throw CancellationException("cancelled at the header")
    val shape = PageShape.Known(header.width, header.height)
    fun decodeOnce(read: PixelSize, extraSample: Int, maxSide: Int): PageResult<PreparedImage> {
        val plan = planDecode(header.width, header.height, read, Int.MAX_VALUE, 4, decoding.budgetBytes, maxSide) as DecodePlan.Decode
        return when (val decoded = decode(plan.sampleSize * extraSample)) {
            is JxlDecodeResult.Failed -> decoded.failure.pageResult(header.width, header.height)
            is JxlDecodeResult.Decoded -> PageResult.Ready(
                PreparedImage.Whole(scaledToOutput(decoded.bitmap, plan.output), shape),
            )
        }
    }
    return if (tiling == Tiling.Always || tiling == Tiling.AsPlanned && isElongated(header.width, header.height)) {
        slicedWhole(shape, target, Int.MAX_VALUE, 4, decoding) { read, extraSample ->
            decodeOnce(read, extraSample, maxSide = Int.MAX_VALUE)
        }
    } else {
        retryingOnce { extraSample -> decodeOnce(target, extraSample, maxSide = DecodeLimits.MAX_BITMAP_SIDE) }
    }
}

private fun JxlFailure.pageResult(width: Int, height: Int): PageResult<Nothing> = when (this) {
    JxlFailure.Corrupt -> corrupt
    JxlFailure.Truncated -> PageResult.Failed(FailureReason.Truncated, retryable = false)
    JxlFailure.OverLimit -> PageResult.Unsupported(UnsupportedKind.TooLarge(width, height, width.toLong() * height * 4))
    JxlFailure.Cancelled -> throw CancellationException("cancelled while decoding")
}

/** [decoded] brought down to [output] by halving and one last bilinear step (docs/spec.md section 5.3). */
private fun scaledToOutput(decoded: Bitmap, output: PixelSize): Bitmap =
    scaledInSteps(decoded, null, shrinkToFit(output, PixelSize(decoded.width, decoded.height)), recycleSource = true).also { it.prepareToDraw() }
