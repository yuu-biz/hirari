package io.github.yuubiz.hirari.tools.corpus

import io.github.yuubiz.hirari.core.model.NaturalOrder
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ImageCorpusTest {
    @Test
    fun `every image the JDK can read has the size its page expects`() {
        val ready = imageCases().filter { it.page is ExpectedPage.Ready && !it.name.endsWith(".webp") }
        assertTrue(ready.size >= 20)
        for (case in ready) {
            val image = ImageIO.read(case.bytes.inputStream()) ?: error("the JDK cannot read ${case.name}")
            val page = case.page as ExpectedPage.Ready
            // The JDK ignores EXIF orientation, so a turned image reads at its stored size.
            val shown = if ("rotated" in case.name) image.height to image.width else image.width to image.height
            assertEquals(page.width to page.height, shown, case.name)
        }
    }

    @Test
    fun `each bmp has the bit depth its name gives`() {
        for (case in imageCases().filter { it.name.startsWith("bmp-") }) {
            val bits = (case.bytes[28].toInt() and 0xff) or (case.bytes[29].toInt() shl 8)
            assertEquals(case.name.substring(4, 6).toInt(), bits, case.name)
        }
    }

    @Test
    fun `the flat jpegs differ from the baseline one only in the frame marker and precision`() {
        val baseline = flatJpeg(64, 96, JpegProcess.Baseline)
        val twelve = flatJpeg(64, 96, JpegProcess.Extended12Bit)
        val frame = (0 until baseline.size - 1).first { baseline[it] == 0xff.toByte() && baseline[it + 1] == 0xc0.toByte() }
        assertEquals(0xc1.toByte(), twelve[frame + 1])
        assertEquals(12.toByte(), twelve[frame + 4])
        assertEquals(baseline.size, twelve.size)
    }

    @Test
    fun `an animated webp has an animation header and one frame per color`() {
        val bytes = animatedWebp(120, 180, listOf(0xcc3333, 0x3333cc))
        val text = String(bytes, Charsets.ISO_8859_1)
        assertEquals(listOf("RIFF", "WEBP", "VP8X"), listOf(text.substring(0, 4), text.substring(8, 12), text.substring(12, 16)))
        assertEquals(bytes.size - 8, (0..3).sumOf { (bytes[4 + it].toInt() and 0xff) shl (8 * it) })
        assertEquals(2, Regex("ANMF").findAll(text).count())
    }

    @Test
    fun `the pages manifest lists the images in natural order and leaves out the text file`() {
        val lines = pagesManifest(corpusCases()).lines().filter { it.isNotEmpty() }.map { it.split('\t') }
        val start = lines.indexOf(listOf("book", "zip/images.zip", "opens", "-"))
        val pages = lines.drop(start + 1).takeWhile { it[0] == "page" }.map { it[1] }
        assertEquals(imageCases().map { it.name }.sortedWith(NaturalOrder), pages)
        assertTrue("notes.txt" !in pages)
        assertEquals(listOf("book", "zip/not-a-zip.zip", "fails", "corrupt"), lines.single { it[1] == "zip/not-a-zip.zip" })
    }

    @Test
    fun `a standard page is a JPEG of the size in section 5_1 near 1_5 MB`() {
        for (page in listOf(0, 1, 2, 9)) {
            val bytes = standardPage(page)
            val image = ImageIO.read(bytes.inputStream())
            val width = if (isStandardSpread(page)) 3200 else 1600
            assertEquals(width to 2400, image.width to image.height)
            val perPage = bytes.size * 1600L / width
            assertTrue(perPage in 1_200_000..1_800_000, "page $page is ${bytes.size} bytes")
        }
        assertEquals(STANDARD_SPREADS, (0 until STANDARD_PAGES).count(::isStandardSpread))
    }
}
