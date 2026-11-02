package io.github.yuubiz.hirari.core.image

import io.github.yuubiz.hirari.core.model.UnsupportedKind
import kotlin.math.min
import kotlin.math.roundToInt

data class PixelSize(val width: Int, val height: Int) {
    init {
        require(width > 0 && height > 0) { "empty size ${width}x$height" }
    }
}

/** The rules of docs/spec.md section 3.7 that do not depend on the device. */
object DecodeLimits {
    /** Longer sides are refused as a lie or an attack. */
    const val MAX_DECLARED_SIDE: Int = 1 shl 20

    const val MAX_DECLARED_PIXELS: Long = 1L shl 28

    /** The longest side of one bitmap until tiles arrive in M3 (要検証). */
    const val MAX_BITMAP_SIDE: Int = 4096
}

sealed interface DecodePlan {
    /**
     * Decode at [sampleSize], a power of two, then scale to [output]. [needed] is the size the target asks for,
     * which [output] falls short of only when the budget forced a larger sample size.
     */
    data class Decode(val sampleSize: Int, val needed: PixelSize, val output: PixelSize) : DecodePlan

    data class Refuse(val kind: UnsupportedKind.TooLarge) : DecodePlan
}

/**
 * The plan for an image of [width] x [height] shown inside [target] (docs/spec.md section 3.7). The output fits
 * inside [target] and inside [maxSide] without enlarging the image. The decoder output is at least the output
 * and less than twice it, unless the decoder of the type cannot average that far ([averagingLimit]); then the
 * rest is halved afterwards. When the decoder output at [bytesPerPixel] would take more than half of
 * [budgetBytes], the sample size grows until it fits, even past what averages or past the output size.
 */
fun planDecode(
    width: Int,
    height: Int,
    target: PixelSize,
    averagingLimit: Int,
    bytesPerPixel: Int,
    budgetBytes: Long,
    maxSide: Int = DecodeLimits.MAX_BITMAP_SIDE,
): DecodePlan {
    dimensionRefusal(width, height)?.let { return DecodePlan.Refuse(it) }
    val scale = minOf(1.0, target.width / width.toDouble(), target.height / height.toDouble(), maxSide / maxOf(width, height).toDouble())
    val needed = PixelSize(
        (width * scale).roundToInt().coerceIn(1, width),
        (height * scale).roundToInt().coerceIn(1, height),
    )
    val fullSample = highestPowerOfTwoAtMost(min(width / needed.width, height / needed.height))
    var sample = min(fullSample, highestPowerOfTwoAtMost(averagingLimit))
    while (sampled(width, sample).toLong() * sampled(height, sample) * bytesPerPixel > budgetBytes / 2 && sample < width) {
        sample *= 2
    }
    return DecodePlan.Decode(sample, needed, shrinkToFit(needed, PixelSize(sampled(width, sample), sampled(height, sample))))
}

/**
 * How to read one tile of a [region] of the stored image at 1/[sample] of its size: decode at [decodeSample],
 * then scale to [output] by 2 x 2 averages (docs/spec.md section 3.7).
 */
internal data class TileRead(val decodeSample: Int, val output: PixelSize)

/**
 * The read of a tile whose [region] the decoder gives at the sample size, for a decoder that averages up to
 * [averagingLimit] (section 5.3). It decodes at the largest sample size the decoder averages, and the rest is
 * averaged afterwards, as for a whole image. When the decoded region would take more than half of [budgetBytes],
 * the sample size grows until it fits, even past what averages (section 3.7). A [reduction] of 2 reads again
 * after memory ran out, at twice the sample size so chosen (section 6.1); the output stays, unless the decoder
 * now gives less.
 */
internal fun planTileRead(region: PixelSize, sample: Int, averagingLimit: Int, budgetBytes: Long, reduction: Int = 1): TileRead {
    var decodeSample = min(sample, highestPowerOfTwoAtMost(averagingLimit))
    while (sampled(region.width, decodeSample).toLong() * sampled(region.height, decodeSample) * 4 > budgetBytes / 2 && decodeSample < sample) {
        decodeSample *= 2
    }
    return TileRead(decodeSample * reduction, PixelSize(sampled(region.width, sample), sampled(region.height, sample)))
}

/**
 * What libjxl may allocate through its memory manager (docs/spec.md section 3.6). The output takes up to half of
 * [budgetBytes]; libjxl gets the other half less 1/16 of the budget, because its own containers and `new` bypass
 * the manager and the limit cannot count them.
 */
internal fun jxlMemoryLimit(budgetBytes: Long): Long = budgetBytes / 2 - budgetBytes / 16

/** Why dimensions declared in a header cannot be real, or null when they can. */
internal fun dimensionRefusal(width: Int, height: Int): UnsupportedKind.TooLarge? {
    val pixels = width.toLong() * height
    val plausible = width > 0 && height > 0 && width <= DecodeLimits.MAX_DECLARED_SIDE &&
        height <= DecodeLimits.MAX_DECLARED_SIDE && pixels <= DecodeLimits.MAX_DECLARED_PIXELS
    return if (plausible) null else UnsupportedKind.TooLarge(width, height, pixels * 4)
}

/** The decoder output size for [side] at [sample], rounded up as the decoders do. */
internal fun sampled(side: Int, sample: Int): Int = ((side + sample - 1) / sample).coerceAtLeast(1)

/** [size] or less on each side, so that it fits inside [bound]. */
internal fun shrinkToFit(size: PixelSize, bound: PixelSize): PixelSize =
    PixelSize(min(size.width, bound.width), min(size.height, bound.height))

/** One step of [downscaleSteps]. */
internal sealed interface ScaleStep {
    val size: PixelSize

    /** Each side halved by [SideStep] or kept, which 2 x 2 averages do exactly (docs/spec.md section 5.3). */
    data class Halve(override val size: PixelSize, val across: SideStep, val down: SideStep) : ScaleStep

    /** The last step, bilinear and less than 2x on each side. */
    data class Bilinear(override val size: PixelSize) : ScaleStep
}

/** What a [ScaleStep.Halve] does to one side of n pixels. A halved side has (n + 1) / 2 pixels. */
internal enum class SideStep {
    Kept,

    /** Pixel i averages pixels 2i and 2i + 1. On an odd side the last pixel averages with itself. */
    Halved,

    /**
     * Pixel i averages pixels 2i - 1 and 2i, and the first pixel averages with itself, so that the pairs of an
     * odd side line up with its far end.
     */
    HalvedFromEnd,
}

/**
 * The steps to scale [from] down to [to]. Each side is halved while half of it, rounded up, is still at least
 * its side of [to], so that k halvings give the size a decoder gives at 1/2^k; a side that reaches [to] first
 * stays while the other halves. A last bilinear step of less than 2x makes up the rest (docs/spec.md section
 * 5.3). The pairs line up with the image as [turn] will show it: on a stored side that [turn] reverses, they
 * count from the far end, which is where the image as shown starts.
 */
internal fun downscaleSteps(from: PixelSize, to: PixelSize, turn: Orientation = Orientation.Normal): List<ScaleStep> = buildList {
    var current = from
    while (true) {
        val across = halvedSide(current.width, to.width, turn.reversesStoredX)
        val down = halvedSide(current.height, to.height, turn.reversesStoredY)
        if (across == SideStep.Kept && down == SideStep.Kept) break
        current = PixelSize(if (across == SideStep.Kept) current.width else half(current.width), if (down == SideStep.Kept) current.height else half(current.height))
        add(ScaleStep.Halve(current, across, down))
    }
    if (current != to) add(ScaleStep.Bilinear(to))
}

private fun half(side: Int): Int = (side + 1) / 2

private fun halvedSide(side: Int, target: Int, reversed: Boolean): SideStep = when {
    side <= target || half(side) < target -> SideStep.Kept
    reversed && side % 2 == 1 -> SideStep.HalvedFromEnd
    else -> SideStep.Halved
}

private fun highestPowerOfTwoAtMost(value: Int): Int = if (value <= 1) 1 else Integer.highestOneBit(value)
