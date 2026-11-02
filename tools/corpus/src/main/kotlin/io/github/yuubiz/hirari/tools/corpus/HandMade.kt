package io.github.yuubiz.hirari.tools.corpus

import java.awt.image.BufferedImage
import java.awt.image.DataBuffer
import java.awt.image.Raster
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import javax.imageio.stream.MemoryCacheImageOutputStream

/** Image files written byte by byte, for the formats and variants the JDK has no encoder for. */

private fun u16le(value: Int) = byteArrayOf(value.toByte(), (value ushr 8).toByte())

private fun u24le(value: Int) = byteArrayOf(value.toByte(), (value ushr 8).toByte(), (value ushr 16).toByte())

private fun u32le(value: Int) = u16le(value) + u16le(value ushr 16)

private fun u16be(value: Int) = byteArrayOf((value ushr 8).toByte(), value.toByte())

private fun ascii(text: String) = text.toByteArray(Charsets.US_ASCII)

/**
 * A BMP with a BITMAPINFOHEADER, rows bottom-up, at [bits] per pixel: 1, 4 or 8 through a gray palette, 16 as
 * RGB 5-5-5, 24 as BGR, and 32 as BGR with an unused byte.
 */
internal fun bmp(width: Int, height: Int, bits: Int): ByteArray {
    require(bits in setOf(1, 4, 8, 16, 24, 32))
    val paletteSize = if (bits <= 8) 1 shl bits else 0
    val stride = (width * bits + 31) / 32 * 4
    val pixels = ByteArray(stride * height)
    for (row in 0 until height) {
        val y = height - 1 - row
        for (x in 0 until width) {
            val level = halftoneLevel(x, y, width, height)
            val red = (255 * (1 - level)).toInt()
            val green = minOf(255, red + x * 64 / width)
            val blue = y * 255 / height
            val at = row * stride
            when (bits) {
                1, 4, 8 -> {
                    val index = (level * (paletteSize - 1)).toInt().let { if (x * 4 < width) (paletteSize - 1) / 2 else it }
                    val bit = x * bits
                    val shift = 8 - bits - bit % 8
                    pixels[at + bit / 8] = (pixels[at + bit / 8].toInt() or (index shl shift)).toByte()
                }
                16 -> u16le(((red shr 3) shl 10) or ((green shr 3) shl 5) or (blue shr 3)).copyInto(pixels, at + x * 2)
                else -> byteArrayOf(blue.toByte(), green.toByte(), red.toByte()).copyInto(pixels, at + x * bits / 8)
            }
        }
    }
    val palette = ByteArray(paletteSize * 4) { i -> if (i % 4 == 3) 0 else (255 - i / 4 * 255 / maxOf(1, paletteSize - 1)).toByte() }
    val offset = 14 + 40 + palette.size
    val fileHeader = ascii("BM") + u32le(offset + pixels.size) + u32le(0) + u32le(offset)
    val infoHeader = u32le(40) + u32le(width) + u32le(height) + u16le(1) + u16le(bits) + u32le(0) + u32le(pixels.size) +
        u32le(2835) + u32le(2835) + u32le(paletteSize) + u32le(0)
    return fileHeader + infoHeader + palette + pixels
}

/** The JPEG coding processes [flatJpeg] writes. */
internal enum class JpegProcess(val marker: Int, val precision: Int) {
    Baseline(0xc0, 8),
    Extended12Bit(0xc1, 12),
    Lossless(0xc3, 8),
}

private fun segment(marker: Int, body: ByteArray) = byteArrayOf(0xff.toByte(), marker.toByte()) + u16be(body.size + 2) + body

/**
 * A one-component JPEG of [width] x [height], multiples of 8, that is flat mid gray: every DCT block has a zero
 * DC difference and no AC coefficient, and every lossless difference is zero. Each Huffman table has one code,
 * `0`, so the scan is one zero bit per block value and pads with ones.
 */
internal fun flatJpeg(width: Int, height: Int, process: JpegProcess): ByteArray {
    require(width % 8 == 0 && height % 8 == 0)
    val oneCode = ByteArray(16).also { it[0] = 1 } + byteArrayOf(0)
    val lossless = process == JpegProcess.Lossless
    val frame = byteArrayOf(process.precision.toByte()) + u16be(height) + u16be(width) + byteArrayOf(1, 1, 0x11, 0)
    val tables = if (lossless) {
        segment(0xc4, byteArrayOf(0x00) + oneCode)
    } else {
        segment(0xdb, byteArrayOf(0x00) + ByteArray(64) { 1 }) + segment(0xc4, byteArrayOf(0x00) + oneCode + byteArrayOf(0x10) + oneCode)
    }
    // Lossless scans carry the predictor in Ss and 0 in Se; DCT scans carry the spectral range 0 to 63.
    val scan = byteArrayOf(1, 1, 0x00) + if (lossless) byteArrayOf(1, 0, 0) else byteArrayOf(0, 63, 0)
    val bits = if (lossless) width * height else width * height / 64 * 2
    val data = ByteArray((bits + 7) / 8)
    if (bits % 8 != 0) data[data.size - 1] = (0xff ushr (bits % 8)).toByte()
    return byteArrayOf(0xff.toByte(), 0xd8.toByte()) + tables + segment(process.marker, frame) + segment(0xda, scan) + data +
        byteArrayOf(0xff.toByte(), 0xd9.toByte())
}

/** [jpeg] with an Exif segment right after its start whose only tag is the [orientation] of docs/spec.md section 3.6. */
internal fun withExifOrientation(jpeg: ByteArray, orientation: Int): ByteArray {
    val tiff = ascii("MM") + u16be(42) + u32be(8) + u16be(1) + u16be(0x0112) + u16be(3) + u32be(1) + u16be(orientation) + u16be(0) + u32be(0)
    return jpeg.copyOf(2) + segment(0xe1, ascii("Exif") + byteArrayOf(0, 0) + tiff) + jpeg.copyOfRange(2, jpeg.size)
}

/** A JPEG with four components and no color conversion, which decoders take for CMYK. */
internal fun cmykJpeg(width: Int, height: Int): ByteArray {
    val raster = Raster.createInterleavedRaster(DataBuffer.TYPE_BYTE, width, height, 4, null)
    for (y in 0 until height) for (x in 0 until width) {
        val ink = (255 * halftoneLevel(x, y, width, height)).toInt()
        raster.setPixel(x, y, intArrayOf(x * 255 / width, y * 255 / height, 0, ink))
    }
    val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
    val out = ByteArrayOutputStream()
    MemoryCacheImageOutputStream(out).use { stream ->
        writer.output = stream
        val param = writer.defaultWriteParam.apply {
            compressionMode = ImageWriteParam.MODE_EXPLICIT
            compressionQuality = 0.9f
        }
        writer.write(null, IIOImage(raster, null, null), param)
    }
    writer.dispose()
    return out.toByteArray()
}

/** A GIF of [frames], all the same size, with the encoder's default timing. */
internal fun animatedGif(frames: List<BufferedImage>): ByteArray {
    val writer = ImageIO.getImageWritersByFormatName("gif").next()
    val out = ByteArrayOutputStream()
    MemoryCacheImageOutputStream(out).use { stream ->
        writer.output = stream
        writer.prepareWriteSequence(null)
        frames.forEach { writer.writeToSequence(IIOImage(it, null, null), null) }
        writer.endWriteSequence()
    }
    writer.dispose()
    return out.toByteArray()
}

/** Bits packed from the least significant end, as WebP lossless reads them. */
private class LsbBits {
    private val out = ByteArrayOutputStream()
    private var current = 0
    private var used = 0

    fun put(value: Int, count: Int) {
        for (i in 0 until count) {
            current = current or (((value ushr i) and 1) shl used)
            if (++used == 8) {
                out.write(current)
                current = 0
                used = 0
            }
        }
    }

    fun bytes(): ByteArray {
        if (used > 0) out.write(current)
        return out.toByteArray()
    }
}

/**
 * A WebP lossless bitstream of one [argb] color. Each of the five prefix codes is a simple code with one
 * symbol, which takes no bits, so the pixels need no data at all.
 */
private fun vp8lSolid(width: Int, height: Int, argb: Int): ByteArray {
    val bits = LsbBits()
    bits.put(width - 1, 14)
    bits.put(height - 1, 14)
    bits.put(0, 1)
    bits.put(0, 3)
    bits.put(0, 1) // no transform
    bits.put(0, 1) // no color cache
    bits.put(0, 1) // no meta prefix codes
    for (symbol in listOf((argb ushr 8) and 0xff, (argb ushr 16) and 0xff, argb and 0xff, argb ushr 24, 0)) {
        bits.put(1, 1) // simple code
        bits.put(0, 1) // one symbol
        bits.put(1, 1) // eight bits wide
        bits.put(symbol, 8)
    }
    return byteArrayOf(0x2f) + bits.bytes()
}

private fun riffChunk(type: String, data: ByteArray): ByteArray =
    ascii(type) + u32le(data.size) + data + if (data.size % 2 == 1) byteArrayOf(0) else ByteArray(0)

private fun riffWebp(chunks: ByteArray) = ascii("RIFF") + u32le(4 + chunks.size) + ascii("WEBP") + chunks

/** A still WebP lossless image of one opaque color. */
internal fun losslessWebp(width: Int, height: Int, rgb: Int): ByteArray =
    riffWebp(riffChunk("VP8L", vp8lSolid(width, height, rgb or (0xff shl 24))))

/** An animated WebP of one frame per color in [colors], each filling the canvas for 100 ms. */
internal fun animatedWebp(width: Int, height: Int, colors: List<Int>): ByteArray {
    val header = riffChunk("VP8X", byteArrayOf(0x02, 0, 0, 0) + u24le(width - 1) + u24le(height - 1))
    val animation = riffChunk("ANIM", u32le(-1) + u16le(0))
    val frames = colors.fold(ByteArray(0)) { acc, rgb ->
        val frame = u24le(0) + u24le(0) + u24le(width - 1) + u24le(height - 1) + u24le(100) + byteArrayOf(0x02)
        acc + riffChunk("ANMF", frame + riffChunk("VP8L", vp8lSolid(width, height, rgb or (0xff shl 24))))
    }
    return riffWebp(header + animation + frames)
}

/** An ISO base media file with an `ftyp` box of [major] and [compatible] brands and nothing else. */
internal fun ftypOnly(major: String, vararg compatible: String): ByteArray {
    val body = ascii("ftyp") + ascii(major) + ByteArray(4) + compatible.fold(ByteArray(0)) { acc, brand -> acc + ascii(brand) }
    return u32be(body.size + 4) + body
}

private fun u32be(value: Int) = u16be(value ushr 16) + u16be(value)
