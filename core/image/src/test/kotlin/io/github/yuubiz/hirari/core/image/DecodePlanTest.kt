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

    @Test
    fun downscalingHalvesUntilTheLastStepIsUnderTwoTimes() {
        assertEquals(
            listOf(PixelSize(2000, 3000), PixelSize(1000, 1500), PixelSize(700, 1050)),
            downscaleSteps(PixelSize(4000, 6000), PixelSize(700, 1050)),
        )
        assertEquals(listOf(PixelSize(1000, 1500)), downscaleSteps(PixelSize(2000, 3000), PixelSize(1000, 1500)))
        assertEquals(emptyList<PixelSize>(), downscaleSteps(PixelSize(100, 100), PixelSize(100, 100)))
        assertEquals(listOf(PixelSize(90, 100)), downscaleSteps(PixelSize(100, 100), PixelSize(90, 100)))
    }
}
