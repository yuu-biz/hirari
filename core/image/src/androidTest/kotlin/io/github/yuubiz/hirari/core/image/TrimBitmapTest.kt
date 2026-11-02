package io.github.yuubiz.hirari.core.image

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import io.github.yuubiz.hirari.core.model.TrimStrength
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The margins of a decoded bitmap, read through the analysis copy of docs/spec.md section 4.8. */
class TrimBitmapTest {
    private fun page(width: Int, height: Int, content: Rect): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            val canvas = Canvas(this)
            canvas.drawColor(Color.rgb(240, 240, 240))
            val ink = Paint().apply { color = Color.rgb(20, 20, 20) }
            canvas.drawRect(content, ink)
            canvas.drawRect(Rect(content.left + 8, content.top + 8, content.right - 8, content.bottom - 8), Paint().apply { color = Color.WHITE })
            canvas.drawRect(Rect(content.left + 40, content.top + 40, content.centerX(), content.centerY()), ink)
        }

    private fun solid(width: Int, height: Int, content: Rect, contrast: Int): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            val canvas = Canvas(this)
            canvas.drawColor(Color.rgb(240, 240, 240))
            val ink = 240 - contrast
            canvas.drawRect(content, Paint().apply { color = Color.rgb(ink, ink, ink) })
        }

    /** [kept] holds all of [content] and leaves no more than three lines of the analysis copy around it. */
    private fun assertKeeps(content: PixelRect, kept: PixelRect, page: PixelSize, message: String) {
        val size = trimAnalysisSize(page)
        val across = 3 * ((page.width + size.width - 1) / size.width)
        val down = 3 * ((page.height + size.height - 1) / size.height)
        assertTrue("$message: $kept", kept.left in content.left - across..content.left && kept.right in content.right..content.right + across)
        assertTrue("$message: $kept", kept.top in content.top - down..content.top && kept.bottom in content.bottom..content.bottom + down)
    }

    @Test
    fun aPageScaledByAPowerOfTwoKeepsTwoLinesOfTheCopyAroundItsContent() {
        val bitmap = page(1448, 2048, Rect(120, 160, 1336, 1872))
        val micros = List(10) {
            val start = SystemClock.elapsedRealtimeNanos()
            scanMargins(bitmap)
            (SystemClock.elapsedRealtimeNanos() - start) / 1_000
        }
        Log.i("TrimBitmapTest", "1448x2048 read in $micros us, first call first")
        val scan = scanMargins(bitmap)
        assertEquals(PixelSize(1448, 2048), scan.page)
        assertEquals(PixelRect(112, 152, 1344, 1880), scan.trim(TrimStrength.Strongest).content)
        assertTrue(!bitmap.isRecycled)
    }

    @Test
    fun anyOtherScaleCutsNoMoreThanTheMargin() {
        val page = PixelSize(1000, 1500)
        val trim = scanMargins(page(1000, 1500, Rect(100, 150, 900, 1350))).trim(TrimStrength.Strongest)
        assertKeeps(PixelRect(100, 150, 900, 1350), trim.content, page, "1000x1500")
    }

    @Test
    fun aBoundaryOffTheGridOfTheCopyIsNotCutAtAnyContrast() {
        val sidesAndStarts = listOf(4096 to 103, 2163 to 445, 1031 to 293, 4198 to 281)
        for ((side, start) in sidesAndStarts) for (contrast in listOf(33, 40, 100, 220)) {
            val page = PixelSize(side, side)
            val content = PixelRect(start, start + 3, side - side / 20 - start % 7, side - side / 19 - start % 5)
            val bitmap = solid(side, side, Rect(content.left, content.top, content.right, content.bottom), contrast)
            try {
                val scan = scanMargins(bitmap)
                for (strength in TrimStrength.entries) {
                    val kept = scan.trim(strength).content
                    assertTrue(
                        "$side, contrast $contrast, $strength: $kept",
                        kept.left <= content.left && kept.top <= content.top && kept.right >= content.right && kept.bottom >= content.bottom,
                    )
                }
                assertKeeps(content, scan.trim(TrimStrength.Strongest).content, page, "$side, contrast $contrast")
            } finally {
                bitmap.recycle()
            }
        }
    }

    @Test
    fun aHardwareBitmapIsRefused() {
        val hardware = page(400, 600, Rect(40, 60, 360, 540)).copy(Bitmap.Config.HARDWARE, false)
        assertThrows(IllegalArgumentException::class.java) { scanMargins(hardware) }
    }
}
