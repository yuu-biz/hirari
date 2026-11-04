package io.github.yuubiz.hirari.feature.library

import io.github.yuubiz.hirari.core.image.MemoryBudget
import io.github.yuubiz.hirari.core.model.ThumbSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CoverMemoryBudgetTest {
    private val mb = 1L shl 20

    @Test
    fun `a normal phone retains five dense screens even when a quarter is too small`() {
        val images = MemoryBudget.bytes(256, 8 * 1024 * mb, false)
        val side = coverSizePx(ThumbSize.Small, 3f).toLong()
        val covers = CoverMemoryBudget.bytes(images, false, 3f)
        assertEquals(180 * side * side * 4, covers)
        assertTrue(covers > images / 4)
        assertTrue(covers <= images / 2)
    }

    @Test
    fun `a normal device uses a quarter when that holds five screens`() {
        assertEquals(128 * mb, CoverMemoryBudget.bytes(512 * mb, false, 2f))
    }

    @Test
    fun `small heaps and high density cannot grow covers beyond half the image budget`() {
        assertEquals(48 * mb, CoverMemoryBudget.bytes(96 * mb, false, 4f))
    }

    @Test
    fun `a low RAM phone keeps its original eighth regardless of density`() {
        val images = MemoryBudget.bytes(128, 2 * 1024 * mb, true)
        assertEquals(8 * mb, CoverMemoryBudget.bytes(images, true, 3f))
        assertEquals(images / 8, CoverMemoryBudget.bytes(images, true, 4f))
    }
}
