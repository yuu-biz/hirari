package io.github.yuubiz.hirari.core.image

import android.graphics.Bitmap

/**
 * Reads the margins of [bitmap] through a grayscale copy of [trimAnalysisSize] (docs/spec.md section 4.8), on the
 * calling thread. Cuts come in pixels of [bitmap]. The copy is made as section 5.3 shrinks pages, so specks
 * average out instead of being sampled. [bitmap] must be a software bitmap, as decoded before it becomes a
 * hardware one (section 10, Q2).
 */
fun scanMargins(bitmap: Bitmap): MarginScan {
    require(bitmap.config != Bitmap.Config.HARDWARE) { "margins are read from a software bitmap" }
    val page = PixelSize(bitmap.width, bitmap.height)
    val size = trimAnalysisSize(page)
    val copy = scaledInSteps(bitmap, null, size, recycleSource = false)
    try {
        val argb = IntArray(size.width * size.height)
        copy.getPixels(argb, 0, size.width, 0, 0, size.width, size.height)
        return scanMargins(LumaImage.fromArgb(size.width, size.height, argb), page)
    } finally {
        if (copy !== bitmap) copy.recycle()
    }
}
