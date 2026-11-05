package io.github.yuubiz.hirari.feature.text

import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class PictureDecodeOptionsTest {
    @Test
    fun `pictures target eight bit sRGB with the default allocator`() {
        val options = pictureDecodeOptions(400, 200, 200, 200)
        assertEquals(ColorSpace.get(ColorSpace.Named.SRGB), options.colorSpace)
        assertEquals(ImageDecoder.ALLOCATOR_DEFAULT, options.allocator)
        assertEquals(200, options.width)
        assertEquals(100, options.height)
    }

    @Test
    fun `four bytes per pixel fit within half the picture budget`() {
        val budget = 32 shl 20
        val options = pictureDecodeOptions(8000, 4000, 8000, 4000, budget)
        assertTrue(options.width.toLong() * options.height * 4 <= budget / 2)
        assertTrue(options.width < 8000)
        assertEquals(options.width / 2, options.height)
    }
}
