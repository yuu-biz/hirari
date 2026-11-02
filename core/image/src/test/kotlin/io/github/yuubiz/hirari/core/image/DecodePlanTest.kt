package io.github.yuubiz.hirari.core.image

import io.github.yuubiz.hirari.core.model.UnsupportedKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DecodePlanTest {
    private val screen = PixelSize(1080, 2400)
    private val roomy = 512L shl 20

    private fun plan(
        width: Int,
        height: Int,
        target: PixelSize = screen,
        averaging: Int = 8,
        bytesPerPixel: Int = 4,
        budget: Long = roomy,
        maxSide: Int = DecodeLimits.MAX_BITMAP_SIDE,
    ) = planDecode(width, height, target, averaging, bytesPerPixel, budget, maxSide)

    private fun decode(plan: DecodePlan) = plan as DecodePlan.Decode

    @Test
    fun theDecoderOutputIsAtLeastTheOutputAndLessThanTwiceIt() {
        for (width in listOf(800, 1600, 3000, 7000, 9999)) {
            for (target in listOf(PixelSize(1080, 2400), PixelSize(540, 1200), PixelSize(300, 300), PixelSize(1, 1))) {
                val height = width * 3 / 2
                val plan = decode(plan(width, height, target, averaging = Int.MAX_VALUE))
                val outW = width / plan.sampleSize
                val outH = height / plan.sampleSize
                val at = "$width x $height in $target: $plan"
                assertTrue(at, outW >= plan.output.width && outH >= plan.output.height)
                assertTrue(at, outW < 2 * plan.output.width || outH < 2 * plan.output.height)
                assertTrue(at, plan.output.width <= target.width && plan.output.height <= target.height)
            }
        }
    }

    @Test
    fun theOutputFitsTheTargetKeepingTheAspect() {
        val plan = decode(plan(1600, 2400, PixelSize(1080, 1080)))
        assertEquals(PixelSize(720, 1080), plan.output)
        assertEquals(2, plan.sampleSize)
    }

    @Test
    fun aSmallImageIsNotEnlarged() {
        val plan = decode(plan(1, 1))
        assertEquals(PixelSize(1, 1), plan.output)
        assertEquals(1, plan.sampleSize)
    }

    @Test
    fun theSampleSizeStopsWhereTheDecoderStopsAveraging() {
        assertEquals(16, decode(plan(16000, 16000, PixelSize(1000, 1000), averaging = Int.MAX_VALUE)).sampleSize)
        assertEquals(8, decode(plan(16000, 16000, PixelSize(1000, 1000), averaging = 8)).sampleSize)
        assertEquals(1, decode(plan(4000, 6000, PixelSize(1000, 1500), averaging = 1)).sampleSize)
    }

    @Test
    fun aDecodeOverHalfTheBudgetSamplesFurtherInsteadOfFailing() {
        val budget = 64L shl 20
        val plan = decode(plan(4000, 6000, PixelSize(1000, 1500), averaging = 1, bytesPerPixel = 8, budget = budget))
        assertEquals(4, plan.sampleSize)
        assertTrue(sampled(4000, 4).toLong() * sampled(6000, 4) * 8 <= budget / 2)
        assertEquals(PixelSize(1000, 1500), plan.output)
    }

    @Test
    fun aBudgetTooSmallForTheOutputShrinksTheOutput() {
        val plan = decode(plan(4000, 4000, PixelSize(2000, 2000), budget = 2L shl 20))
        assertEquals(PixelSize(500, 500), plan.output)
        assertTrue(plan.output.width.toLong() * plan.output.height * 4 <= 1L shl 20)
        assertEquals(PixelSize(2000, 2000), plan.needed)
    }

    @Test
    fun theOutputStaysUnderTheSingleBitmapLimit() {
        val plan = decode(plan(2000, 30000, PixelSize(2000, 30000), maxSide = 4096))
        assertEquals(4096, plan.output.height)
        assertTrue(plan.output.width in 272..274)
    }

    @Test
    fun impossibleDimensionsAreRefusedBeforeAnyAllocation() {
        val refusals = listOf(0 to 10, 10 to 0, -1 to 10, (1 shl 20) + 1 to 1, 65535 to 65535)
        for ((w, h) in refusals) {
            val plan = plan(w, h)
            assertEquals("$w x $h", DecodePlan.Refuse(UnsupportedKind.TooLarge(w, h, w.toLong() * h * 4)), plan)
        }
        assertTrue(plan(800, 50_000) is DecodePlan.Decode)
        assertTrue(plan(1 shl 14, 1 shl 14) is DecodePlan.Decode)
    }

    private fun sizes(from: PixelSize, to: PixelSize) = downscaleSteps(from, to).map { it.size }

    @Test
    fun downscalingHalvesUntilTheLastStepIsUnderTwoTimes() {
        val steps = downscaleSteps(PixelSize(4000, 6000), PixelSize(700, 1050))
        assertEquals(listOf(PixelSize(2000, 3000), PixelSize(1000, 1500), PixelSize(700, 1050)), steps.map { it.size })
        assertEquals(ScaleStep.Halve(PixelSize(2000, 3000), SideStep.Halved, SideStep.Halved), steps.first())
        assertEquals(ScaleStep.Bilinear(PixelSize(700, 1050)), steps.last())
        assertEquals(listOf(PixelSize(1000, 1500)), sizes(PixelSize(2000, 3000), PixelSize(1000, 1500)))
        assertEquals(emptyList<ScaleStep>(), downscaleSteps(PixelSize(100, 100), PixelSize(100, 100)))
        assertEquals(listOf(ScaleStep.Bilinear(PixelSize(90, 100))), downscaleSteps(PixelSize(100, 100), PixelSize(90, 100)))
        assertEquals(emptyList<ScaleStep>(), downscaleSteps(PixelSize(1, 1), PixelSize(1, 1)))
    }

    @Test
    fun anOddSideHalvesRoundingUpSoThatTheEdgeTileEndsAtItsOutput() {
        assertEquals(listOf(PixelSize(320, 163), PixelSize(160, 82), PixelSize(80, 41)), sizes(PixelSize(640, 325), PixelSize(80, 41)))
        assertEquals(listOf(PixelSize(1065, 1065), PixelSize(1064, 1064)), sizes(PixelSize(2129, 2129), PixelSize(1064, 1064)))
    }

    @Test
    fun aSideAlreadyAtItsOutputStaysWhileTheOtherKeepsHalving() {
        val steps = downscaleSteps(PixelSize(640, 1), PixelSize(80, 1))
        assertEquals(listOf(PixelSize(320, 1), PixelSize(160, 1), PixelSize(80, 1)), steps.map { it.size })
        assertTrue(steps.all { it == ScaleStep.Halve(it.size, SideStep.Halved, SideStep.Kept) })
        assertEquals(listOf(PixelSize(40, 1000), PixelSize(20, 500), PixelSize(20, 250)), sizes(PixelSize(80, 2000), PixelSize(20, 250)))
    }

    /** One side of [pixels] after a halving [step], as docs/spec.md section 5.3 defines it. */
    private fun halved(pixels: DoubleArray, step: SideStep): DoubleArray {
        val last = pixels.size - 1
        return when (step) {
            SideStep.Kept -> pixels
            SideStep.Halved -> DoubleArray((pixels.size + 1) / 2) { (pixels[2 * it] + pixels[minOf(2 * it + 1, last)]) / 2 }
            SideStep.HalvedFromEnd -> DoubleArray((pixels.size + 1) / 2) { (pixels[maxOf(2 * it - 1, 0)] + pixels[2 * it]) / 2 }
        }
    }

    /** A row of [pixels] at 1/[sample], halved in the steps a stored row read for [turn] takes. */
    private fun averaged(pixels: DoubleArray, sample: Int, turn: Orientation): DoubleArray =
        downscaleSteps(PixelSize(pixels.size, 1), PixelSize(sampled(pixels.size, sample), 1), turn)
            .fold(pixels) { row, step -> halved(row, (step as ScaleStep.Halve).across) }

    @Test
    fun tilesHalvedOnTheirOwnStitchIntoTheWholeRowHalvedAtOnce() {
        val random = java.util.Random(7)
        for (sample in listOf(2, 4, 8)) for (length in listOf(3 * 64, 3 * 64 + 1, 3 * 64 + 5, 3 * 64 + 13)) {
            val shown = DoubleArray(length) { random.nextDouble() }
            val whole = averaged(shown, sample, Orientation.Normal)
            val span = 64
            for (turn in listOf(Orientation.Normal, Orientation.FlipHorizontal)) {
                val stitched = (0 until length step span).flatMap { left ->
                    val tile = shown.copyOfRange(left, minOf(left + span, length))
                    if (turn.reversesStoredX) averaged(tile.reversedArray(), sample, turn).reversed() else averaged(tile, sample, turn).toList()
                }
                assertEquals("$length at 1/$sample stored for $turn", whole.toList(), stitched)
            }
        }
    }

    @Test
    fun libjxlIsLeftARoomForWhatItAllocatesOutsideItsLimit() {
        for (budget in listOf(64L shl 20, 96L shl 20, 256L shl 20)) {
            val limit = jxlMemoryLimit(budget)
            assertTrue(limit < budget / 2)
            assertEquals(budget / 16, budget - budget / 2 - limit)
        }
    }
}
