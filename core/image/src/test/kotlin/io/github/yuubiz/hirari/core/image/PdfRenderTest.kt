package io.github.yuubiz.hirari.core.image

import io.github.yuubiz.hirari.core.model.PageResult
import io.github.yuubiz.hirari.core.model.PageShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfRenderTest {
    private val page = PageShape.Known(595, 842)
    private val target = PixelSize(1080, 1920)
    private val roomy = 512L shl 20

    @Test
    fun aPageIsDrawnOnceAtTheSizeThatFitsTheTarget() {
        val sizes = mutableListOf<PixelSize>()
        val result = drawPdfPage(page, target, roomy) { size ->
            sizes += size
            PageResult.Ready(size)
        }
        assertEquals(listOf(PixelSize(1080, 1528)), sizes)
        assertEquals(PageResult.Ready(PixelSize(1080, 1528)), result)
    }

    @Test
    fun aPageThatWouldTakeMoreThanHalfTheBudgetIsDrawnSmaller() {
        val budget = 4L shl 20
        val sizes = mutableListOf<PixelSize>()
        drawPdfPage(page, target, budget) { size ->
            sizes += size
            PageResult.Ready(size)
        }
        val size = sizes.single()
        assertTrue("$size", size.width.toLong() * size.height * 4 <= budget / 2 * 1.01)
        assertTrue("$size", size.width < 1080)
    }

    @Test
    fun runningOutOfMemoryDrawsOnceMoreAtHalfTheSize() {
        val sizes = mutableListOf<PixelSize>()
        val result = drawPdfPage(page, target, roomy) { size ->
            sizes += size
            if (sizes.size == 1) throw OutOfMemoryError()
            PageResult.Ready(size)
        }
        assertEquals(listOf(PixelSize(1080, 1528), PixelSize(540, 764)), sizes)
        assertEquals(PageResult.Ready(PixelSize(540, 764)), result)
    }

    @Test
    fun runningOutOfMemoryTwiceIsARetryableReadErrorAfterTwoTries() {
        var tries = 0
        val result = drawPdfPage<Unit>(page, target, roomy) { _ ->
            tries++
            throw OutOfMemoryError()
        }
        assertEquals(2, tries)
        assertEquals(readError, result)
    }

    @Test
    fun theSecondSizeNeverDropsBelowOnePixel() {
        val sizes = mutableListOf<PixelSize>()
        drawPdfPage(PageShape.Known(1, 1), PixelSize(1, 1), roomy) { size ->
            sizes += size
            if (sizes.size == 1) throw OutOfMemoryError()
            PageResult.Ready(size)
        }
        assertEquals(listOf(PixelSize(1, 1), PixelSize(1, 1)), sizes)
    }
}
