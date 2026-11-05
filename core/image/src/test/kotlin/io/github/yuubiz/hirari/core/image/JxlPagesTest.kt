package io.github.yuubiz.hirari.core.image

import android.graphics.Bitmap
import io.github.yuubiz.hirari.core.model.PageResult
import io.github.yuubiz.hirari.core.model.PageShape
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.coroutines.cancellation.CancellationException

/** Page preparation with a sampled bitmap in place of JNI; native routing is covered by JxlDecoderTest. */
@RunWith(RobolectricTestRunner::class)
class JxlPagesTest {
    private val tall = JxlHeader(800, 50_000, hasAnimation = false)
    private val target = PixelSize(1080, 67_500)
    private val decoding = ImageDecoding(budgetBytes = 64L shl 20, parallelism = 1)

    private fun decoded(header: JxlHeader, sample: Int): JxlDecodeResult.Decoded {
        val bitmap = Bitmap.createBitmap(sampled(header.width, sample), sampled(header.height, sample), Bitmap.Config.ARGB_8888)
        assertTrue("sampled output fits half the budget", bitmap.allocationByteCount.toLong() <= decoding.budgetBytes / 2)
        return JxlDecodeResult.Decoded(bitmap, peakNativeBytes = 0)
    }

    @Test
    fun aTallPageGivesTilesBeyondTheWholeBitmapSideLimitWithinTheBudget() = runBlocking {
        for (tiling in listOf(Tiling.AsPlanned, Tiling.Always)) {
            var sample = 0
            val result = prepareJxlPage(tall, target, decoding, tiling, { true }) {
                sample = it
                decoded(tall, it)
            } as PageResult.Ready
            val image = result.image as PreparedImage.Tiled
            image.source.use { source ->
                assertEquals(PageShape.Known(800, 50_000), image.shape)
                assertEquals(PixelSize(800, 50_000), source.size)
                assertEquals("whole-image sampling must not keep the 4096 side cap", 4, sample)
                val tile = (source.tile(TileKey(1, 0, 20)) as PageResult.Ready).image
                try {
                    assertEquals(800 to TILE_SIDE, tile.width to tile.height)
                    assertTrue(tile.allocationByteCount.toLong() <= decoding.budgetBytes / 4)
                } finally {
                    tile.recycle()
                }
            }
        }
    }

    @Test
    fun zoomRequestsTilesEvenForAnOrdinaryPage() {
        val header = JxlHeader(256, 128, hasAnimation = false)
        val result = prepareJxlPage(header, PixelSize(64, 64), decoding, Tiling.Always, { true }) {
            assertEquals("tiles keep original pixels when the whole fits", 1, it)
            decoded(header, it)
        } as PageResult.Ready
        (result.image as PreparedImage.Tiled).source.close()
    }

    @Test
    fun aCallerWithoutTilesStillGetsACappedWhole() {
        val result = prepareJxlPage(tall, target, decoding, Tiling.Never, { true }) { decoded(tall, it) } as PageResult.Ready
        val image = result.image as PreparedImage.Whole
        try {
            assertEquals(4096, image.bitmap.height)
            assertTrue(image.bitmap.allocationByteCount.toLong() <= decoding.budgetBytes / 2)
        } finally {
            image.bitmap.recycle()
        }
    }

    @Test
    fun tilesAreDeclinedWithoutDecodingWhenTheyWouldBeNoSharper() {
        val header = JxlHeader(6000, 4000, hasAnimation = false)
        val result = prepareJxlPage(header, PixelSize(4096, 4096), decoding, Tiling.Always, { true }) {
            throw AssertionError("no pixel decode for an untiled page")
        }
        assertEquals(PageResult.Ready(PreparedImage.Untiled(PageShape.Known(6000, 4000))), result)
    }

    @Test
    fun aTileRequestRetriesOnceAtTwiceTheSampleAfterRunningOutOfMemory() {
        val samples = mutableListOf<Int>()
        val result = prepareJxlPage(tall, target, decoding, Tiling.Always, { true }) {
            samples += it
            if (samples.size == 1) throw OutOfMemoryError("first decode")
            decoded(tall, it)
        } as PageResult.Ready
        (result.image as PreparedImage.Tiled).source.close()
        assertEquals(listOf(4, 8), samples)
    }

    @Test(expected = CancellationException::class)
    fun aTileRequestChecksCancellationBeforeDecoding() {
        prepareJxlPage(tall, target, decoding, Tiling.Always, { false }) {
            throw AssertionError("cancelled before decoding pixels")
        }
    }
}
