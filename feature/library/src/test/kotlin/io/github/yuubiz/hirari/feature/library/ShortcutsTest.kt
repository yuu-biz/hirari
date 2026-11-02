package io.github.yuubiz.hirari.feature.library

import io.github.yuubiz.hirari.core.model.BookAddress
import io.github.yuubiz.hirari.core.model.LibraryLocation
import io.github.yuubiz.hirari.core.model.LibrarySort
import io.github.yuubiz.hirari.core.model.LocationId
import io.github.yuubiz.hirari.core.model.OpenBookRequest
import io.github.yuubiz.hirari.core.model.UriString
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ShortcutsTest {
    private val fixture = Fixture()
    private val place = LibraryLocation.SafTree(LocationId(1), "漫画", UriString("content://tree/manga"))
    private val server = LibraryLocation.SmbShare(LocationId(2), "NAS", "nas.local", 445, "books", "", null, null, null)

    private fun file(name: String) = FolderEntry(name, "doc:作品/$name", directory = false, size = 1, modified = null)

    private suspend fun resolve(location: LocationId, path: String, folder: Boolean) = fixture.services.resolve(PlaceShortcut(location, path, folder, ""))

    @Test
    fun `a shortcut opens its folder, book or text when they are still there`() = runTest {
        fixture.locations.rows.value = listOf(place)
        fixture.lister.folders["作品"] = listOf(file("1巻.zip"), file("後書き.txt"))

        assertEquals(ShortcutTarget.Folder(FolderKey(place.id, "作品", "doc:作品")), resolve(place.id, "作品", folder = true))
        assertEquals(
            ShortcutTarget.Book(OpenBookRequest(BookAddress.InLocation(place.id, "作品/1巻.zip", folder = false, "doc:作品/1巻.zip"))),
            resolve(place.id, "作品/1巻.zip", folder = false),
        )
        assertEquals(
            ShortcutTarget.Text(TextAddress.Document(UriString("content://tree/manga/document/doc:作品/後書き.txt"))),
            resolve(place.id, "作品/後書き.txt", folder = false),
        )
    }

    @Test
    fun `a shortcut whose place, grant, folder or book is gone finds nothing`() = runTest {
        assertEquals(ShortcutTarget.Missing, resolve(place.id, "作品", folder = true))
        fixture.locations.rows.value = listOf(place.copy(permissionHeld = false))
        assertEquals(ShortcutTarget.Missing, resolve(place.id, "", folder = true))
        fixture.locations.rows.value = listOf(place)
        fixture.lister.folders["作品"] = listOf(file("1巻.zip"))
        assertEquals(ShortcutTarget.Missing, resolve(place.id, "外伝", folder = true))
        assertEquals(ShortcutTarget.Missing, resolve(place.id, "作品/2巻.zip", folder = false))
    }

    @Test
    fun `a SAF folder whose grant is gone though the place still says held finds nothing`() = runTest {
        fixture.locations.rows.value = listOf(place)
        fixture.lister.failure = ListFailure.NoPermission
        assertEquals(ShortcutTarget.Missing, resolve(place.id, "作品", folder = true))
    }

    @Test
    fun `an SMB folder opens even when the server does not answer, so its list says why`() = runTest {
        fixture.locations.rows.value = listOf(server)
        fixture.smb.failure = ListFailure.Unreachable
        assertEquals(ShortcutTarget.Folder(FolderKey(server.id, "作品")), resolve(server.id, "作品", folder = true))
    }

    @Test
    fun `the sheet offers a shortcut for folders, and for books and texts of a SAF place only`() {
        val rows = listOf(FolderEntry("外伝", "doc:外伝", directory = true, size = null, modified = null), file("1巻.zip"), file("p01.jpg"))
            .toRows("作品", Ordering(LibrarySort.Name, foldersFirst = true, showDotFiles = false))
        assertEquals(PlaceShortcut(place.id, "作品/外伝", folder = true, "外伝"), shortcutFor(rows[0], place.id, place))
        assertEquals(PlaceShortcut(place.id, "作品/1巻.zip", folder = false, "1巻.zip"), shortcutFor(rows[1], place.id, place))
        assertNull(shortcutFor(rows[2], place.id, place))
        assertNull(shortcutFor(rows[1], server.id, server))
        assertEquals(PlaceShortcut(server.id, "作品/外伝", folder = true, "外伝"), shortcutFor(rows[0], server.id, server))
    }
}
