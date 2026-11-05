package io.github.yuubiz.hirari.feature.library

import io.github.yuubiz.hirari.core.data.BookmarkGroup
import io.github.yuubiz.hirari.core.data.BookmarkScope
import io.github.yuubiz.hirari.core.data.GlobalSettings
import io.github.yuubiz.hirari.core.model.Book
import io.github.yuubiz.hirari.core.model.Bookmark
import io.github.yuubiz.hirari.core.model.LibraryLocation
import io.github.yuubiz.hirari.core.model.LocationId
import io.github.yuubiz.hirari.core.model.ReadState
import io.github.yuubiz.hirari.core.model.ReadingPosition
import io.github.yuubiz.hirari.core.model.UriString
import io.github.yuubiz.hirari.core.model.locationBookId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryGapsTest {
    private val fixture = Fixture()
    private val place = LibraryLocation.SafTree(LocationId(1), "books", UriString("content://books"))
    private val server = LibraryLocation.SmbShare(LocationId(2), "NAS", "nas", 445, "books", "", null, null, null)
    @Before fun main() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun reset() = Dispatchers.resetMain()
    private fun home() = HomeViewModel(CompletableDeferred(fixture.services), UnconfinedTestDispatcher())
    private fun group(book: Book, count: Int = 1) = BookmarkGroup(book.id, book, null,
        (1..count).map { Bookmark(it.toLong(), book.id, ReadingPosition.Image(0, 10), "mark", T0) })

    @Test fun `image folder books offer restart and read state actions but ordinary folders do not`() {
        val row = FileRow(FileItem.Folder(FolderEntry("images", "doc:images", true, null, null)), "images", "images")
        fun actions(row: FileRow, recognised: Boolean = false) = rowSheetActions(row, canReadFolder = recognised).map { it.first }
        assertFalse(SheetAction.MarkRead in actions(row))
        assertTrue(SheetAction.MarkRead in actions(row, true))
        val reading = actions(row.copy(mark = ReadMark.Reading(50)), true)
        assertTrue(reading.containsAll(listOf(SheetAction.FromStart, SheetAction.MarkRead, SheetAction.MarkUnread)))
        assertTrue(SheetAction.FromStart in actions(row.copy(mark = ReadMark.Read)))
        assertFalse(SheetAction.MarkRead in actions(row.copy(mark = ReadMark.Read)))
        assertFalse(SheetAction.MarkUnread in actions(row.copy(mark = ReadMark.Unread)))
    }

    @Test fun `image folder marking uses the same book id as opening`() = runTest {
        fixture.locations.rows.value = listOf(place)
        fixture.lister.folders[""] = listOf(FolderEntry("images", "doc:images", true, null, null))
        fixture.lister.folders["images"] = listOf(FolderEntry("1.jpg", "doc:1", false, 10, T0))
        val model = FolderViewModel(CompletableDeferred(fixture.services), FolderKey(place.id, ""), UnconfinedTestDispatcher(testScheduler))
        watch(model.state)
        val row = (model.state.value.content as FolderContent.Shown).rows.single()
        val request = model.folderBook(row)!!
        assertEquals(io.github.yuubiz.hirari.core.model.BookAddress.InLocation(place.id, "images", true, "doc:images"), request.book)
        model.mark(row, ReadState.Read)
        assertEquals(ReadState.Read, fixture.books.progressRepository.observe(locationBookId(place, "images")).first()?.state)
        assertEquals(ReadMark.Read, (model.state.value.content as FolderContent.Shown).rows.single().mark)
        model.mark(row, ReadState.Unread)
        assertEquals(ReadMark.Unread, (model.state.value.content as FolderContent.Shown).rows.single().mark)
    }

    @Test fun `bulk deletion previews counts and passes each scope to the repository without storage queries`() = runTest {
        fixture.locations.rows.value = listOf(place, server)
        val first = book(place.id, "series/one.cbz")
        val nested = book(place.id, "series/sub/two.cbz")
        fixture.bookmarks.groups = listOf(group(first, 2), group(nested), group(book(place.id, "series-other/three.cbz")), group(book(server.id, "remote.cbz")))
        val home = home()
        val cases = listOf(BookmarkScope.All to 5, BookmarkScope.UnderFolder(place.id, "series") to 3,
            BookmarkScope.UnderFolder(place.id, "series/sub") to 1, BookmarkScope.InLocation(place.id) to 4, BookmarkScope.InLocation(server.id) to 1, BookmarkScope.OnDevice to 4)
        cases.forEach { (scope, count) ->
            val plan = home.bookmarkDeletion(scope)
            assertEquals(count, plan.count)
            assertTrue(fixture.bookmarks.deletedScopes.size == cases.indexOf(scope to count))
            home.deleteBookmarks(plan)
            assertEquals(scope, fixture.bookmarks.deletedScopes.last())
        }
        assertEquals(0, fixture.lister.calls)
        assertTrue(fixture.smb.listed.isEmpty())
    }

    @Test fun `missing excludes offline permission lost external and unindexed books`() = runTest {
        val denied = place.copy(id = LocationId(3), permissionHeld = false)
        fixture.locations.rows.value = listOf(place, server, denied)
        fixture.lister.folders[""] = listOf(FolderEntry("exists.cbz", null, false, 1, T0))
        fixture.smb.lost = true
        val missing = book(place.id, "missing.cbz")
        val deleted = book(LocationId(99), "deleted.cbz")
        fixture.bookmarks.groups = listOf(group(missing, 2), group(deleted), group(book(place.id, "exists.cbz")),
            group(book(server.id, "offline.cbz")), group(book(denied.id, "denied.cbz")),
            group(book(place.id, "external.cbz").copy(source = io.github.yuubiz.hirari.core.model.BookSource.External(UriString("content://external/book")))),
            BookmarkGroup(io.github.yuubiz.hirari.core.model.BookId("unindexed"), null, null, emptyList()))
        val home = home()
        val plan = home.bookmarkDeletion(null)
        assertEquals(3, plan.count)
        assertEquals(setOf(missing.id, deleted.id), (plan.scope as BookmarkScope.Books).books.toSet())
        assertTrue(fixture.bookmarks.deletedScopes.isEmpty())
        home.deleteBookmarks(plan)
        assertEquals(plan.scope, fixture.bookmarks.deletedScopes.single())
        fixture.lister.failure = ListFailure.NoPermission
        assertEquals(setOf(deleted.id), (home.bookmarkDeletion(null).scope as BookmarkScope.Books).books.toSet())
    }

    @Test fun `shelf unpins absent folders and unavailable places without storage access`() = runTest {
        fixture.smb.lost = true
        fixture.locations.pin(server.id, "gone", T0)
        fixture.locations.pin(place.id, "deleted-place", T0)
        val home = home()
        fixture.locations.pinned.first().forEach { home.unpin(PinnedItem(it, null)) }
        assertTrue(fixture.locations.pinned.first().isEmpty())
        assertEquals(0, fixture.lister.calls)
        assertTrue(fixture.smb.listed.isEmpty())
    }

    @Test fun `name and author lines never exceed three including normalization`() {
        assertEquals(2, ListLook(nameLines = 3, noteLine = true).bodyLines)
        assertEquals(3, ListLook(nameLines = 3, noteLine = false).bodyLines)
        assertEquals(2, GlobalSettings(gridNameLines = 3, gridNoteLine = true).normalized().gridNameLines)
        assertEquals(3, GlobalSettings(gridNameLines = 3, gridNoteLine = false).normalized().gridNameLines)
        for (lines in -1..5) for (note in listOf(false, true)) {
            val settings = GlobalSettings(gridNameLines = lines, gridNoteLine = note).normalized()
            assertTrue(settings.gridNameLines + (if (note) 1 else 0) <= 3)
            assertTrue(settings.listLook().bodyLines + (if (note) 1 else 0) <= 3)
        }
    }
}
