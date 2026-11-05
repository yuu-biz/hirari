package io.github.yuubiz.hirari

import io.github.yuubiz.hirari.core.model.BookAddress
import io.github.yuubiz.hirari.core.model.LocationId
import io.github.yuubiz.hirari.core.model.UriString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReaderReturnTest {
    @Test
    fun nextBookReturnsItsOwnPathToTheFolderBelowTheReplacedReader() {
        val folder = FolderRoute(1, "series", reveal = "series/01.zip")
        val first = BookAddress.InLocation(LocationId(1), "series/01.zip", folder = false)
        val next = first.copy(path = "series/99.zip")
        assertEquals("series/01.zip", folder.readerReveal(first))
        assertEquals("series/99.zip", folder.readerReveal(next))
        assertEquals("series/01.zip", folder.readerReveal(first))
    }

    @Test
    fun onlyBooksInsideTheSamePlaceReturnARowToReveal() {
        val folder = FolderRoute(1, "series")
        assertNull(folder.readerReveal(BookAddress.Document(UriString("content://book"))))
        assertNull(folder.readerReveal(BookAddress.InLocation(LocationId(2), "series/99.zip", false)))
        assertNull(folder.readerReveal(BookAddress.InLocation(LocationId(1), "other/99.zip", false)))
        assertEquals("series/child", folder.readerReveal(BookAddress.InLocation(LocationId(1), "series/child/99.zip", false)))
    }
}
