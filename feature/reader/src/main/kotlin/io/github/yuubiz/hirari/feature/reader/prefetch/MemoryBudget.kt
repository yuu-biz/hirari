package io.github.yuubiz.hirari.feature.reader.prefetch

private const val MB = 1024L * 1024L

/**
 * The memory budget of docs/spec.md section 5.3, in bytes. [memoryClassMb] is
 * `ActivityManager.getMemoryClass()` and [totalMemBytes] is `MemoryInfo.totalMem`.
 */
fun memoryBudget(memoryClassMb: Int, totalMemBytes: Long, lowRamDevice: Boolean): Long {
    val byClass = (memoryClassMb * MB * 3 / 2).coerceIn(96 * MB, 512 * MB)
    val byTotal = minOf(byClass, totalMemBytes / 10)
    return if (lowRamDevice) minOf(byTotal, 64 * MB) else byTotal
}

fun prefetchDepths(lowRamDevice: Boolean): PrefetchDepths = if (lowRamDevice) PrefetchDepths.LowRam else PrefetchDepths()
