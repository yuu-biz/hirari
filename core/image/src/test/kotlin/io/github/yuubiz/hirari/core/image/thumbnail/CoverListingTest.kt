package io.github.yuubiz.hirari.core.image.thumbnail

import io.github.yuubiz.hirari.core.archive.ArchiveEntry
import io.github.yuubiz.hirari.core.archive.ListingNeed
import io.github.yuubiz.hirari.core.image.sortedPages
import io.github.yuubiz.hirari.core.model.PageEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverListingTest {
    private fun entry(name: ByteArray, isDirectory: Boolean = false) = ArchiveEntry(0, name, null, isDirectory, encrypted = false, compression = null)

    private fun counts(name: String, isDirectory: Boolean = false) =
        (CoverListing.automatic as ListingNeed.Images).isImage(entry(name.toByteArray(Charsets.UTF_8), isDirectory))

    @Test
    fun theAutomaticCoverCountsTheEntriesThePagesTakeByTheirNames() {
        assertEquals(5, (CoverListing.automatic as ListingNeed.Images).count)
        assertTrue(counts("vol1/001.JPG"))
        assertTrue(counts("表紙.webp"))
        assertFalse("a resource fork", counts("__MACOSX/vol1/._001.jpg"))
        assertFalse("a hidden file", counts("vol1/.cover.jpg"))
        assertFalse(counts("readme.txt"))
        assertFalse("a name that tells nothing is sniffed later, not counted", counts("page01"))
        assertFalse(counts("folder.jpg/", isDirectory = true))
    }

    @Test
    fun aCp932NameIsCountedByItsAsciiExtension() {
        assertTrue((CoverListing.automatic as ListingNeed.Images).isImage(entry("第1巻/表紙.jpg".toByteArray(charset("windows-31j")))))
    }

    private fun pages(vararg names: Pair<Int, String>) =
        sortedPages(names.map { (entryIndex, name) -> PageEntry(-1, entryIndex, name.toByteArray(), name, null, null) })

    private val found = pages(0 to "010.jpg", 1 to "002.jpg", 2 to "007.jpg")

    @Test
    fun aListingStoppedAtTheFirstImagesGivesTheFirstOfThemInNaturalOrderAsProvisional() {
        assertEquals(1, CoverListing.provisional(CoverListing.automatic, partial = true, pages = found))
    }

    @Test
    fun theProvisionalCoverIsAmongTheImagesTheListingCounted() {
        val sniffedFirst = pages(0 to "000.dat", 1 to "001.jpg", 2 to "002.jpg", 3 to "003.jpg", 4 to "004.jpg", 5 to "005.jpg")
        assertEquals(1, CoverListing.provisional(CoverListing.automatic, partial = true, pages = sniffedFirst))
        assertEquals("the cover shows the page remembered", 1, coverPage(sniffedFirst, null, listingFinal = true, partial = true)?.entryIndex)
        assertEquals("a whole listing keeps the page rules", 0, coverPage(sniffedFirst, null, listingFinal = true)?.entryIndex)
    }

    @Test
    fun aCompleteListingOrAChosenPageGivesNoProvisionalCover() {
        assertNull(CoverListing.provisional(CoverListing.automatic, partial = false, pages = found))
        assertNull(CoverListing.provisional(ListingNeed.UpTo(2), partial = true, pages = found))
        assertNull(CoverListing.provisional(ListingNeed.All, partial = false, pages = found))
        assertNull(CoverListing.provisional(CoverListing.automatic, partial = true, pages = emptyList()))
    }
}
