package io.github.yuubiz.hirari.core.model

import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class CoverCropTest {
    @Test
    fun `a range inside the page stays as it is`() {
        assertEquals(CoverCrop(1000, 2000, 6000, 9000), CoverCrop.of(1000, 2000, 6000, 9000))
    }

    @Test
    fun `edges given the wrong way round are put in order`() {
        assertEquals(CoverCrop(1000, 2000, 6000, 9000), CoverCrop.of(6000, 9000, 1000, 2000))
    }

    @Test
    fun `edges outside the page are pulled to its sides`() {
        assertEquals(CoverCrop(0, 0, 10_000, 10_000), CoverCrop.of(-500, -1, 12_000, 10_001))
    }

    @Test
    fun `a side shorter than the least grows around its middle`() {
        assertEquals(CoverCrop(4500, 2000, 5500, 6000), CoverCrop.of(5000, 2000, 5000, 6000))
        assertEquals(CoverCrop(2000, 4600, 6000, 5600), CoverCrop.of(2000, 5100, 6000, 5100))
    }

    @Test
    fun `a side that has to grow slides back inside when it was at the edge`() {
        assertEquals(CoverCrop(0, 0, 1000, 6000), CoverCrop.of(0, 0, 100, 6000))
        assertEquals(CoverCrop(9000, 0, 10_000, 6000), CoverCrop.of(9950, 0, 10_000, 6000))
        assertEquals(CoverCrop(0, 9000, 6000, 10_000), CoverCrop.of(0, 10_000, 6000, 10_000))
    }

    @Test
    fun `fractions round to the nearest ten thousandth`() {
        assertEquals(CoverCrop(1235, 0, 5000, 10_000), CoverCrop.ofFractions(0.12345f, 0f, 0.5f, 1f))
    }

    @Test
    fun `whatever the edges the result is a valid range`(): Unit = runBlocking<Unit> {
        val edge = Arb.int(-20_000, 30_000)
        checkAll(edge, edge, edge, edge) { a, b, c, d ->
            val crop = CoverCrop.of(a, b, c, d)
            assert(crop.left in 0..CoverCrop.SCALE && crop.right in 0..CoverCrop.SCALE)
            assert(crop.width >= CoverCrop.MIN_SIDE && crop.height >= CoverCrop.MIN_SIDE)
            assertEquals(crop, CoverCrop.of(crop.left, crop.top, crop.right, crop.bottom))
        }
    }

    @Test
    fun `the constructor refuses what of would have fixed`() {
        assertFailsWith<IllegalArgumentException> { CoverCrop(6000, 0, 1000, 5000) }
        assertFailsWith<IllegalArgumentException> { CoverCrop(0, 0, 10_001, 5000) }
        assertFailsWith<IllegalArgumentException> { CoverCrop(0, 0, 999, 5000) }
    }

    @Test
    fun `a range is written and read back as the same text`() {
        val crop = CoverCrop(1000, 2000, 6000, 9000)
        assertEquals("1000,2000,6000,9000", crop.encode())
        assertEquals(crop, CoverCrop.decode(crop.encode()))
    }

    @Test
    fun `text that is not a range reads as none`() {
        for (text in listOf("", "1,2,3", "1000,2000,6000,9000,1", "a,b,c,d", "6000,0,1000,5000", "0,0,10001,5000", "0,0,500,5000")) {
            assertNull(CoverCrop.decode(text), text)
        }
    }
}
