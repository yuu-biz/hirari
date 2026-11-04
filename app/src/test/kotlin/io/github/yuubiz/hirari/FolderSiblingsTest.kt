package io.github.yuubiz.hirari

import io.github.yuubiz.hirari.core.data.SmbServer as ServerForm
import io.github.yuubiz.hirari.core.model.BookAddress
import io.github.yuubiz.hirari.core.model.CredentialRef
import io.github.yuubiz.hirari.core.model.LibraryLocation
import io.github.yuubiz.hirari.core.model.LocationId
import io.github.yuubiz.hirari.core.model.UriString
import io.github.yuubiz.hirari.core.source.ByteSource
import io.github.yuubiz.hirari.core.source.save.SaveFolder
import io.github.yuubiz.hirari.core.source.save.SaveOutcome
import io.github.yuubiz.hirari.core.source.save.SaveProgress
import io.github.yuubiz.hirari.feature.library.FolderEntry
import io.github.yuubiz.hirari.feature.library.FolderLister
import io.github.yuubiz.hirari.feature.library.ListFailure
import io.github.yuubiz.hirari.feature.library.Listing
import io.github.yuubiz.hirari.feature.library.SmbPlaces
import io.github.yuubiz.hirari.feature.library.SmbTestResult
import io.github.yuubiz.hirari.core.model.BookEdge
import io.github.yuubiz.hirari.core.model.Sibling
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

internal val TREE = UriString("content://tree")
internal val PLACE = LocationId(1)

internal fun file(name: String) = FolderEntry(name, "doc:$name", directory = false, size = 1, modified = null)

internal fun folder(name: String) = FolderEntry(name, "doc:$name", directory = true, size = null, modified = null)

private fun Map<String, List<FolderEntry>>.listing(path: String, documentId: String?): Listing =
    this[path]?.let { Listing.Listed(documentId, it) } ?: Listing.Failed(ListFailure.NotFound)

internal class Folders(private val listings: Map<String, List<FolderEntry>>) : FolderLister {
    override suspend fun list(tree: UriString, path: String, documentId: String?): Listing =
        if (tree == TREE) listings.listing(path, "doc:$path") else Listing.Failed(ListFailure.NotFound)
}

/** The folders of one SMB place, counted from the place's folder, as [SmbPlaces.list] gives them. */
internal class Shares(private val listings: Map<String, List<FolderEntry>>) : SmbPlaces {
    val listed = mutableListOf<Pair<LibraryLocation.SmbShare, String>>()

    override suspend fun list(place: LibraryLocation.SmbShare, path: String): Listing {
        listed += place to path
        return listings.listing(path, documentId = null)
    }

    override suspend fun reconnect(place: LibraryLocation.SmbShare) = error("not used")

    override fun reconnections(place: LibraryLocation.SmbShare): Flow<Unit> = error("not used")

    override suspend fun test(server: ServerForm, password: CharArray?, stored: CredentialRef?): SmbTestResult = error("not used")

    override suspend fun open(place: LibraryLocation.SmbShare, path: String): ByteSource = error("not used")

    override suspend fun save(place: LibraryLocation.SmbShare, path: String, directory: Boolean, destination: SaveFolder, onProgress: (SaveProgress) -> Unit): SaveOutcome =
        error("not used")

    override suspend fun forget(id: LocationId) = error("not used")
}

internal val SAF_PLACE = LibraryLocation.SafTree(PLACE, "漫画", TREE)
internal val SMB_PLACE = LibraryLocation.SmbShare(LocationId(2), "NAS", "nas.local", 445, "books", "manga", null, "reader", null)

private fun siblings(listings: Map<String, List<FolderEntry>>, dotFiles: Boolean = false) =
    FolderSiblings(placeFolders({ if (it == PLACE) SAF_PLACE else null }, Folders(listings), Shares(emptyMap())), dotFiles = { dotFiles })

private fun book(path: String, isFolder: Boolean = false) = BookAddress.InLocation(PLACE, path, isFolder)

internal fun smbFile(name: String) = FolderEntry(name, documentId = null, directory = false, size = 1, modified = null)

internal fun smbFolder(name: String) = FolderEntry(name, documentId = null, directory = true, size = null, modified = null)

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

    @Test
    fun aBookOfAnSmbPlaceFindsItsNeighborsInTheListingOfItsServer() = runBlocking {
        val shares = Shares(
            mapOf(
                "作品" to listOf(smbFile("第10巻.zip"), smbFile("第2巻.cbz"), smbFile("第1巻.pdf"), smbFolder("おまけ"), smbFolder("空")),
                "作品/おまけ" to listOf(smbFile("01.png")),
                "作品/空" to listOf(smbFile("説明.txt")),
            ),
        )
        val siblings = FolderSiblings(placeFolders({ if (it == SMB_PLACE.id) SMB_PLACE else null }, Folders(emptyMap()), shares), dotFiles = { false })
        val place = SMB_PLACE.id

        assertEquals(Sibling.Found(BookAddress.InLocation(place, "作品/第10巻.zip", false)), siblings.beside(BookAddress.InLocation(place, "作品/第2巻.cbz", false), BookEdge.End))
        assertEquals(Sibling.Found(BookAddress.InLocation(place, "作品/おまけ", true)), siblings.beside(BookAddress.InLocation(place, "作品/第1巻.pdf", false), BookEdge.Start))
        assertEquals(Sibling.Last, siblings.beside(BookAddress.InLocation(place, "作品/第10巻.zip", false), BookEdge.End))
        assertEquals(setOf(SMB_PLACE), shares.listed.map { it.first }.toSet())
        assertEquals(setOf("作品", "作品/空", "作品/おまけ"), shares.listed.map { it.second }.toSet())
    }
}
