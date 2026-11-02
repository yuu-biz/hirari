package io.github.yuubiz.hirari.tools.corpus

import io.github.yuubiz.hirari.core.model.NaturalOrder
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ImageCorpusTest {
    @Test
    fun `every image the JDK can read has the size its page expects`() {
        val ready = imageCases().filter { it.page is ExpectedPage.Ready }
        assertTrue(ready.size >= 10)
        for (case in ready) {
            val image = ImageIO.read(case.bytes.inputStream())
            val page = case.page as ExpectedPage.Ready
            assertEquals(page.width to page.height, image.width to image.height, case.name)
        }
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
        for (page in listOf(0, 9)) {
            val bytes = standardPage(page)
            val image = ImageIO.read(bytes.inputStream())
            val width = if (isStandardSpread(page)) 3200 else 1600
            assertEquals(width to 2400, image.width to image.height)
            val perPage = bytes.size * 1600L / width
            assertTrue(perPage in 1_000_000..2_000_000, "page $page is ${bytes.size} bytes")
        }
        assertEquals(STANDARD_SPREADS, (0 until STANDARD_PAGES).count(::isStandardSpread))
    }
}
