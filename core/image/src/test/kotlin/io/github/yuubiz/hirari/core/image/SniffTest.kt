package io.github.yuubiz.hirari.core.image

import io.github.yuubiz.hirari.core.model.ImageType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SniffTest {
    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private fun ascii(text: String) = text.toByteArray(Charsets.US_ASCII)

    private fun u32le(value: Int) = bytes(value and 0xff, (value shr 8) and 0xff, (value shr 16) and 0xff, value ushr 24)

    private fun ftyp(major: String, vararg compatible: String): ByteArray {
        val body = ascii("ftyp") + ascii(major) + ByteArray(4) + compatible.fold(ByteArray(0)) { acc, b -> acc + ascii(b) }
        val size = 4 + body.size
        return bytes(size ushr 24, (size shr 16) and 0xff, (size shr 8) and 0xff, size and 0xff) + body + ByteArray(16)
    }

    private val png = bytes(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)

    private fun pngHeader(bitDepth: Int) = png + bytes(0, 0, 0, 13) + ascii("IHDR") + bytes(0, 0, 0, 1, 0, 0, 0, 1, bitDepth, 6, 0, 0, 0)

    @Test
    fun eachMagicGivesItsType() {
        val cases = mapOf(
            bytes(0xff, 0xd8, 0xff, 0xe0) to ImageType.Jpeg,
            png to ImageType.Png,
            ascii("RIFF") + ByteArray(4) + ascii("WEBPVP8 ") to ImageType.WebP,
            ascii("GIF87a") to ImageType.Gif,
            ascii("GIF89a") to ImageType.Gif,
            ascii("BM") + ByteArray(12) + u32le(40) to ImageType.Bmp,
            ascii("BM") + ByteArray(12) + u32le(124) to ImageType.Bmp,
            bytes(0xff, 0x0a, 0x00) to ImageType.JpegXl,
            bytes(0, 0, 0, 0x0c, 0x4a, 0x58, 0x4c, 0x20, 0x0d, 0x0a, 0x87, 0x0a) to ImageType.JpegXl,
            ftyp("heic", "mif1", "heic") to ImageType.Heif,
            ftyp("mif1", "miaf") to ImageType.Heif,
            ftyp("avif", "mif1", "miaf") to ImageType.Avif,
            ftyp("mif1", "avif") to ImageType.Avif,
        )
        cases.forEach { (bytes, type) -> assertEquals(bytes.joinToString(" ") { "%02x".format(it) }, type, sniffImage(bytes)) }
    }

    @Test
    fun anythingElseIsNotAnImage() {
        listOf(
            ByteArray(0),
            ByteArray(64),
            ascii("PK\u0003\u0004"),
            bytes(0xff, 0xd8),
            ascii("RIFF") + ByteArray(4) + ascii("WAVE"),
            ascii("BM") + ByteArray(12) + u32le(1000),
            ascii("BMsmall"),
            ftyp("isom", "mp41"),
            ascii("plain text, not an image"),
        ).forEach { assertNull(String(it, Charsets.ISO_8859_1), sniffImage(it)) }
    }

    @Test
    fun theExtensionPlaysNoPart() {
        assertEquals(ImageType.Png, sniffImage(png + ByteArray(32)))
    }

    @Test
    fun sixteenBitPngIsToldApartByItsBitDepth() {
        assertTrue(isSixteenBitPng(pngHeader(16)))
        assertFalse(isSixteenBitPng(pngHeader(8)))
        assertFalse(isSixteenBitPng(png))
        assertFalse(isSixteenBitPng(bytes(0xff, 0xd8, 0xff)))
    }
}
