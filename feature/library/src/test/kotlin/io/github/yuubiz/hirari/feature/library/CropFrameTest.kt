package io.github.yuubiz.hirari.feature.library

import io.github.yuubiz.hirari.core.model.CoverCrop
import io.github.yuubiz.hirari.core.model.PageShape
import io.github.yuubiz.hirari.core.model.ReadingDirection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CropFrameTest {
    private val frame = CropFrame(0.2f, 0.3f, 0.6f, 0.8f)

    private fun assertFrame(expected: CropFrame, actual: CropFrame) {
        assertEquals(expected.left, actual.left, 2e-4f)
        assertEquals(expected.top, actual.top, 2e-4f)
        assertEquals(expected.right, actual.right, 2e-4f)
        assertEquals(expected.bottom, actual.bottom, 2e-4f)
    }

    @Test
    fun `dragging inside moves the frame and keeps its size`() {
        assertFrame(CropFrame(0.3f, 0.2f, 0.7f, 0.7f), frame.moved(0.1f, -0.1f))
    }

    @Test
    fun `a frame stops at the edges of the page`() {
        assertFrame(CropFrame(0f, 0f, 0.4f, 0.5f), frame.moved(-1f, -1f))
        assertFrame(CropFrame(0.6f, 0.5f, 1f, 1f), frame.moved(1f, 1f))
    }

    @Test
    fun `an edge moves alone and the opposite edge stays`() {
        assertFrame(CropFrame(0.1f, 0.3f, 0.6f, 0.8f), frame.resized(Grip.Left, -0.1f, 0.5f))
        assertFrame(CropFrame(0.2f, 0.3f, 0.9f, 0.8f), frame.resized(Grip.Right, 0.3f, 0.5f))
        assertFrame(CropFrame(0.2f, 0.4f, 0.6f, 0.8f), frame.resized(Grip.Top, 0.5f, 0.1f))
        assertFrame(CropFrame(0.2f, 0.3f, 0.6f, 0.9f), frame.resized(Grip.Bottom, 0.5f, 0.1f))
    }

    @Test
    fun `a corner moves its two edges`() {
        assertFrame(CropFrame(0.1f, 0.1f, 0.6f, 0.8f), frame.resized(Grip.TopLeft, -0.1f, -0.2f))
        assertFrame(CropFrame(0.2f, 0.3f, 0.7f, 0.9f), frame.resized(Grip.BottomRight, 0.1f, 0.1f))
    }

    @Test
    fun `an edge stops at the page and at the least side from the opposite edge`() {
        assertEquals(0f, frame.resized(Grip.Left, -5f, 0f).left)
        assertEquals(1f, frame.resized(Grip.Right, 5f, 0f).right)
        assertEquals(0.6f - CropFrame.MIN, frame.resized(Grip.Left, 5f, 0f).left, 1e-6f)
        assertEquals(0.3f + CropFrame.MIN, frame.resized(Grip.Bottom, 0f, -5f).bottom, 1e-6f)
    }

    @Test
    fun `no drag makes a frame smaller than the least side or one outside the page`() {
        val moves = listOf(-3f, -0.5f, -0.05f, 0f, 0.05f, 0.4f, 3f)
        for (grip in Grip.entries) for (dx in moves) for (dy in moves) {
            val next = frame.resized(grip, dx, dy)
            assertTrue(next.left >= 0f && next.top >= 0f && next.right <= 1f && next.bottom <= 1f, "$grip $dx $dy $next")
            assertTrue(next.width >= CropFrame.MIN - 1e-6f && next.height >= CropFrame.MIN - 1e-6f, "$grip $dx $dy $next")
            next.toCrop()
        }
    }

    @Test
    fun `a finger takes the corner, the edge or the inside it is on`() {
        val slop = 0.03f
        assertEquals(Grip.TopLeft, frame.gripAt(0.21f, 0.31f, slop, slop))
        assertEquals(Grip.BottomRight, frame.gripAt(0.62f, 0.81f, slop, slop))
        assertEquals(Grip.Left, frame.gripAt(0.19f, 0.55f, slop, slop))
        assertEquals(Grip.Right, frame.gripAt(0.59f, 0.55f, slop, slop))
        assertEquals(Grip.Top, frame.gripAt(0.4f, 0.29f, slop, slop))
        assertEquals(Grip.Bottom, frame.gripAt(0.4f, 0.82f, slop, slop))
        assertEquals(Grip.Inside, frame.gripAt(0.4f, 0.55f, slop, slop))
    }

    @Test
    fun `a finger off the frame takes nothing`() {
        assertNull(frame.gripAt(0.05f, 0.55f, 0.03f, 0.03f))
        assertNull(frame.gripAt(0.4f, 0.95f, 0.03f, 0.03f))
    }

    @Test
    fun `a small frame can still be taken by its inside`() {
        val small = CropFrame(0.4f, 0.4f, 0.5f, 0.5f)
        assertEquals(Grip.Inside, small.gripAt(0.45f, 0.45f, 0.2f, 0.2f))
    }

    @Test
    fun `a frame and its range convert both ways`() {
        val crop = CoverCrop(1234, 2345, 6789, 9876)
        assertEquals(crop, CropFrame.of(crop).toCrop())
        assertEquals(CoverCrop(0, 0, 10_000, 10_000), CropFrame.Whole.toCrop())
    }

    @Test
    fun `every range survives a round trip through a frame`() {
        for (left in 0..9000 step 137) for (width in listOf(1000, 3333, 10_000 - left)) {
            val crop = CoverCrop.of(left, 0, minOf(left + width, 10_000), 10_000)
            assertEquals(crop, CropFrame.of(crop).toCrop())
        }
    }

    @Test
    fun `a tall page opens with the largest frame of the cell proportion in its middle`() {
        val shape = PageShape.Known(1200, 1700)
        val opened = CropFrame.initial(shape, ReadingDirection.RightToLeft)
        val widthPx = opened.width * shape.width
        val heightPx = opened.height * shape.height
        assertEquals(COVER_RATIO, widthPx / heightPx, 0.01f)
        assertFrame(CropFrame((1f - opened.width) / 2, 0f, (1f + opened.width) / 2, 1f), opened)
    }

    @Test
    fun `a page thinner than the cell opens as wide as itself`() {
        val shape = PageShape.Known(600, 1700)
        val opened = CropFrame.initial(shape, ReadingDirection.RightToLeft)
        assertEquals(1f, opened.width, 1e-3f)
        assertEquals(600f / COVER_RATIO / 1700f, opened.height, 1e-3f)
        assertEquals((1f - opened.height) / 2, opened.top, 1e-3f)
    }

    @Test
    fun `the stored range opens on the page it was chosen on and no other`() {
        val shape = PageShape.Known(1200, 1700)
        val crop = CoverCrop(1000, 2000, 6000, 9000)
        val same = openingFrame(storedEntry = 3, storedCrop = crop, entry = 3, shape, ReadingDirection.RightToLeft)
        assertEquals(OpeningFrame(CropFrame.of(crop), ranged = true), same)
        val other = openingFrame(storedEntry = 3, storedCrop = crop, entry = 4, shape, ReadingDirection.RightToLeft)
        assertEquals(OpeningFrame(CropFrame.initial(shape, ReadingDirection.RightToLeft), ranged = false), other)
        val none = openingFrame(storedEntry = 3, storedCrop = null, entry = 3, shape, ReadingDirection.RightToLeft)
        assertEquals(false, none.ranged)
    }

    @Test
    fun `a wide page opens on the half the automatic cover shows`() {
        val shape = PageShape.Known(2400, 1700)
        val right = CropFrame.initial(shape, ReadingDirection.RightToLeft)
        val left = CropFrame.initial(shape, ReadingDirection.LeftToRight)
        assertTrue(right.left >= 0.5f && right.right <= 1f, "$right")
        assertTrue(left.left >= 0f && left.right <= 0.5f, "$left")
        assertEquals((right.left + right.right) / 2, 0.75f, 1e-3f)
        assertEquals((left.left + left.right) / 2, 0.25f, 1e-3f)
        assertEquals(COVER_RATIO, right.width * shape.width / (right.height * shape.height), 0.01f)
    }
}
