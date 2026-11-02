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

    /** A PNG of [rows] x [columns] whose row y is the color [rowColor] gives it. */
    private fun pngOfRows(columns: Int, rows: Int, rowColor: (Int) -> Int): ByteArray =
        compressed(Bitmap.createBitmap(columns, rows, Bitmap.Config.ARGB_8888).apply { for (y in 0 until rows) setPixels(IntArray(columns) { rowColor(y) }, 0, columns, 0, y, columns, 1) }, Bitmap.CompressFormat.PNG)

    private fun compressed(bitmap: Bitmap, format: Bitmap.CompressFormat): ByteArray =
        ByteArrayOutputStream().also { bitmap.compress(format, 100, it) }.toByteArray().also { bitmap.recycle() }

    /** [jpeg] with an EXIF segment that holds only the orientation [exif]. */
    private fun withOrientation(jpeg: ByteArray, exif: Int): ByteArray {
        val tiff = ByteBuffer.allocate(26).order(ByteOrder.BIG_ENDIAN)
            .put('M'.code.toByte()).put('M'.code.toByte()).putShort(42).putInt(8)
            .putShort(1).putShort(0x0112).putShort(3).putInt(1).putShort(exif.toShort()).putShort(0)
            .putInt(0)
        val payload = "Exif\u0000\u0000".toByteArray(Charsets.ISO_8859_1) + tiff.array()
        val segment = ByteBuffer.allocate(4 + payload.size).put(0xFF.toByte()).put(0xE1.toByte()).putShort((2 + payload.size).toShort()).put(payload).array()
        return jpeg.copyOfRange(0, 2) + segment + jpeg.copyOfRange(2, jpeg.size)
    }

    /** The tiles of the one column of tiles of [bytes] at [sample], stitched top to bottom. */
    private fun stitchedColumn(bytes: ByteArray, sample: Int, target: PixelSize): Bitmap {
        val page = decodePage(bytes, target, decoding, Tiling.AsPlanned) { true }
        val tiled = (page as PageResult.Ready).image as PreparedImage.Tiled
        try {
            val rows = (tiled.source.size.height + TILE_SIDE * sample - 1) / (TILE_SIDE * sample)
            val tiles = (0 until rows).map { row -> (runBlocking { tiled.source.tile(TileKey(sample, 0, row)) } as PageResult.Ready).image }
            val out = Bitmap.createBitmap(tiles.first().width, tiles.sumOf { it.height }, Bitmap.Config.ARGB_8888)
            var top = 0
            for (tile in tiles) {
                assertEquals(out.width, tile.width)
                val pixels = IntArray(tile.width * tile.height).also { tile.getPixels(it, 0, tile.width, 0, 0, tile.width, tile.height) }
                out.setPixels(pixels, 0, tile.width, 0, top, tile.width, tile.height)
                top += tile.height
            }
            return out
        } finally {
            tiled.source.close()
        }
    }

    private fun wholePage(bytes: ByteArray, target: PixelSize): Bitmap =
        ((decodePage(bytes, target, decoding, Tiling.Never) { true } as PageResult.Ready).image as PreparedImage.Whole).bitmap

    /** That every row of [bitmap] but the last is the gray of black and white averaged. */
    private fun assertGrayAboveTheLastRow(bitmap: Bitmap) {
        val out = IntArray(bitmap.width * (bitmap.height - 1)).also { bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height - 1) }
        val row = out.indices.maxBy { abs(Color.red(out[it]) - 127.5) } / bitmap.width
        val worst = out.maxOf { abs(Color.red(it) - 127.5) }
        assertTrue("row $row of ${bitmap.height} is ${worst + 127.5}", worst <= 3)
    }

    @Test
    fun theOddEdgeTileOfAlternateLinesAveragesAndStitchesOntoTheWholeImage() {
        val bytes = pngOfRows(640, 8517) { y -> if (y % 2 == 0) Color.BLACK else Color.WHITE }
        val target = PixelSize(80, 1065)
        val stitched = stitchedColumn(bytes, sample, target)
        assertGrayAboveTheLastRow(stitched)
        assertSameAs(wholePage(bytes, target), stitched)
    }

    @Test
    fun theTilesOfARotatedJpegLineUpWithTheImageAsShown() {
        val shownHeight = 8 * 2089
        val shownWidth = width
        val row = IntArray(shownHeight) { x -> if ((shownHeight - 1 - x) / 8 % 2 == 0) Color.BLACK else Color.WHITE }
        val stored = Bitmap.createBitmap(shownHeight, shownWidth, Bitmap.Config.ARGB_8888)
        for (y in 0 until shownWidth) stored.setPixels(row, 0, shownHeight, 0, y, shownHeight, 1)
        val bytes = withOrientation(compressed(stored, Bitmap.CompressFormat.JPEG), Orientation.Rotate270.exif)
        val target = PixelSize(width / 16, 1045)
        val stitched = stitchedColumn(bytes, 16, target)
        assertEquals(target, PixelSize(stitched.width, stitched.height))
        assertGrayAboveTheLastRow(stitched)
        assertSameAs(wholePage(bytes, target), stitched)
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
