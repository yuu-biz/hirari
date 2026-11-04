package io.github.yuubiz.hirari.feature.library

import android.app.ActivityManager
import android.content.Context
import io.github.yuubiz.hirari.core.image.ImageDecoding
import io.github.yuubiz.hirari.core.model.ThumbSize

/** Decoded covers only; the reader keeps its own unchanged page budget (docs/spec.md section 5.4). */
object CoverMemoryBudget {
    fun forDevice(context: Context): Long = bytes(
        ImageDecoding.deviceBudget(context),
        context.getSystemService(ActivityManager::class.java).isLowRamDevice,
        context.resources.displayMetrics.density,
    )

    fun bytes(imageBudget: Long, lowRam: Boolean, density: Float): Long {
        if (lowRam) return imageBudget / 8
        // Six columns by six rows, five screens, at the dense grid's small thumbnail tier.
        // A square is the largest decoded cover at this tier, regardless of the source aspect ratio.
        val side = coverSizePx(ThumbSize.Small, density).toLong()
        val fiveScreens = 5L * 6 * 6 * side * side * 4
        return maxOf(imageBudget / 4, fiveScreens).coerceAtMost(imageBudget / 2)
    }
}
