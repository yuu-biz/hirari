package io.github.yuubiz.hirari

import io.github.yuubiz.hirari.core.model.BookEdge
import io.github.yuubiz.hirari.core.model.BookId
import io.github.yuubiz.hirari.core.model.LocationId
import io.github.yuubiz.hirari.core.model.Sibling
import io.github.yuubiz.hirari.feature.library.FolderEntry
import io.github.yuubiz.hirari.feature.text.EntryArchive
import io.github.yuubiz.hirari.feature.text.TextBookRef
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

private fun siblings(listings: Map<String, List<FolderEntry>>, dotFiles: Boolean = false) =
    TextFolderSiblings(placeFolders({ if (it == PLACE) SAF_PLACE else null }, Folders(listings), Shares(emptyMap())), dotFiles = { dotFiles })

private fun text(path: String) = TextBookRef.InLocation(PLACE, path)

private fun found(path: String) = Sibling.Found(TextBookRef.InLocation(PLACE, path, "doc:${path.substringAfterLast('/')}"))

class TextFolderSiblingsTest {
    private val series = mapOf(
        "作品" to listOf(
            file("第10話.txt"), file("第2話.txt"), file("第1話.txt"), file("第3巻.zip"), file("表紙.jpg"), file("説明.pdf"),
            folder("番外"), file(".下書き.txt"),
        ),
        "作品/番外" to listOf(file("おまけ.txt")),
    )

    @Test
    fun theNextTextIsTheNextTextInNaturalOrder() = runBlocking {
        assertEquals(found("作品/第2話.txt"), siblings(series).beside(text("作品/第1話.txt"), BookEdge.End))
        assertEquals(found("作品/第10話.txt"), siblings(series).beside(text("作品/第2話.txt"), BookEdge.End))
        assertEquals(found("作品/第1話.txt"), siblings(series).beside(text("作品/第2話.txt"), BookEdge.Start))
    }

    @Test
    fun foldersArchivesAndImagesAreSkipped() = runBlocking {
        assertEquals(Sibling.Last, siblings(series).beside(text("作品/第10話.txt"), BookEdge.End))
        assertEquals(Sibling.Last, siblings(series).beside(text("作品/第1話.txt"), BookEdge.Start))
    }

    @Test
    fun aTextAloneInItsFolderHasNeitherNeighbor() = runBlocking {
        assertEquals(Sibling.Last, siblings(series).beside(text("作品/番外/おまけ.txt"), BookEdge.End))
        assertEquals(Sibling.Last, siblings(series).beside(text("作品/番外/おまけ.txt"), BookEdge.Start))
    }

    @Test
    fun hiddenFilesCountOnlyWhenTheListShowsThem() = runBlocking {
        assertEquals(Sibling.Last, siblings(series).beside(text("作品/第1話.txt"), BookEdge.Start))
        assertEquals(found("作品/.下書き.txt"), siblings(series, dotFiles = true).beside(text("作品/第1話.txt"), BookEdge.Start))
    }

    @Test
    fun aTextWithoutAFolderToListHasNoNeighbors() = runBlocking {
        val siblings = siblings(series)
        assertEquals(Sibling.NoFolder, siblings.beside(TextBookRef.InLocation(LocationId(9), "作品/第1話.txt"), BookEdge.End))
        assertEquals(Sibling.NoFolder, siblings.beside(text("消えた/本.txt"), BookEdge.End))
        assertEquals(Sibling.NoFolder, siblings.beside(text("作品/ない.txt"), BookEdge.End))
        assertEquals(
            Sibling.NoFolder,
            siblings.beside(TextBookRef.ArchiveEntry(EntryArchive.InLocation(PLACE, "作品/第3巻.zip"), 0, BookId("zip")), BookEdge.End),
        )
    }

    @Test
    fun aTextOfAnSmbPlaceFindsItsNeighborsInTheListingOfItsServer() = runBlocking {
        val shares = Shares(mapOf("作品" to listOf(smbFile("第10話.txt"), smbFile("第2話.txt"), smbFile("第1巻.zip"), smbFolder("番外"))))
        val siblings = TextFolderSiblings(placeFolders({ if (it == SMB_PLACE.id) SMB_PLACE else null }, Folders(emptyMap()), shares), dotFiles = { false })
        val place = SMB_PLACE.id

        assertEquals(
            Sibling.Found(TextBookRef.InLocation(place, "作品/第10話.txt")),
            siblings.beside(TextBookRef.InLocation(place, "作品/第2話.txt"), BookEdge.End),
        )
        assertEquals(Sibling.Last, siblings.beside(TextBookRef.InLocation(place, "作品/第2話.txt"), BookEdge.Start))
        assertEquals(setOf(SMB_PLACE), shares.listed.map { it.first }.toSet())
        assertEquals(setOf("作品"), shares.listed.map { it.second }.toSet())
    }
}
