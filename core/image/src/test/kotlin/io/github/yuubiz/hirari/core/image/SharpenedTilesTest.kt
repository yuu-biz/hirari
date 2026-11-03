package io.github.yuubiz.hirari.core.image

import android.graphics.Bitmap
import io.github.yuubiz.hirari.core.model.PageResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private const val SIDE = 4

/** A dark left half and a light right half, so シャープ moves the pixels beside the edge apart. */
private val EDGE = IntArray(SIDE * SIDE) { if (it % SIDE < SIDE / 2) 0xFF404040.toInt() else 0xFFC0C0C0.toInt() }

private fun Bitmap.pixels(): IntArray = IntArray(width * height).also { getPixels(it, 0, width, 0, 0, width, height) }

/** シャープ of a tile (docs/spec.md sections 3.7 and 4.8). */
@RunWith(RobolectricTestRunner::class)
class SharpenedTilesTest {
    private val decoding = ImageDecoding(budgetBytes = 96L shl 20, parallelism = 1)
    private val key = TileKey(8, 0, 0)

    private class Tiles(decoding: ImageDecoding) : CachedTiles(PixelSize(8192, 32768), decoding) {
        var decodes = 0

        override fun decodeTile(rect: PixelRect, sample: Int, reduction: Int): Bitmap {
            decodes++
            return Bitmap.createBitmap(EDGE, SIDE, SIDE, Bitmap.Config.ARGB_8888)
        }

        override fun release() = Unit
    }

    @Test
    fun aSharpenedTileIsTheTileSharpenedAndIsHeldApartFromThePlainOne() = runBlocking {
        val tiles = Tiles(decoding)
        val plain = (tiles.tile(key) as PageResult.Ready).image
        val sharp = (tiles.tile(key, sharpen = true) as PageResult.Ready).image
        val expected = EDGE.copyOf()
        sharpenRows(SIDE, SIDE, read = { y, row -> EDGE.copyInto(row, 0, y * SIDE, y * SIDE + SIDE) }, write = { y, row -> row.copyInto(expected, y * SIDE) })
        assertArrayEquals(EDGE, plain.pixels())
        assertArrayEquals(expected, sharp.pixels())
        assertArrayEquals(EDGE, (tiles.tile(key) as PageResult.Ready).image.pixels())
        assertArrayEquals(expected, (tiles.tile(key, sharpen = true) as PageResult.Ready).image.pixels())
        assertEquals("each is read once and then held", 2, tiles.decodes)
    }
}
