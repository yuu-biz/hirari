package io.github.yuubiz.hirari.feature.text

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.os.SystemClock
import android.util.Log
import io.github.yuubiz.hirari.core.data.GlobalSettings
import io.github.yuubiz.hirari.core.text.CP932
import io.github.yuubiz.hirari.core.text.PageContent
import io.github.yuubiz.hirari.core.text.PaintGlyphMetrics
import io.github.yuubiz.hirari.core.text.VerticalLayout
import io.github.yuubiz.hirari.core.text.decodeText
import io.github.yuubiz.hirari.core.text.detectCharset
import io.github.yuubiz.hirari.core.text.parseAozora
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What only the device can tell: how its ICU converter reads a CP932 byte with no mapping (docs/spec.md section
 * 6.2), and that a page laid out with the system font's metrics draws with ink on the paper, in the time P9 allows.
 */
class TextOnDeviceTest {
    @Test
    fun anUnmappedCp932LeadByteBeforeALineBreakReadsAsOneReplacementCharacter() {
        val bytes = "前".toByteArray(CP932) + byteArrayOf(0x85.toByte(), '\n'.code.toByte()) + "後".toByteArray(CP932)
        assertEquals("前�\n後", decodeText(bytes, CP932))
    }

    @Test
    fun aMegabyteTextReachesItsFirstPageWithTheSystemFontAndDrawsInk() {
        val line = "　吾輩《わがはい》は猫である。名前はまだ無い。どこで生れたかとんと見当《けんとう》がつかぬ。「何でも薄暗いじめじめした所で泣いていた」\n"
        val bytes = ("吾輩は猫である\n" + line.repeat((1 shl 20) / (line.length * 2) + 1)).toByteArray(CP932)
        val viewport = Viewport(width = 1080, height = 2340, density = 2.75f)
        val frame = pageFrame(viewport, GlobalSettings())
        val start = SystemClock.elapsedRealtime()
        val text = decodeText(bytes, detectCharset(bytes, CP932).charset)
        val decoded = SystemClock.elapsedRealtime()
        val document = parseAozora(text)
        val parsed = SystemClock.elapsedRealtime()
        val metrics = PaintGlyphMetrics(Typeface.SERIF)
        val setter = Typesetter(VerticalLayout(document, frame.params, metrics), document.sourceLength, 0)
        setter.step(0)
        val end = SystemClock.elapsedRealtime()
        Log.i(
            "TextOnDeviceTest",
            "P9 on this device: ${bytes.size} bytes to the first page in ${end - start} ms " +
                "(decode ${decoded - start}, parse ${parsed - decoded}, first pages ${end - parsed})",
        )
        val page = setter.snapshot().pages.first()
        assertTrue((page.content as PageContent.Lines).lines.size > 5)

        val colors = pageColors(GlobalSettings())
        val bitmap = Bitmap.createBitmap(frame.pageWidth.toInt(), frame.pageHeight.toInt(), Bitmap.Config.ARGB_8888)
        PagePainter(Typeface.SERIF, colors).draw(Canvas(bitmap), page, frame, 0f, 0f, PageDecor("吾輩は猫である", "1 / 2", null)) { null }
        val pixels = IntArray(bitmap.width * bitmap.height).also { bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height) }
        val paper = argb(colors.paper)
        assertEquals(paper, pixels[0])
        assertTrue("no ink on the page", pixels.count { Color.red(it) < 64 && Color.green(it) < 64 } > 1000)
    }
}
