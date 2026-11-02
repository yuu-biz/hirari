package io.github.yuubiz.hirari.core.image

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import io.github.yuubiz.hirari.core.model.PageShape
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/** A page ready to draw (docs/spec.md section 2.4). */
sealed interface PreparedImage {
    /** The size of the original image after EXIF rotation, not of the bitmap. */
    val shape: PageShape.Known

    data class Whole(val bitmap: Bitmap, override val shape: PageShape.Known) : PreparedImage

    /** Whoever holds it closes [source]. */
    data class Tiled(val source: TileSource, override val shape: PageShape.Known) : PreparedImage
}

/** Whether a page may come as tiles. Callers that draw only whole bitmaps keep [Never]. */
enum class Tiling {
    /** Always one bitmap, scaled down to fit the limits of docs/spec.md section 3.7. */
    Never,

    /** Tiles where section 3.7 plans them: images narrower than 1:3. */
    AsPlanned,
}

/**
 * What every decode in the process shares: the memory budget of docs/spec.md section 5.3 and the decode
 * dispatcher of section 8.3. Make one per process.
 */
class ImageDecoding(val budgetBytes: Long, parallelism: Int) {
    val dispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(parallelism)

    companion object {
        /** docs/spec.md section 3.7. */
        const val DECODE_TIMEOUT_MILLIS: Long = 10_000

        fun forDevice(context: Context): ImageDecoding {
            val activityManager = context.getSystemService(ActivityManager::class.java)
            val memory = ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo)
            val budget = MemoryBudget.bytes(activityManager.memoryClass, memory.totalMem, activityManager.isLowRamDevice)
            return ImageDecoding(budget, MemoryBudget.decodeParallelism(Runtime.getRuntime().availableProcessors(), budget))
        }
    }
}
