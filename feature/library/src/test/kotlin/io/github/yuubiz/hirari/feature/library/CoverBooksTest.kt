package io.github.yuubiz.hirari.feature.library

import io.github.yuubiz.hirari.core.archive.ExtractionCache
import io.github.yuubiz.hirari.core.model.BookId
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoverBooksTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun aCoverOfABookNobodyExtractedGetsNoDirectoryAndMakesNone() {
        assertNull(coverExtractDir(temp.root, BookId("book"), 100, 1_000))
        assertFalse(File(temp.root, "extract").exists(), "a cover made the extraction cache")
    }

    @Test
    fun aCoverReadsFromTheDirectoryTheReaderExtractsInto() {
        val extracted = ExtractionCache.directory(temp.root, BookId("book"), 100, 1_000).apply { mkdirs() }
        assertEquals(extracted, coverExtractDir(temp.root, BookId("book"), 100, 1_000))
        assertNull(coverExtractDir(temp.root, BookId("book"), 100, 2_000))
        assertNull(coverExtractDir(temp.root, BookId("book"), 200, 1_000))
    }
    @Test fun fileEditsLeaveExtractedEntriesAndUnfinishedFilesForTheNextTrim() {
        val directory = ExtractionCache.directory(temp.root, BookId("book"), 100, 1_000).apply { mkdirs() }
        File(directory, "0").writeText("page")
        File(directory, "1.part").writeText("extracting")
        removeExtracted(temp.root, setOf(BookId("book")))
        assertTrue(File(directory, "0").isFile)
        assertTrue(File(directory, "1.part").isFile)
    }

}
