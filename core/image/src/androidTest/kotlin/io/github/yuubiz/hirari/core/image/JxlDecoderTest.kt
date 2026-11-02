package io.github.yuubiz.hirari.core.image

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yuubiz.hirari.core.model.FailureReason
import io.github.yuubiz.hirari.core.model.PageResult
import io.github.yuubiz.hirari.core.model.PageShape
import io.github.yuubiz.hirari.core.model.UnsupportedKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.ByteBuffer
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs

/**
 * libjxl through JNI on a device (docs/spec.md sections 3.6, 3.7 and 8.3). The files in assets/jxl are written
 * by src/androidTest/jxl/make_test_images.py, which also says what each one shows.
 */
class JxlDecoderTest {
    private val assets = InstrumentationRegistry.getInstrumentation().context.assets
    private val budget = 256L shl 20

    private fun jxl(name: String): ByteArray = assets.open("jxl/$name").use { it.readBytes() }

    private fun header(bytes: ByteArray): JxlHeader = when (val read = JxlDecoder.header(bytes)) {
        is JxlHeaderResult.Read -> read.header
        is JxlHeaderResult.Failed -> throw AssertionError("header failed: ${read.failure}")
    }

    private fun decoded(name: String, sampleSize: Int = 1): Bitmap {
        val bytes = jxl(name)
        return when (val result = JxlDecoder.decode(bytes, header(bytes), sampleSize, budget / 2) { true }) {
            is JxlDecodeResult.Decoded -> {
                Log.i("JxlDecoderTest", "$name at 1/$sampleSize: ${result.peakNativeBytes} native bytes at the peak")
                result.bitmap
            }
            is JxlDecodeResult.Failed -> throw AssertionError("$name failed: ${result.failure}")
        }
    }

    private fun page(name: String, target: PixelSize, budgetBytes: Long = budget): PageResult<PreparedImage> =
        decodeJxlPage(jxl(name), target, budgetBytes) { true }

    private fun assertColor(expected: Int, actual: Int, tolerance: Int = 0) {
        val close = listOf(Color::alpha, Color::red, Color::green, Color::blue).all { channel ->
            abs(channel(expected) - channel(actual)) <= tolerance
        }
        assertTrue("expected #%08x within $tolerance, got #%08x".format(expected, actual), close)
    }

    private fun assertQuadrants(bitmap: Bitmap, tolerance: Int) {
        val (w, h) = bitmap.width to bitmap.height
        assertColor(Color.rgb(220, 30, 40), bitmap.getPixel(w / 4, h / 4), tolerance)
        assertColor(Color.rgb(30, 200, 60), bitmap.getPixel(3 * w / 4, h / 4), tolerance)
        assertColor(Color.rgb(40, 60, 210), bitmap.getPixel(w / 4, 3 * h / 4), tolerance)
        assertColor(Color.WHITE, bitmap.getPixel(3 * w / 4, 3 * h / 4), tolerance)
    }

    @Test
    fun lossyImageDecodesToSrgbAtFullSize() {
        val bitmap = decoded("lossy_quadrants.jxl")
        assertEquals(256 to 128, bitmap.width to bitmap.height)
        assertFalse(bitmap.hasAlpha())
        assertQuadrants(bitmap, tolerance = 12)
    }

    @Test
    fun sampleSizeAveragesSquaresOfPixels() {
        val bitmap = decoded("lossy_quadrants.jxl", sampleSize = 2)
        assertEquals(128 to 64, bitmap.width to bitmap.height)
        assertQuadrants(bitmap, tolerance = 12)
    }

    @Test
    fun losslessGrayKeepsEveryValue() {
        val bitmap = decoded("lossless_gray.jxl")
        assertEquals(32 to 16, bitmap.width to bitmap.height)
        for ((x, y) in listOf(0 to 0, 5 to 3, 31 to 15)) {
            val gray = (x * 8 + y) % 256
            assertColor(Color.rgb(gray, gray, gray), bitmap.getPixel(x, y))
        }
    }

    @Test
    fun straightAlphaIsPremultipliedInTheBitmap() {
        val bitmap = decoded("lossless_alpha.jxl")
        assertTrue(bitmap.hasAlpha())
        val pixels = ByteBuffer.allocate(bitmap.byteCount).also(bitmap::copyPixelsToBuffer)
        assertEquals(listOf(128, 0, 0, 128), (0 until 4).map { pixels.get(it).toInt() and 0xff })
    }

    @Test
    fun containerDecodesLikeABareCodestream() {
        val bytes = jxl("lossless_container.jxl")
        assertEquals("container signature", 0x0c, bytes[3].toInt())
        assertQuadrants(decoded("lossless_container.jxl"), tolerance = 0)
    }

    @Test
    fun animationGivesItsFirstFrame() {
        assertTrue(header(jxl("animation.jxl")).hasAnimation)
        assertColor(Color.rgb(220, 30, 40), decoded("animation.jxl").getPixel(12, 12))
    }

    @Test
    fun pageFitsTheTargetAndKeepsTheShape() {
        val result = page("lossy_quadrants.jxl", PixelSize(64, 64)) as PageResult.Ready
        val image = result.image as PreparedImage.Whole
        assertEquals(PageShape.Known(256, 128), image.shape)
        assertEquals(64 to 32, image.bitmap.width to image.bitmap.height)
        assertQuadrants(image.bitmap, tolerance = 12)
    }

    @Test
    fun implausibleHeaderIsRefusedBeforeDecoding() {
        val bytes = jxl("too_tall.jxl")
        assertEquals(JxlHeader(1, (1 shl 20) + 1, hasAnimation = false), header(bytes))
        val refused = PageResult.Unsupported(UnsupportedKind.TooLarge(1, (1 shl 20) + 1, ((1L shl 20) + 1) * 4))
        assertEquals(refused, decodeJxlShape(bytes))
        var checks = 0
        assertEquals(refused, decodeJxlPage(bytes, PixelSize(1080, 1920), budget) { checks++; true })
        assertEquals("only the header stage ran", 0, checks)
    }

    @Test
    fun memoryLimitStopsLibjxl() {
        val bytes = jxl("lossy_quadrants.jxl")
        assertEquals(
            JxlDecodeResult.Failed(JxlFailure.OverLimit),
            JxlDecoder.decode(bytes, header(bytes), 1, memoryLimitBytes = 16L shl 10) { true },
        )
        assertEquals(
            PageResult.Unsupported(UnsupportedKind.TooLarge(256, 128, 256L * 128 * 4)),
            page("lossy_quadrants.jxl", PixelSize(256, 128), budgetBytes = 32L shl 10),
        )
    }

    @Test
    fun cancellationStopsTheDecode() {
        val bytes = jxl("lossy_quadrants.jxl")
        var whole = 0
        JxlDecoder.decode(bytes, header(bytes), 1, budget / 2) { whole++; true }
        // Four events reach the loop around JxlDecoderProcessInput. The rest are the runner's, between groups.
        assertTrue("checks in a whole decode: $whole", whole > 8)
        var checks = 0
        val result = JxlDecoder.decode(bytes, header(bytes), 1, budget / 2) { checks++ < 7 }
        assertEquals(JxlDecodeResult.Failed(JxlFailure.Cancelled), result)
        assertEquals("no check after the one that stopped", 8, checks)
        try {
            decodeJxlPage(bytes, PixelSize(256, 128), budget) { false }
            fail("expected a cancellation")
        } catch (_: CancellationException) {
        }
    }

    @Test
    fun exceptionFromTheCancellationCheckReachesTheCaller() {
        val bytes = jxl("lossy_quadrants.jxl")
        try {
            JxlDecoder.decode(bytes, header(bytes), 1, budget / 2) { throw IllegalStateException("from the check") }
            fail("expected the exception")
        } catch (e: IllegalStateException) {
            assertEquals("from the check", e.message)
        }
    }

    @Test
    fun truncatedAndCorruptInputFail() {
        val bytes = jxl("lossy_quadrants.jxl")
        val truncated = bytes.copyOf(bytes.size / 2)
        assertEquals(PageResult.Failed(FailureReason.Truncated, retryable = false), decodeJxlPage(truncated, PixelSize(256, 128), budget) { true })
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a) + ByteArray(64)
        assertEquals(JxlHeaderResult.Failed(JxlFailure.Corrupt), JxlDecoder.header(png))
        assertEquals(JxlHeaderResult.Failed(JxlFailure.Truncated), JxlDecoder.header(bytes.copyOf(2)))
    }
}
