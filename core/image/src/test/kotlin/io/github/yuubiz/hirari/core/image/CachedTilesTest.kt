package io.github.yuubiz.hirari.core.image

import android.graphics.Bitmap
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** A tile that runs out of memory is read once more at twice the reduction (docs/spec.md section 6.1). */
class CachedTilesTest {
    private val decoding = ImageDecoding(budgetBytes = 96L shl 20, parallelism = 1)
    private val key = TileKey(8, 0, 0)

    private class Tiles(decoding: ImageDecoding, private val read: (reduction: Int) -> Bitmap?) : CachedTiles(PixelSize(8192, 32768), decoding) {
        val reductions = mutableListOf<Int>()

        override fun decodeTile(rect: PixelRect, sample: Int, reduction: Int): Bitmap? {
            reductions += reduction
            return read(reduction)
        }

        override fun release() = Unit
    }

    @Test
    fun runningOutOfMemoryReadsTheTileOnceMoreAtTwiceTheReduction() = runBlocking {
        val tiles = Tiles(decoding) { reduction -> if (reduction == 1) throw OutOfMemoryError() else null }
        val result = tiles.tile(key)
        assertEquals(listOf(1, 2), tiles.reductions)
        assertEquals("the second read's own result", corrupt, result)
    }

    @Test
    fun runningOutOfMemoryTwiceIsARetryableReadError() = runBlocking {
        val tiles = Tiles(decoding) { _ -> throw OutOfMemoryError() }
        val result = tiles.tile(key)
        assertEquals(listOf(1, 2), tiles.reductions)
        assertEquals(readError, result)
    }
}
