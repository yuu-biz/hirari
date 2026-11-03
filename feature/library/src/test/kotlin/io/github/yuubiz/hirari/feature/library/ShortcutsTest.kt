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

    private fun serverFile(name: String) = FolderEntry(name, null, directory = false, size = 1, modified = null)

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
            ShortcutTarget.Text(TextAddress.InLocation(place.id, "作品/後書き.txt", "doc:作品/後書き.txt")),
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
    fun `an SMB book or text opens its folder when the folder does not list, so the list says why`() = runTest {
        fixture.locations.rows.value = listOf(server)
        for (reason in listOf(ListFailure.Unreachable, ListFailure.AuthenticationFailed, ListFailure.PasswordUnreadable, ListFailure.NoLocalNetwork, ListFailure.ReadError)) {
            fixture.smb.failure = reason
            assertEquals(ShortcutTarget.Folder(FolderKey(server.id, "作品")), resolve(server.id, "作品/1巻.zip", folder = false), "$reason")
            assertEquals(ShortcutTarget.Folder(FolderKey(server.id, "作品")), resolve(server.id, "作品/後書き.txt", folder = false), "$reason")
        }
        fixture.smb.failure = ListFailure.Unreachable
        assertEquals(ShortcutTarget.Folder(FolderKey(server.id, "")), resolve(server.id, "1巻.zip", folder = false))
    }

    @Test
    fun `a book whose folder is gone or refused finds nothing`() = runTest {
        fixture.locations.rows.value = listOf(server, place)
        fixture.smb.failure = ListFailure.NotFound
        assertEquals(ShortcutTarget.Missing, resolve(server.id, "作品/1巻.zip", folder = false))
        fixture.lister.failure = ListFailure.NoPermission
        assertEquals(ShortcutTarget.Missing, resolve(place.id, "作品/1巻.zip", folder = false))
    }

    @Test
    fun `an SMB shortcut opens its book or text by place and path`() = runTest {
        fixture.locations.rows.value = listOf(server)
        fixture.smb.folders["作品"] = listOf(serverFile("1巻.zip"), serverFile("後書き.txt"))

        assertEquals(
            ShortcutTarget.Book(OpenBookRequest(BookAddress.InLocation(server.id, "作品/1巻.zip", folder = false))),
            resolve(server.id, "作品/1巻.zip", folder = false),
        )
        assertEquals(ShortcutTarget.Text(TextAddress.InLocation(server.id, "作品/後書き.txt")), resolve(server.id, "作品/後書き.txt", folder = false))
        assertEquals(ShortcutTarget.Missing, resolve(server.id, "作品/2巻.zip", folder = false))
    }

    @Test
    fun `the sheet offers a shortcut for folders, books and texts of a place of either kind, but not for images`() {
        val rows = listOf(FolderEntry("外伝", "doc:外伝", directory = true, size = null, modified = null), file("1巻.zip"), file("p01.jpg"), file("後書き.txt"))
            .toRows("作品", Ordering(LibrarySort.Name, foldersFirst = true, showDotFiles = false))
            .associateBy { it.path }
        for (location in listOf(place.id, server.id)) {
            assertEquals(PlaceShortcut(location, "作品/外伝", folder = true, "外伝"), shortcutFor(rows.getValue("作品/外伝"), location))
            assertEquals(PlaceShortcut(location, "作品/1巻.zip", folder = false, "1巻.zip"), shortcutFor(rows.getValue("作品/1巻.zip"), location))
            assertEquals(PlaceShortcut(location, "作品/後書き.txt", folder = false, "後書き.txt"), shortcutFor(rows.getValue("作品/後書き.txt"), location))
            assertNull(shortcutFor(rows.getValue("作品/p01.jpg"), location))
        }
    }
}
