package io.github.yuubiz.hirari.core.image

import android.graphics.Bitmap
import android.graphics.Color
import io.github.yuubiz.hirari.core.model.PageResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * Tiles read far below the screen's resolution average the image as the whole-image path does, so a screentone
 * of dots does not drop out or beat into moire (docs/spec.md sections 3.7 and 5.3). The image is 2 x 2 dots on
 * a 4-pixel pitch, a quarter black, so every 8 x 8 block of it averages to the same gray.
 */
class TileScalingTest {
    private val decoding = ImageDecoding(budgetBytes = 256L shl 20, parallelism = 1)
    private val width = 640
    private val height = 3200
    private val sample = 8
    private val shown = PixelSize(width / sample, height / sample)
    private val averaged = 191

    private fun dot(x: Int, y: Int) = x % 4 < 2 && y % 4 < 2

    private fun pixels() = IntArray(width * height) { i -> if (dot(i % width, i / width)) Color.BLACK else Color.WHITE }

    private fun png(): ByteArray {
        val bitmap = Bitmap.createBitmap(pixels(), width, height, Bitmap.Config.ARGB_8888)
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    private fun bmp(): ByteArray {
        val data = ByteBuffer.allocate(54 + width * height * 4).order(ByteOrder.LITTLE_ENDIAN)
        data.put('B'.code.toByte()).put('M'.code.toByte()).putInt(data.capacity()).putInt(0).putInt(54)
        data.putInt(40).putInt(width).putInt(-height).putShort(1).putShort(32).putInt(0).putInt(width * height * 4)
        data.putInt(2835).putInt(2835).putInt(0).putInt(0)
        pixels().forEach(data::putInt)
        return data.array()
    }

    private fun tiledTile(bytes: ByteArray): Bitmap {
        val page = decodePage(bytes, shown, decoding, Tiling.AsPlanned) { true }
        val tiled = (page as PageResult.Ready).image as PreparedImage.Tiled
        try {
            val tile = runBlocking { tiled.source.tile(TileKey(sample, 0, 0)) }
            return (tile as PageResult.Ready).image
        } finally {
            tiled.source.close()
        }
    }

    private fun wholePage(bytes: ByteArray): Bitmap =
        ((decodePage(bytes, shown, decoding, Tiling.Never) { true } as PageResult.Ready).image as PreparedImage.Whole).bitmap

    private fun assertAveraged(tile: Bitmap) {
        assertEquals(shown.width, tile.width)
        assertEquals(shown.height, tile.height)
        val out = IntArray(tile.width * tile.height)
        tile.getPixels(out, 0, tile.width, 0, 0, tile.width, tile.height)
        val worst = out.maxOf { abs(Color.red(it) - averaged) }
        assertTrue("the worst pixel is $worst gray levels from the average", worst <= 3)
    }

    private fun assertSameAs(whole: Bitmap, tile: Bitmap) {
        assertEquals(whole.width, tile.width)
        assertEquals(whole.height, tile.height)
        val a = IntArray(whole.width * whole.height).also { whole.getPixels(it, 0, whole.width, 0, 0, whole.width, whole.height) }
        val b = IntArray(tile.width * tile.height).also { tile.getPixels(it, 0, tile.width, 0, 0, tile.width, tile.height) }
        val worst = a.indices.maxOf { abs(Color.red(a[it]) - Color.red(b[it])) }
        assertTrue("the tile differs from the whole-image path by up to $worst gray levels", worst <= 2)
    }

    @Test
    fun aRegionTileOfAPngAveragesTheDotsLikeTheWholeImage() {
        val bytes = png()
        val tile = tiledTile(bytes)
        assertAveraged(tile)
        assertSameAs(wholePage(bytes), tile)
    }

    @Test
    fun aSlicedTileOfABmpAveragesTheDotsLikeTheWholeImage() {
        val bytes = bmp()
        val tile = tiledTile(bytes)
        assertAveraged(tile)
        assertSameAs(wholePage(bytes), tile)
    }
}
