package io.github.yuubiz.hirari.core.image

import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.coroutines.cancellation.CancellationException

/** A decode that is no longer wanted stops at the next step of its scaling (docs/spec.md section 4.4). */
@RunWith(RobolectricTestRunner::class)
class ScalingCancellationTest {
    @Test
    fun theScalingStopsBeforeTheNextStepOnceTheDecodeIsNoLongerWanted() {
        val source = Bitmap.createBitmap(1024, 1024, Bitmap.Config.ARGB_8888)
        var checks = 0
        try {
            scaledInSteps(source, null, PixelSize(100, 100), recycleSource = true, isActive = { ++checks < 2 })
            fail("the scaling went on")
        } catch (_: CancellationException) {
        }
        assertEquals("one step, then the check that stopped it", 2, checks)
        assertTrue(source.isRecycled)
    }

    @Test
    fun aJpegXlPageCancelledAfterItsHeaderIsNeverHandedOn() {
        val header = JxlHeader(4000, 4000, hasAnimation = false)
        val decoding = ImageDecoding(budgetBytes = 64L shl 20, parallelism = 1)
        var decoded: Bitmap? = null
        var checks = 0
        try {
            prepareJxlPage(header, PixelSize(300, 300), decoding, Tiling.Never, { ++checks < 2 }) { sample ->
                Bitmap.createBitmap(4000 / sample, 4000 / sample, Bitmap.Config.ARGB_8888).let {
                    decoded = it
                    JxlDecodeResult.Decoded(it, peakNativeBytes = 0)
                }
            }
            fail("the page was prepared")
        } catch (_: CancellationException) {
        }
        assertTrue("the decoded pixels are given back", decoded!!.isRecycled)
    }
}
