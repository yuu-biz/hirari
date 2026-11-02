package io.github.yuubiz.hirari.core.image

/** The memory budget of docs/spec.md section 5.3 and the decode parallelism of section 8.3. */
object MemoryBudget {
    private const val MB = 1L shl 20

    /** [memoryClassMb] is `ActivityManager.getMemoryClass`, [totalMemBytes] is `MemoryInfo.totalMem`. */
    fun bytes(memoryClassMb: Int, totalMemBytes: Long, isLowRamDevice: Boolean): Long {
        var budget = (memoryClassMb * MB * 3 / 2).coerceIn(96 * MB, 512 * MB)
        budget = minOf(budget, totalMemBytes / 10)
        if (isLowRamDevice) budget = minOf(budget, 64 * MB)
        return budget
    }

    fun decodeParallelism(availableProcessors: Int, budgetBytes: Long): Int =
        if (budgetBytes < 128 * MB) 1 else (availableProcessors - 2).coerceIn(1, 4)
}
