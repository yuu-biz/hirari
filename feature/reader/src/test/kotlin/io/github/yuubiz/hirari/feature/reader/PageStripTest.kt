package io.github.yuubiz.hirari.feature.reader

import io.github.yuubiz.hirari.core.model.PageShape
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PageStripTest {
    @Test
    fun aCellIs160DpTallAndInLandscapeAtMost35PercentOfTheScreen() {
        assertEquals(160f, PageStrip.cellHeightDp(411f, 914f))
        assertEquals(143.85f, PageStrip.cellHeightDp(914f, 411f), 0.01f)
        assertEquals(160f, PageStrip.cellHeightDp(1280f, 800f))
    }

    @Test
    fun anUprightPageTakesAnUprightCellAndAWidePageUpToTwice() {
        assertEquals(113.12f, PageStrip.cellWidthDp(PageShape.Known(1000, 1500), 160f), 0.01f)
        assertEquals(113.12f, PageStrip.cellWidthDp(PageShape.Known(500, 1500), 160f), 0.01f, "a narrow page is fitted into the upright cell")
        assertEquals(160f, PageStrip.cellWidthDp(PageShape.Known(1500, 1500), 160f), 0.01f)
        assertEquals(226.24f, PageStrip.cellWidthDp(PageShape.Known(3000, 1000), 160f), 0.01f)
        assertEquals(113.12f, PageStrip.cellWidthDp(PageShape.Unknown, 160f), 0.01f)
    }

    @Test
    fun thumbnailsAreDecodedIntoTheWidestCell() {
        assertEquals(622 to 440, PageStrip.decodeSize(2.75f))
    }

    @Test
    fun theMiddlePageIsTheCellNearestTheMiddleOfTheStrip() {
        val cells = listOf(PageStrip.Cell(4, -60, 100), PageStrip.Cell(5, 50, 100), PageStrip.Cell(6, 160, 100))
        assertEquals(5, PageStrip.centre(cells, viewportStart = 0, viewportEnd = 200))
        assertEquals(6, PageStrip.centre(cells, viewportStart = 0, viewportEnd = 400))
        assertNull(PageStrip.centre(emptyList(), 0, 200))
    }

    @Test
    fun thumbnailsAreMadeFromTheMiddleOutwardTheLaterPageFirst() {
        assertEquals(listOf(5, 6, 4, 7, 3, 8), PageStrip.madeOrder(5, (3..8).toList()))
        assertEquals(listOf(0, 1, 2), PageStrip.madeOrder(0, listOf(2, 1, 0)))
    }

    @Test
    fun theCacheKeepsWithinItsBytesDroppingTheLeastRecentlyUsed() {
        val cache = ThumbnailCache<Int>(budget = 10) { it.toLong() }
        cache[1] = 4
        cache[2] = 4
        cache[1]
        cache[3] = 4
        assertEquals(listOf(4, null, 4), listOf(cache[1], cache[2], cache[3]))
        assertEquals(8, cache.heldBytes)
        cache[4] = 30
        assertEquals(listOf(null, null, 30), listOf(cache[1], cache[3], cache[4]), "one larger than the budget is held alone")
        cache[4] = 2
        assertEquals(2, cache.heldBytes)
    }
}
