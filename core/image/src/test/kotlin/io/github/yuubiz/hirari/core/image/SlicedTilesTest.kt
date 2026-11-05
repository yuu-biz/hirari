package io.github.yuubiz.hirari.core.image

import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The bitmap the tiles are cut from counts with them against their quarter of the budget (docs/spec.md section 3.7). */
@RunWith(RobolectricTestRunner::class)
class SlicedTilesTest {
    private val decoding = ImageDecoding(budgetBytes = 96L shl 20, parallelism = 1)
    private val image = PixelSize(8192, 8192)
    private val shown = PixelRect(3500, 3500, 4000, 4000)

    @Test
    fun theWholeBitmapTakesTilesOutOfThePlan() {
        val whole = Bitmap.createBitmap(2048, 2048, Bitmap.Config.ARGB_8888)
        val alone = tileLimit(decoding.budgetBytes)
        val sliced = SlicedTiles(whole, image, decoding)

        assertEquals(6, alone)
        assertEquals(2, tileLimit(decoding.budgetBytes, reservedBytes = whole.allocationByteCount.toLong()))
        assertEquals(2, sliced.plan(shown, 1.0).all.size)
    }
}
