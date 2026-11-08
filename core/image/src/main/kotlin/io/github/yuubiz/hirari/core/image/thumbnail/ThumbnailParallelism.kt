package io.github.yuubiz.hirari.core.image.thumbnail

object ThumbnailParallelism {
    fun cap(deviceBudgetBytes: Long): Int =
        (deviceBudgetBytes / (64L shl 20)).coerceIn(1, Int.MAX_VALUE.toLong()).toInt()

    fun effective(chosen: Int, deviceBudgetBytes: Long): Int = chosen.coerceIn(1, cap(deviceBudgetBytes))

    internal fun perDecodeBudget(deviceBudgetBytes: Long, n: Int): Long =
        deviceBudgetBytes / if (deviceBudgetBytes < (128L shl 20)) 2 else n
}
