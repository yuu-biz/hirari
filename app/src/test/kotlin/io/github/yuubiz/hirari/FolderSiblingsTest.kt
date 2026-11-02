package io.github.yuubiz.hirari

import io.github.yuubiz.hirari.core.model.BookAddress
import io.github.yuubiz.hirari.core.model.LocationId
import io.github.yuubiz.hirari.core.model.UriString
import io.github.yuubiz.hirari.feature.library.FolderEntry
import io.github.yuubiz.hirari.feature.library.FolderLister
import io.github.yuubiz.hirari.feature.library.ListFailure
import io.github.yuubiz.hirari.feature.library.Listing
import io.github.yuubiz.hirari.feature.reader.BookEdge
import io.github.yuubiz.hirari.feature.reader.Sibling
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

private val TREE = UriString("content://tree")
private val PLACE = LocationId(1)

private fun file(name: String) = FolderEntry(name, "doc:$name", directory = false, size = 1, modified = null)

private fun folder(name: String) = FolderEntry(name, "doc:$name", directory = true, size = null, modified = null)

private class Folders(private val listings: Map<String, List<FolderEntry>>) : FolderLister {
    override suspend fun list(tree: UriString, path: String, documentId: String?): Listing =
        listings[path]?.let { Listing.Listed("doc:$path", it) } ?: Listing.Failed(ListFailure.NotFound)
}

private fun siblings(listings: Map<String, List<FolderEntry>>, dotFiles: Boolean = false) =
    FolderSiblings(Folders(listings), treeOf = { if (it == PLACE) TREE else null }, dotFiles = { dotFiles })

private fun book(path: String, isFolder: Boolean = false) = BookAddress.InLocation(PLACE, path, isFolder)

class FolderSiblingsTest {
    private val series = mapOf(
        "作品" to listOf(file("第10巻.zip"), file("第2巻.cbz"), file("メモ.txt"), file("表紙.jpg"), file("第1巻.pdf"), folder("おまけ"), folder("空")),
        "作品/おまけ" to listOf(file("01.png")),
        "作品/空" to listOf(file("説明.txt")),
    )

    @Test
    fun theNextBookIsTheNextArchiveInNaturalOrder() = runBlocking {
        assertEquals(Sibling.Found(BookAddress.InLocation(PLACE, "作品/第10巻.zip", false, "doc:第10巻.zip")), siblings(series).beside(book("作品/第2巻.cbz"), BookEdge.End))
    }

    @Test
    fun foldersComeFirstAndOnlyThoseHoldingAnImageCount() = runBlocking {
        assertEquals(Sibling.Found(BookAddress.InLocation(PLACE, "作品/おまけ", true, "doc:おまけ")), siblings(series).beside(book("作品/第1巻.pdf"), BookEdge.Start))
        assertEquals(Sibling.Found(BookAddress.InLocation(PLACE, "作品/第1巻.pdf", false, "doc:第1巻.pdf")), siblings(series).beside(book("作品/おまけ", isFolder = true), BookEdge.End))
    }

    @Test
    fun theLastBookOfTheFolderHasNoNext() = runBlocking {
        assertEquals(Sibling.Last, siblings(series).beside(book("作品/第10巻.zip"), BookEdge.End))
        assertEquals(Sibling.Last, siblings(series).beside(book("作品/おまけ", isFolder = true), BookEdge.Start))
    }

    @Test
    fun aBookWithoutAFolderToListHasNoNeighbors() = runBlocking {
        assertEquals(Sibling.NoFolder, siblings(series).beside(BookAddress.Document(UriString("content://picked")), BookEdge.End))
        assertEquals(Sibling.NoFolder, siblings(series).beside(BookAddress.InLocation(LocationId(9), "作品/第2巻.cbz", false), BookEdge.End))
        assertEquals(Sibling.NoFolder, siblings(series).beside(book("消えた/本.zip"), BookEdge.End))
    }
}
