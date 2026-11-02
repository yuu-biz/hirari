package io.github.yuubiz.hirari.tools.corpus

import java.awt.Transparency
import java.awt.color.ColorSpace
import java.awt.image.BufferedImage
import java.awt.image.ComponentColorModel
import java.awt.image.DataBuffer
import java.awt.image.IndexColorModel
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import javax.imageio.stream.MemoryCacheImageOutputStream
import kotlin.math.hypot
import kotlin.math.min

/**
 * Screentone-like dots whose size follows a diagonal gradient, so a bad downscale shows moiré. The level at
 * [x], [y] is 0 for white and 1 for black.
 */
internal fun halftoneLevel(x: Int, y: Int, width: Int, height: Int, cell: Int = 6): Double {
    val shade = (x.toDouble() / width + y.toDouble() / height) / 2
    val cx = x % cell - cell / 2.0 + 0.5
    val cy = y % cell - cell / 2.0 + 0.5
    val radius = cell / 2.0 * Math.sqrt(shade) * 1.3
    return if (hypot(cx, cy) < radius) 1.0 else 0.0
}

private fun gray(level: Double): Int = (255 * (1 - level)).toInt().coerceIn(0, 255)

private fun rgbHalftone(width: Int, height: Int): BufferedImage {
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    for (y in 0 until height) {
        for (x in 0 until width) {
            val g = gray(halftoneLevel(x, y, width, height))
            image.setRGB(x, y, (g shl 16) or (min(255, g + (x * 64 / width)) shl 8) or g)
        }
    }
    return image
}

internal fun jpeg(image: BufferedImage, quality: Float = 0.9f, progressive: Boolean = false): ByteArray {
    val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
    val param = writer.defaultWriteParam.apply {
        compressionMode = ImageWriteParam.MODE_EXPLICIT
        compressionQuality = quality
        if (progressive) progressiveMode = ImageWriteParam.MODE_DEFAULT
    }
    return write(writer, image, param)
}

private fun png(image: BufferedImage, interlaced: Boolean = false): ByteArray {
    val writer = ImageIO.getImageWritersByFormatName("png").next()
    val param = writer.defaultWriteParam.apply { if (interlaced) progressiveMode = ImageWriteParam.MODE_DEFAULT }
    return write(writer, image, param)
}

private fun write(writer: javax.imageio.ImageWriter, image: BufferedImage, param: ImageWriteParam): ByteArray {
    val out = ByteArrayOutputStream()
    MemoryCacheImageOutputStream(out).use { stream ->
        writer.output = stream
        writer.write(null, IIOImage(image, null, null), param)
    }
    writer.dispose()
    return out.toByteArray()
}

private fun encode(image: BufferedImage, format: String): ByteArray =
    ByteArrayOutputStream().also { check(ImageIO.write(image, format, it)) { "no $format writer" } }.toByteArray()

/** A palette PNG whose index 0 is transparent through a tRNS chunk. */
private fun palettePng(width: Int, height: Int): ByteArray {
    val levels = 16
    val palette = ByteArray(levels) { (255 - it * 255 / (levels - 1)).toByte() }
    val model = IndexColorModel(8, levels, palette, palette, palette, 0)
    val image = BufferedImage(width, height, BufferedImage.TYPE_BYTE_INDEXED, model)
    for (y in 0 until height) for (x in 0 until width) {
        image.raster.setSample(x, y, 0, (halftoneLevel(x, y, width, height) * (levels - 1)).toInt().coerceAtLeast(if (x < width / 4) 0 else 1))
    }
    return png(image)
}

private fun sixteenBitPng(width: Int, height: Int): ByteArray {
    val model = ComponentColorModel(ColorSpace.getInstance(ColorSpace.CS_sRGB), intArrayOf(16, 16, 16), false, false, Transparency.OPAQUE, DataBuffer.TYPE_USHORT)
    val raster = model.createCompatibleWritableRaster(width, height)
    for (y in 0 until height) for (x in 0 until width) {
        val v = (65535 * (1 - halftoneLevel(x, y, width, height))).toInt()
        raster.setPixel(x, y, intArrayOf(v, x * 65535 / width, y * 65535 / height))
    }
    return png(BufferedImage(model, raster, false, null))
}

private fun grayAlphaPng(width: Int, height: Int): ByteArray {
    val model = ComponentColorModel(ColorSpace.getInstance(ColorSpace.CS_GRAY), intArrayOf(8, 8), true, false, Transparency.TRANSLUCENT, DataBuffer.TYPE_BYTE)
    val raster = model.createCompatibleWritableRaster(width, height)
    for (y in 0 until height) for (x in 0 until width) {
        raster.setPixel(x, y, intArrayOf(gray(halftoneLevel(x, y, width, height)), 255 * x / width))
    }
    return png(BufferedImage(model, raster, false, null))
}

private fun indexed(width: Int, height: Int): BufferedImage {
    val image = BufferedImage(width, height, BufferedImage.TYPE_BYTE_INDEXED)
    val source = rgbHalftone(width, height)
    image.graphics.apply { drawImage(source, 0, 0, null) }.dispose()
    return image
}

private fun bgr(width: Int, height: Int): BufferedImage {
    val image = BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR)
    image.graphics.apply { drawImage(rgbHalftone(width, height), 0, 0, null) }.dispose()
    return image
}

private fun pngChunk(type: String, data: ByteArray): ByteArray {
    val typeBytes = type.toByteArray(Charsets.US_ASCII)
    return u32be(data.size) + typeBytes + data + u32be(crc32(typeBytes + data))
}

private fun u32be(value: Int) = byteArrayOf((value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte())

private fun zlib(data: ByteArray): ByteArray {
    val deflater = Deflater()
    deflater.setInput(data)
    deflater.finish()
    val buffer = ByteArray(1024)
    val size = deflater.deflate(buffer)
    deflater.end()
    return buffer.copyOf(size)
}

/** A PNG of a few dozen bytes whose header declares [width] x [height] RGB pixels. */
private fun declaredPng(width: Int, height: Int): ByteArray {
    val signature = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
    val header = u32be(width) + u32be(height) + byteArrayOf(8, 2, 0, 0, 0)
    return signature + pngChunk("IHDR", header) + pngChunk("IDAT", zlib(ByteArray(16))) + pngChunk("IEND", ByteArray(0))
}

/** One image entry of the image corpus with what the book opener must return for it. */
internal class ImageCase(val name: String, val bytes: ByteArray, val page: ExpectedPage)

/** The image cases of docs/spec.md section 6.2, in one archive. HEIF and AVIF have no encoder here (see [heifCases]). */
internal fun imageCases(): List<ImageCase> {
    val progressive = jpeg(rgbHalftone(600, 900), progressive = true)
    val pngBytes = png(rgbHalftone(400, 600))
    return listOf(
        ImageCase("baseline.jpg", jpeg(rgbHalftone(600, 900)), ExpectedPage.Ready(600, 900)),
        ImageCase("progressive.jpg", progressive, ExpectedPage.Ready(600, 900)),
        // API 37 decodes the scans that arrived and API 28 reports the stream as malformed.
        ImageCase(
            "progressive-truncated.jpg",
            progressive.copyOf(progressive.size * 3 / 5),
            ExpectedPage.OneOf(listOf(ExpectedPage.Ready(600, 900), ExpectedPage.Fails(Failure.Corrupt))),
        ),
        ImageCase("large.jpg", jpeg(rgbHalftone(2400, 3600)), ExpectedPage.Ready(2400, 3600)),
        ImageCase("spread.jpg", jpeg(rgbHalftone(1800, 1200)), ExpectedPage.Ready(1800, 1200)),
        ImageCase("one-pixel.jpg", jpeg(rgbHalftone(1, 1)), ExpectedPage.Ready(1, 1)),
        ImageCase("png-named.jpg", pngBytes, ExpectedPage.Ready(400, 600)),
        ImageCase("no-extension", pngBytes, ExpectedPage.Ready(400, 600)),
        ImageCase("zero-filled.jpg", ByteArray(4096), ExpectedPage.NotAnImage),
        ImageCase("large.png", png(rgbHalftone(2000, 3000)), ExpectedPage.Ready(2000, 3000)),
        ImageCase("palette-trns.png", palettePng(400, 600), ExpectedPage.Ready(400, 600)),
        ImageCase("sixteen-bit.png", sixteenBitPng(300, 450), ExpectedPage.Ready(300, 450)),
        ImageCase("interlaced.png", png(rgbHalftone(400, 600), interlaced = true), ExpectedPage.Ready(400, 600)),
        ImageCase("gray-alpha.png", grayAlphaPng(400, 600), ExpectedPage.Ready(400, 600)),
        ImageCase(
            "declares-65535.png",
            declaredPng(65535, 65535),
            ExpectedPage.TooLarge(65535, 65535, 65535L * 65535 * 4),
        ),
        ImageCase("still.gif", encode(indexed(200, 300), "gif"), ExpectedPage.Ready(200, 300)),
        ImageCase("animated.gif", animatedGif(listOf(indexed(200, 300), indexed(300, 200).let(::rotated))), ExpectedPage.Ready(200, 300)),
        ImageCase("imageio.bmp", encode(bgr(200, 300), "bmp"), ExpectedPage.Ready(200, 300)),
        ImageCase("cmyk.jpg", cmykJpeg(400, 600), ExpectedPage.Ready(400, 600)),
        ImageCase("flat-baseline.jpg", flatJpeg(64, 96, JpegProcess.Baseline), ExpectedPage.Ready(64, 96)),
        ImageCase("flat-12-bit.jpg", flatJpeg(64, 96, JpegProcess.Extended12Bit), ExpectedPage.ImageFeature("12-bit JPEG")),
        ImageCase("flat-lossless.jpg", flatJpeg(64, 96, JpegProcess.Lossless), ExpectedPage.ImageFeature("lossless JPEG")),
        ImageCase("lossless.webp", losslessWebp(120, 180, 0x3366cc), ExpectedPage.Ready(120, 180)),
        ImageCase("animated.webp", animatedWebp(120, 180, listOf(0xcc3333, 0x3333cc)), ExpectedPage.Ready(120, 180)),
        ImageCase("codestream.jxl", byteArrayOf(0xff.toByte(), 0x0a) + ByteArray(64), ExpectedPage.OneOf(listOf(ExpectedPage.Fails(Failure.Corrupt), ExpectedPage.Fails(Failure.Truncated)))),
        ImageCase("tall.png", tallPng(800, 50_000), ExpectedPage.Ready(800, 50_000)),
        // BitmapRegionDecoder does not read GIF, so its tiles are cut from one bitmap.
        ImageCase("tall.gif", encode(indexed(200, 1000), "gif"), ExpectedPage.Ready(200, 1000)),
        // Stored wide and turned upright by EXIF, which the tiles must do themselves.
        ImageCase("tall-rotated.jpg", withExifOrientation(jpeg(quadrants(1500, 300)), 6), ExpectedPage.Ready(300, 1500)),
        ImageCase("rotated.jpg", withExifOrientation(jpeg(quadrants(600, 400)), 8), ExpectedPage.Ready(400, 600)),
    ) + listOf(1, 4, 8, 16, 24, 32).map { bits ->
        ImageCase("bmp-%02d-bit.bmp".format(bits), bmp(203, 301, bits), ExpectedPage.Ready(203, 301))
    } + heifCases()
}

/**
 * Files that are HEIF and AVIF by their `ftyp` brands and hold nothing else. The JDK has no encoder for either,
 * so these check only that a decoder error on them reads as a missing decoder (docs/spec.md section 3.6).
 */
private fun heifCases() = listOf(
    ImageCase("ftyp-only.heic", ftypOnly("heic", "mif1", "heic"), ExpectedPage.ImageFeature("HEIF")),
    ImageCase("ftyp-only.avif", ftypOnly("avif", "mif1", "avif"), ExpectedPage.ImageFeature("AVIF")),
)

/** Four flat colors, one a quadrant, so that a turned or mirrored part shows in the wrong colors. */
private fun quadrants(width: Int, height: Int): BufferedImage {
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    val colors = intArrayOf(0xd03030, 0x30d030, 0x3030d0, 0xe0e0e0)
    for (y in 0 until height) for (x in 0 until width) {
        image.setRGB(x, y, colors[(if (x < width / 2) 0 else 1) + (if (y < height / 2) 0 else 2)])
    }
    return image
}

private fun rotated(image: BufferedImage): BufferedImage {
    val out = BufferedImage(image.height, image.width, image.type, image.colorModel as IndexColorModel)
    for (y in 0 until image.height) for (x in 0 until image.width) out.raster.setSample(y, x, 0, image.raster.getSample(x, y, 0))
    return out
}

/** A grayscale halftone [width] x [height], taller than 1:3, which the reader shows as tiles (section 3.7). */
private fun tallPng(width: Int, height: Int): ByteArray {
    val image = BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY)
    val raster = image.raster
    for (y in 0 until height) for (x in 0 until width) {
        raster.setSample(x, y, 0, gray(halftoneLevel(x, y % 2000, width, 2000)))
    }
    return png(image)
}
