package io.github.yuubiz.hirari.tools.corpus

import java.awt.image.BufferedImage
import java.io.File
import java.time.LocalDateTime
import java.util.Random
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** The standard corpus of docs/spec.md section 5.1: 200 JPEG pages of 1600 x 2400, 10 of them 3200 x 2400 spreads. */
const val STANDARD_PAGES = 200
const val STANDARD_SPREADS = 10
const val STANDARD_ZIP = "standard.zip"

/** Every 20th page from the 10th is a spread. */
fun isStandardSpread(page: Int): Boolean = page % (STANDARD_PAGES / STANDARD_SPREADS) == STANDARD_SPREADS - 1

/**
 * Page [page] of the standard corpus: halftone dots with seeded noise, which brings a 1600 x 2400 page near
 * the 1.5 MB of a scanned one.
 */
fun standardPage(page: Int): ByteArray {
    val width = if (isStandardSpread(page)) 3200 else 1600
    val height = 2400
    val random = Random(page.toLong())
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    for (y in 0 until height) {
        for (x in 0 until width) {
            val level = halftoneLevel(x, y, width, height, cell = 5 + page % 3)
            val g = ((255 * (1 - level)) + random.nextGaussian() * NOISE).toInt().coerceIn(0, 255)
            image.setRGB(x, y, (g shl 16) or (g shl 8) or g)
        }
    }
    return jpeg(image, quality = 0.75f)
}

private const val NOISE = 7.0

object StandardCorpus {
    /** Writes [STANDARD_ZIP] into the directory given as the only argument. Entries are stored, as JPEG barely shrinks. */
    @JvmStatic
    fun main(args: Array<String>) {
        val dir = File(args.single()).apply { mkdirs() }
        ZipOutputStream(dir.resolve(STANDARD_ZIP).outputStream().buffered()).use { zip ->
            for (page in 0 until STANDARD_PAGES) {
                val bytes = standardPage(page)
                zip.putNextEntry(
                    ZipEntry("%03d.jpg".format(page + 1)).apply {
                        method = ZipEntry.STORED
                        size = bytes.size.toLong()
                        crc = CRC32().apply { update(bytes) }.value
                        timeLocal = LocalDateTime.of(1980, 1, 1, 0, 0)
                    },
                )
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }
}
