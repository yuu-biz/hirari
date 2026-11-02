package io.github.yuubiz.hirari.core.image

import io.github.yuubiz.hirari.core.model.ImageType

/** The image type of [bytes] by its magic bytes (docs/spec.md section 3.2), or null when it is not an image. */
fun sniffImage(bytes: ByteArray): ImageType? = when {
    bytes.startsWith(JPEG) -> ImageType.Jpeg
    bytes.startsWith(PNG) -> ImageType.Png
    bytes.startsWith(RIFF) && bytes.startsWith(WEBP, at = 8) -> ImageType.WebP
    bytes.startsWith(GIF87A) || bytes.startsWith(GIF89A) -> ImageType.Gif
    bytes.startsWith(BM) && bytes.u32le(14) in BMP_INFO_HEADER_SIZES -> ImageType.Bmp
    bytes.startsWith(JXL_CODESTREAM) || bytes.startsWith(JXL_CONTAINER) -> ImageType.JpegXl
    else -> isoBrandType(bytes)
}

/** Whether `%PDF-` starts within the first 1024 bytes of [prefix] (docs/spec.md section 3.2). */
fun isPdf(prefix: ByteArray): Boolean =
    (0..minOf(prefix.size, 1024) - PDF.size).any { prefix.startsWith(PDF, at = it) }

/** Whether [bytes] is a PNG with 16 bits per sample, which decodes to 8 bytes a pixel. */
internal fun isSixteenBitPng(bytes: ByteArray): Boolean =
    bytes.startsWith(PNG) && bytes.startsWith(IHDR, at = 12) && bytes.size > 24 && bytes[24].toInt() == 16

/**
 * HEIF or AVIF from the brands of the `ftyp` box. A file whose only brands are the generic `mif1` or `msf1` is
 * HEIF until the item types are read (M3).
 */
private fun isoBrandType(bytes: ByteArray): ImageType? {
    if (!bytes.startsWith(FTYP, at = 4)) return null
    val boxEnd = minOf(bytes.u32be(0) ?: return null, bytes.size.toLong()).toInt()
    val brands = buildList {
        add(brandAt(bytes, 8))
        var at = 16
        while (at + 4 <= boxEnd) {
            add(brandAt(bytes, at))
            at += 4
        }
    }
    return when {
        brands.any { it in AVIF_BRANDS } -> ImageType.Avif
        brands.any { it in HEIF_BRANDS } -> ImageType.Heif
        else -> null
    }
}

private fun brandAt(bytes: ByteArray, at: Int): String =
    if (at + 4 <= bytes.size) String(bytes, at, 4, Charsets.ISO_8859_1) else ""

private fun ByteArray.startsWith(prefix: ByteArray, at: Int = 0): Boolean =
    size >= at + prefix.size && prefix.indices.all { this[at + it] == prefix[it] }

private fun ByteArray.u32le(at: Int): Long? =
    if (size < at + 4) null else (0..3).fold(0L) { acc, i -> acc or ((this[at + i].toLong() and 0xff) shl (8 * i)) }

private fun ByteArray.u32be(at: Int): Long? =
    if (size < at + 4) null else (0..3).fold(0L) { acc, i -> (acc shl 8) or (this[at + i].toLong() and 0xff) }

private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

private val JPEG = bytes(0xff, 0xd8, 0xff)
private val PNG = bytes(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
private val IHDR = "IHDR".toByteArray(Charsets.US_ASCII)
private val RIFF = "RIFF".toByteArray(Charsets.US_ASCII)
private val WEBP = "WEBP".toByteArray(Charsets.US_ASCII)
private val GIF87A = "GIF87a".toByteArray(Charsets.US_ASCII)
private val GIF89A = "GIF89a".toByteArray(Charsets.US_ASCII)
private val BM = "BM".toByteArray(Charsets.US_ASCII)
private val JXL_CODESTREAM = bytes(0xff, 0x0a)
private val JXL_CONTAINER = bytes(0x00, 0x00, 0x00, 0x0c, 0x4a, 0x58, 0x4c, 0x20, 0x0d, 0x0a, 0x87, 0x0a)
private val FTYP = "ftyp".toByteArray(Charsets.US_ASCII)
private val PDF = "%PDF-".toByteArray(Charsets.US_ASCII)

/** The sizes of the BMP info headers: OS/2 1.x, Windows 3.x and its extensions, OS/2 2.x, and Windows 4 and 5. */
private val BMP_INFO_HEADER_SIZES = setOf(12L, 16L, 40L, 52L, 56L, 64L, 108L, 124L)
private val AVIF_BRANDS = setOf("avif", "avis")
private val HEIF_BRANDS = setOf("heic", "heix", "hevc", "heim", "heis", "mif1", "msf1")
