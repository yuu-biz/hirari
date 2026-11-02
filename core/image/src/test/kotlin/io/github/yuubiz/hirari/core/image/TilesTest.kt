package io.github.yuubiz.hirari.core.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TilesTest {
    private val tall = PixelSize(800, 50_000)

    @Test
    fun theSampleKeepsTilesAtLeastAsSharpAsTheScreenAndUnderTwiceIt() {
        assertEquals(1, tileSample(1.35))
        assertEquals(1, tileSample(0.6))
        assertEquals(2, tileSample(0.5))
        assertEquals(2, tileSample(0.3))
        assertEquals(8, tileSample(0.1))
    }

    @Test
    fun aFitWidthViewportOfTheTallImageHoldsItsTilesAndOneScreenAboveAndBelow() {
        val scale = 1080 / 800.0
        val viewport = PixelRect(0, 20_000, 800, 20_000 + (1920 / scale).toInt())
        val plan = planTiles(tall, viewport, scale, maxTiles = 100)
        assertEquals(1, plan.sample)
        assertEquals(listOf(TileKey(1, 0, 19), TileKey(1, 0, 20)), plan.visible)
        assertEquals(listOf(21, 18, 22), plan.margin.map { it.row })
        val covered = plan.all.map { tileRect(it, tall) }
        assertTrue(covered.minOf { it.top } <= viewport.top - viewport.height)
        assertTrue(covered.maxOf { it.bottom } >= viewport.bottom + viewport.height)
    }

    @Test
    fun theLimitTrimsTheFarthestMarginTilesButNeverAVisibleOne() {
        val viewport = PixelRect(0, 20_000, 800, 22_500)
        val plan = planTiles(tall, viewport, 1.0, maxTiles = 2)
        assertEquals(3, plan.visible.size)
        assertEquals(emptyList<TileKey>(), plan.margin)
        val wider = planTiles(tall, viewport, 1.0, maxTiles = 4)
        assertEquals(listOf(TileKey(1, 0, 22)), wider.margin.take(1))
        assertEquals(1, wider.margin.size)
    }

    @Test
    fun tilesAtTheEdgesStopAtTheImage() {
        val last = tileRect(TileKey(1, 0, 48), tall)
        assertEquals(PixelRect(0, 49_152, 800, 50_000), last)
        assertEquals(PixelRect(0, 0, 800, 2048), tileRect(TileKey(2, 0, 0), tall))
    }

    @Test
    fun aViewportOutsideTheImagePlansNothing() {
        val plan = planTiles(tall, PixelRect(900, 0, 1800, 1000), 1.0, maxTiles = 10)
        assertTrue(plan.all.isEmpty())
    }

    @Test
    fun theOneToThreeRuleTilesOnlyImagesNarrowerThanIt() {
        assertTrue(isElongated(800, 50_000))
        assertTrue(isElongated(50_000, 800))
        assertFalse(isElongated(1000, 3000))
        assertFalse(isElongated(1200, 1800))
    }

    /** Where the stored pixel at [x], [y] of [source] shows, by the EXIF definition of each orientation. */
    private fun Orientation.shownPixel(x: Int, y: Int, source: PixelSize): Pair<Int, Int> {
        val w = source.width
        val h = source.height
        return when (this) {
            Orientation.Normal -> x to y
            Orientation.FlipHorizontal -> w - 1 - x to y
            Orientation.Rotate180 -> w - 1 - x to h - 1 - y
            Orientation.FlipVertical -> x to h - 1 - y
            Orientation.Transpose -> y to x
            Orientation.Rotate90 -> h - 1 - y to x
            Orientation.Transverse -> h - 1 - y to w - 1 - x
            Orientation.Rotate270 -> y to w - 1 - x
        }
    }

    @Test
    fun eachOrientationMapsEveryShownPixelBackToTheStoredOne() {
        val source = PixelSize(3, 5)
        for (orientation in Orientation.entries) {
            val shown = orientation.shown(source)
            for (y in 0 until source.height) for (x in 0 until source.width) {
                val (sx, sy) = orientation.shownPixel(x, y, source)
                assertTrue("$orientation shows $x,$y outside", sx in 0 until shown.width && sy in 0 until shown.height)
                assertEquals("$orientation at $x,$y", PixelRect(x, y, x + 1, y + 1), orientation.toStored(PixelRect(sx, sy, sx + 1, sy + 1), source))
            }
        }
    }

    @Test
    fun aRectangleMapsToTheBoundsOfItsPixels() {
        val source = PixelSize(4000, 3000)
        val stored = Orientation.Rotate90.toStored(PixelRect(0, 0, 1024, 1024), source)
        assertEquals(PixelRect(0, 3000 - 1024, 1024, 3000), stored)
        assertEquals(PixelSize(3000, 4000), Orientation.Rotate90.shown(source))
    }

    @Test
    fun unknownExifValuesShowTheImageAsStored() {
        assertEquals(Orientation.Normal, Orientation.of(0))
        assertEquals(Orientation.Normal, Orientation.of(9))
        assertEquals(Orientation.Rotate270, Orientation.of(8))
    }

    @Test
    fun aQuarterOfTheBudgetBoundsTheTiles() {
        assertEquals(18, tileLimit(288L shl 20))
        assertEquals(1, tileLimit(1L shl 20))
    }

    private val roomy = 512L shl 20
    private val full = PixelSize(1024 * 8, 1024 * 8)

    @Test
    fun aTileOfAFormatThatPicksIsReadWholeAndAveragedAfterwards() {
        assertEquals(TileRead(1, PixelSize(1024, 1024)), planTileRead(full, 8, averagingLimit = 1, budgetBytes = roomy))
        assertEquals(TileRead(1, PixelSize(1024, 1024)), planTileRead(PixelSize(2048, 2048), 2, averagingLimit = 1, budgetBytes = roomy))
    }

    @Test
    fun aTileIsReadAtTheSampleWhereTheDecoderStillAverages() {
        assertEquals(8, planTileRead(full, 16, averagingLimit = 8, budgetBytes = roomy).decodeSample)
        assertEquals(4, planTileRead(full, 4, averagingLimit = 8, budgetBytes = roomy).decodeSample)
        assertEquals(16, planTileRead(PixelSize(1024 * 16, 1024 * 16), 16, averagingLimit = Int.MAX_VALUE, budgetBytes = roomy).decodeSample)
    }

    @Test
    fun aTileTooBigForTheBudgetIsReadAtTheSmallestSampleThatFitsHalfOfIt() {
        val read = planTileRead(full, 8, averagingLimit = 1, budgetBytes = 96L shl 20)
        assertEquals(4, read.decodeSample)
        assertEquals(PixelSize(1024, 1024), read.output)
    }

    @Test
    fun anEdgeTileRoundsItsOutputUpAsTheDecodersDo() {
        assertEquals(PixelSize(125, 42), planTileRead(PixelSize(1000, 333), 8, averagingLimit = 1, budgetBytes = roomy).output)
        assertEquals(PixelSize(1, 1), planTileRead(PixelSize(3, 2), 8, averagingLimit = 1, budgetBytes = roomy).output)
    }

    @Test
    fun aTileAtSampleOneIsReadAsItIs() {
        assertEquals(TileRead(1, PixelSize(1024, 700)), planTileRead(PixelSize(1024, 700), 1, averagingLimit = 1, budgetBytes = roomy))
    }
}
