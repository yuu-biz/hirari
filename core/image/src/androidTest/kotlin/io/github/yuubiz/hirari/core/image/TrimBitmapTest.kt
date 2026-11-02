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

    @Test
    fun theContentOfAPageScaledByAPowerOfTwoComesBackExactly() {
        val bitmap = page(1448, 2048, Rect(120, 160, 1336, 1872))
        val micros = List(10) {
            val start = SystemClock.elapsedRealtimeNanos()
            scanMargins(bitmap)
            (SystemClock.elapsedRealtimeNanos() - start) / 1_000
        }
        Log.i("TrimBitmapTest", "1448x2048 read in $micros us, first call first")
        val scan = scanMargins(bitmap)
        assertEquals(PixelSize(1448, 2048), scan.page)
        assertEquals(PixelRect(120, 160, 1336, 1872), scan.trim(TrimStrength.Strongest).content)
        assertTrue(!bitmap.isRecycled)
    }

    @Test
    fun anyOtherScaleCutsNoMoreThanTheMargin() {
        val content = PixelRect(100, 150, 900, 1350)
        val trim = scanMargins(page(1000, 1500, Rect(100, 150, 900, 1350))).trim(TrimStrength.Strongest)
        val found = trim.content
        assertTrue("$found", found.left in content.left - 6..content.left && found.top in content.top - 6..content.top)
        assertTrue("$found", found.right in content.right..content.right + 6 && found.bottom in content.bottom..content.bottom + 6)
    }

    @Test
    fun aHardwareBitmapIsRefused() {
        val hardware = page(400, 600, Rect(40, 60, 360, 540)).copy(Bitmap.Config.HARDWARE, false)
        assertThrows(IllegalArgumentException::class.java) { scanMargins(hardware) }
    }
}
