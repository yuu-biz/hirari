package io.github.yuubiz.hirari.core.image.thumbnail

import org.junit.Assert.assertEquals
import org.junit.Test

class ThumbnailParallelismTest {
    @Test
    fun deviceBudgetCapsTheChosenParallelism() {
        for ((mb, cap) in listOf(96 to 1, 128 to 2, 256 to 4, 512 to 8)) {
            val budget = mb.toLong() shl 20
            assertEquals(cap, ThumbnailParallelism.cap(budget))
            assertEquals(cap, ThumbnailParallelism.effective(8, budget))
            assertEquals(minOf(2, cap), ThumbnailParallelism.effective(2, budget))
        }
        assertEquals(1, ThumbnailParallelism.cap(0))
        assertEquals(1, ThumbnailParallelism.cap((128L shl 20) - 1))
        assertEquals(3, ThumbnailParallelism.cap((256L shl 20) - 1))
    }

    @Test
    fun decodesShareTheDeviceBudgetExceptOnLowMemoryDevices() {
        for (n in listOf(2, 3, 4, 6, 8)) {
            assertEquals((512L shl 20) / n, ThumbnailParallelism.perDecodeBudget(512L shl 20, n))
        }
        assertEquals(48L shl 20, ThumbnailParallelism.perDecodeBudget(96L shl 20, 1))
    }
}
