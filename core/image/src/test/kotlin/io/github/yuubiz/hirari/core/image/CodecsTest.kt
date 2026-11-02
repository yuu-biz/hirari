package io.github.yuubiz.hirari.core.image

import io.github.yuubiz.hirari.core.model.ImageType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CodecsTest {
    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    /** A JPEG start, an APP0 segment, and a frame header with [marker] and [precision]. */
    private fun jpegWithFrame(marker: Int, precision: Int) =
        bytes(0xff, 0xd8, 0xff, 0xe0, 0x00, 0x04, 0x4a, 0x46, 0xff, marker, 0x00, 0x0b, precision, 0, 8, 0, 8, 1, 1, 0x11, 0)

    @Test
    fun jpegXlIsTheSeamForItsOwnDecoder() {
        assertEquals(Codec.Missing("JPEG XL"), codecFor(ImageType.JpegXl, sdk = 37))
    }

    @Test
    fun avifDecodesOnThePlatformFromAndroid12Only() {
        assertEquals(Codec.Missing("AVIF"), codecFor(ImageType.Avif, sdk = 30))
        val platform = codecFor(ImageType.Avif, sdk = 31) as Codec.Platform
        assertEquals(true, platform.needsDeviceDecoder)
    }

    @Test
    fun onlyFormatsWhoseDecoderAveragesAreSampledInTheDecoder() {
        val limits = ImageType.entries.associateWith { (codecFor(it, sdk = 37) as? Codec.Platform)?.averagingLimit }
        assertEquals(8, limits[ImageType.Jpeg])
        assertEquals(Int.MAX_VALUE, limits[ImageType.WebP])
        for (type in listOf(ImageType.Png, ImageType.Gif, ImageType.Bmp, ImageType.Heif, ImageType.Avif)) {
            assertEquals("$type", 1, limits[type])
        }
    }

    @Test
    fun theFrameHeaderNamesTheCodingThatEightBitDecodersLack() {
        assertNull(jpegFeature(jpegWithFrame(0xc0, 8)))
        assertNull(jpegFeature(jpegWithFrame(0xc2, 8)))
        assertEquals("12-bit JPEG", jpegFeature(jpegWithFrame(0xc1, 12)))
        assertEquals("12-bit JPEG", jpegFeature(jpegWithFrame(0xc2, 12)))
        assertEquals("lossless JPEG", jpegFeature(jpegWithFrame(0xc3, 8)))
        assertEquals("lossless JPEG", jpegFeature(jpegWithFrame(0xc7, 16)))
    }

    @Test
    fun aHuffmanTableIsNotTakenForAFrame() {
        val table = bytes(0xff, 0xc4, 0x00, 0x03, 0x00)
        val jpeg = bytes(0xff, 0xd8) + table + jpegWithFrame(0xc1, 12).copyOfRange(2, 21)
        assertEquals("12-bit JPEG", jpegFeature(jpeg))
    }

    @Test
    fun aHeaderCutBeforeTheFrameNamesNothing() {
        assertNull(jpegFeature(jpegWithFrame(0xc1, 12).copyOf(10)))
        assertNull(jpegFeature(bytes(0xff, 0xd8, 0x00, 0x00, 0x00)))
    }
}
