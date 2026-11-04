package io.github.yuubiz.hirari.feature.library

import io.github.yuubiz.hirari.core.model.BookId
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class CoverBooksTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun aCoverOfABookNobodyExtractedGetsNoDirectoryAndMakesNone() {
        assertNull(coverExtractDir(temp.root, BookId("book")))
        assertFalse(File(temp.root, "extract").exists(), "a cover made the extraction cache")
    }

    @Test
    fun aCoverReadsFromTheDirectoryTheReaderExtractsInto() {
        val extracted = File(temp.root, "extract/book").apply { mkdirs() }
        assertEquals(extracted, coverExtractDir(temp.root, BookId("book")))
    }
}
