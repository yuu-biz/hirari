package io.github.yuubiz.hirari.core.image

import org.junit.Assert.assertEquals
import org.junit.Test

class MemoryBudgetTest {
    private val mb = 1L shl 20
    private val gb = 1L shl 30

    @Test
    fun theBudgetIsOneAndAHalfMemoryClassesWithinItsBounds() {
        assertEquals(384 * mb, MemoryBudget.bytes(memoryClassMb = 256, totalMemBytes = 12 * gb, isLowRamDevice = false))
        assertEquals(96 * mb, MemoryBudget.bytes(memoryClassMb = 48, totalMemBytes = 12 * gb, isLowRamDevice = false))
        assertEquals(512 * mb, MemoryBudget.bytes(memoryClassMb = 512, totalMemBytes = 12 * gb, isLowRamDevice = false))
    }

    @Test
    fun aTenthOfTheTotalMemoryCapsTheBudget() {
        assertEquals(2 * gb / 10, MemoryBudget.bytes(memoryClassMb = 256, totalMemBytes = 2 * gb, isLowRamDevice = false))
    }

    @Test
    fun aLowRamDeviceGetsAtMost64Mb() {
        assertEquals(64 * mb, MemoryBudget.bytes(memoryClassMb = 256, totalMemBytes = 12 * gb, isLowRamDevice = true))
    }

    @Test
    fun parallelismLeavesTwoCoresAndDropsToOneOnASmallBudget() {
        assertEquals(4, MemoryBudget.decodeParallelism(availableProcessors = 8, budgetBytes = 384 * mb))
        assertEquals(2, MemoryBudget.decodeParallelism(availableProcessors = 4, budgetBytes = 384 * mb))
        assertEquals(1, MemoryBudget.decodeParallelism(availableProcessors = 2, budgetBytes = 384 * mb))
        assertEquals(1, MemoryBudget.decodeParallelism(availableProcessors = 8, budgetBytes = 127 * mb))
        assertEquals(4, MemoryBudget.decodeParallelism(availableProcessors = 8, budgetBytes = 128 * mb))
    }
}
