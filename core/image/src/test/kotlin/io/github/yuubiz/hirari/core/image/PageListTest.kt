package io.github.yuubiz.hirari.core.image

import io.github.yuubiz.hirari.core.model.EntryOrder
import io.github.yuubiz.hirari.core.model.PageEntry
import org.junit.Assert.assertEquals
import org.junit.Test

class PageListTest {
    @Test
    fun imageExtensionsAreIncludedInAnyCase() {
        listOf("001.jpg", "a/b/002.JPEG", "x.png", "x.WebP", "x.gif", "x.bmp", "x.heic", "x.avif", "x.jxl", "x.jfif").forEach {
            assertEquals(it, PageCandidate.Image, pageCandidate(it, isDirectory = false))
        }
    }

    @Test
    fun junkTextAndFoldersAreExcluded() {
        listOf(
            "ch1/",
            "__MACOSX/._001.jpg",
            "__MACOSX/ch1/001.jpg",
            "__macosx/cover.jpg",
            ".hidden.jpg",
            "ch1/.DS_Store",
            ".git/001.jpg",
            "Thumbs.db",
            "ch1/desktop.ini",
            "readme.txt",
            "info.NFO",
            "ComicInfo.xml",
            "check.sfv",
            "check.md5",
            "link.url",
        ).forEach { assertEquals(it, PageCandidate.Excluded, pageCandidate(it, isDirectory = false)) }
        assertEquals(PageCandidate.Excluded, pageCandidate("001.jpg", isDirectory = true))
    }

    @Test
    fun aMissingOrUnknownExtensionIsSniffed() {
        listOf("001", "page.dat", "scan.jpg.bak", "v1.0/001").forEach {
            assertEquals(it, PageCandidate.Sniff, pageCandidate(it, isDirectory = false))
        }
    }

    @Test
    fun pagesAreInNaturalOrderAndNumberedInThatOrder() {
        val names = listOf("10.jpg", "2.jpg", "1.jpg", "ch2/1.jpg", "ch10/1.jpg")
        val entries = names.mapIndexed { i, name ->
            PageEntry(index = -1, entryIndex = i, rawName = name.toByteArray(), name = name, declaredSize = null, sniffed = null)
        }
        val pages = sortedPages(entries)
        assertEquals(listOf("1.jpg", "2.jpg", "10.jpg", "ch2/1.jpg", "ch10/1.jpg"), pages.map { it.name })
        assertEquals(listOf(0, 1, 2, 3, 4), pages.map { it.index })
        assertEquals(listOf(2, 1, 0, 3, 4), pages.map { it.entryIndex })
        val reversed = sortedPages(entries, PageOrder(reversed = true))
        assertEquals(listOf("ch10/1.jpg", "ch2/1.jpg", "10.jpg", "2.jpg", "1.jpg"), reversed.map { it.name })
        assertEquals(listOf(0, 1, 2, 3, 4), reversed.map { it.index })
    }

    @Test
    fun theStoredOrderIsTheOrderOfTheEntriesInTheArchive() {
        val names = listOf("10.jpg", "2.jpg", "1.jpg")
        val entries = names.mapIndexed { i, name ->
            PageEntry(index = -1, entryIndex = i * 2, rawName = name.toByteArray(), name = name, declaredSize = null, sniffed = null)
        }.shuffled(kotlin.random.Random(1))
        val stored = sortedPages(entries, PageOrder(EntryOrder.Stored))
        assertEquals(names, stored.map { it.name })
        assertEquals(listOf(0, 1, 2), stored.map { it.index })
        assertEquals(names.reversed(), sortedPages(entries, PageOrder(EntryOrder.Stored, reversed = true)).map { it.name })
    }
}
