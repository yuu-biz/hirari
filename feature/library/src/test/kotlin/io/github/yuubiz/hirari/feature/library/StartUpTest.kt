package io.github.yuubiz.hirari.feature.library

import io.github.yuubiz.hirari.core.data.LastFolder
import io.github.yuubiz.hirari.core.data.LaunchRecord
import io.github.yuubiz.hirari.core.data.OpeningBook
import io.github.yuubiz.hirari.core.model.BookAddress
import io.github.yuubiz.hirari.core.model.BookFormat
import io.github.yuubiz.hirari.core.model.HistoryEntry
import io.github.yuubiz.hirari.core.model.LibraryLocation
import io.github.yuubiz.hirari.core.model.LibraryView
import io.github.yuubiz.hirari.core.model.LocationId
import io.github.yuubiz.hirari.core.model.OpenBookRequest
import io.github.yuubiz.hirari.core.model.ReopenLastBook
import io.github.yuubiz.hirari.core.model.UriString
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class StartUpTest {
    private val fixture = Fixture()
    private val place = LibraryLocation.SafTree(LocationId(1), "漫画", UriString("content://tree/manga"))
    private val folder = LastFolder(place.id, "作品", "doc:作品")
    private val last = book(place.id, "作品/2巻.zip")
    private val request = OpenBookRequest(BookAddress.InLocation(place.id, "作品/2巻.zip", folder = false))

    private fun given(
        reopen: ReopenLastBook,
        lastFolder: LastFolder? = folder,
        unfinished: Int = 0,
        historyLimit: Int = 100,
        reopenFolder: Boolean = true,
    ) {
        fixture.locations.rows.value = listOf(place)
        fixture.grants.held += place.treeUri
        fixture.books.put(last)
        fixture.history.entries.value = listOf(HistoryEntry(last.id, T0))
        fixture.settings.value.value = fixture.settings.value.value.copy(reopenLastFolder = reopenFolder, reopenLastBook = reopen, libraryView = LibraryView.List, historyLimit = historyLimit)
        fixture.launch.record = LaunchRecord(unfinished, lastFolder)
    }

    @Test
    fun `with the folder setting off a start shows home, and the last book still opens over it when the setting asks for it`() = runTest {
        given(ReopenLastBook.Off, reopenFolder = false)
        assertEquals(StartPlan.Open(), fixture.services.planStart())

        given(ReopenLastBook.Ask, reopenFolder = false)
        assertEquals(StartPlan.Open(reopen = Reopen.Ask("2巻.zip", request)), fixture.services.planStart())

        given(ReopenLastBook.Immediately, reopenFolder = false)
        assertEquals(StartPlan.Open(reopen = Reopen.Now(request)), fixture.services.planStart())
    }

    @Test
    fun `a start whose record cannot be read starts at home with a notice, also over kept screens`() = runTest {
        given(ReopenLastBook.Immediately)
        fixture.launch.failure = java.io.IOException("no space left on device")
        assertEquals(StartPlan.Unplanned, fixture.services.planStart())
        assertEquals(StartPlan.Unplanned, fixture.services.planStart(restoring = true))
    }

    @Test
    fun `a start with the folder setting off still records the last folder for when it is turned on`() = runTest {
        given(ReopenLastBook.Off, reopenFolder = false)
        fixture.services.planStart()
        assertEquals(folder, fixture.launch.record.lastFolder)

        fixture.settings.value.value = fixture.settings.value.value.copy(reopenLastFolder = true)
        assertEquals(StartPlan.Open(FolderKey(place.id, "作品", "doc:作品")), fixture.services.planStart())
    }

    @Test
    fun `a start opens the last folder over home when the setting is on, and the last book only when its setting asks for it`() = runTest {
        given(ReopenLastBook.Off)
        assertEquals(StartPlan.Open(FolderKey(place.id, "作品", "doc:作品")), fixture.services.planStart())

        given(ReopenLastBook.Ask)
        assertEquals(StartPlan.Open(FolderKey(place.id, "作品", "doc:作品"), "作品/2巻.zip", Reopen.Ask("2巻.zip", request)), fixture.services.planStart())

        given(ReopenLastBook.Immediately, lastFolder = null)
        assertEquals(StartPlan.Open(reopen = Reopen.Now(request)), fixture.services.planStart())
    }

    @Test
    fun `with no history kept there is no last book, even one the history still holds`() = runTest {
        given(ReopenLastBook.Immediately, historyLimit = 0)
        assertEquals(StartPlan.Open(FolderKey(place.id, "作品", "doc:作品")), fixture.services.planStart())
    }

    @Test
    fun `a start counts itself until it finishes`() = runTest {
        given(ReopenLastBook.Off)
        fixture.services.planStart()
        fixture.services.planStart()
        assertEquals(2, fixture.launch.record.unfinishedStarts)

        fixture.launch.finishStart()
        assertEquals(0, fixture.launch.record.unfinishedStarts)
    }

    @Test
    fun `after three unfinished starts the next starts at home with the last folder and the view reset`() = runTest {
        given(ReopenLastBook.Immediately, unfinished = FAILED_STARTS - 1)
        assertEquals(Reopen.Now(request), (fixture.services.planStart() as StartPlan.Open).reopen)

        assertEquals(StartPlan.Open(), fixture.services.planStart())
        assertEquals(null, fixture.launch.record.lastFolder)
        assertEquals(LibraryView.Grid, fixture.settings.value.value.libraryView)
    }

    @Test
    fun `a start over the screens the system kept keeps them, and counts itself`() = runTest {
        given(ReopenLastBook.Immediately, unfinished = FAILED_STARTS - 1)
        assertEquals(StartPlan.Restore(), fixture.services.planStart(restoring = true))
        assertEquals(FAILED_STARTS, fixture.launch.record.unfinishedStarts)

        assertEquals(StartPlan.Open(), fixture.services.planStart(restoring = true))
        assertEquals(null, fixture.launch.record.lastFolder)
        assertEquals(LibraryView.Grid, fixture.settings.value.value.libraryView)
    }

    private val died = OpeningBook(BookAddress.InLocation(place.id, "作品//2巻.zip", folder = false, documentId = "doc:2"), "2巻.zip")

    @Test
    fun `the last book the app ended opening is asked about, never opened at once, and the start right after names it`() = runTest {
        given(ReopenLastBook.Immediately, reopenFolder = false)
        fixture.launch.record = LaunchRecord(diedOpening = died, suspect = died)
        assertEquals(StartPlan.Open(reopen = Reopen.Ask("2巻.zip", request, suspect = true), diedOpening = died), fixture.services.planStart())

        fixture.launch.record = LaunchRecord(suspect = died)
        assertEquals(StartPlan.Open(reopen = Reopen.Ask("2巻.zip", request, suspect = true)), fixture.services.planStart())

        given(ReopenLastBook.Ask, reopenFolder = false)
        fixture.launch.record = LaunchRecord(suspect = died)
        assertEquals(StartPlan.Open(reopen = Reopen.Ask("2巻.zip", request, suspect = true)), fixture.services.planStart())
    }

    @Test
    fun `another book the app ended opening is named and leaves the last book as the setting says`() = runTest {
        given(ReopenLastBook.Immediately, reopenFolder = false)
        val other = OpeningBook(BookAddress.Document(UriString("content://downloads/9")), "9.zip")
        fixture.launch.record = LaunchRecord(diedOpening = other, suspect = other)
        assertEquals(StartPlan.Open(reopen = Reopen.Now(request), diedOpening = other), fixture.services.planStart())
    }

    @Test
    fun `a start over kept screens after the app ended opening a book names it, and so does the start at home after three`() = runTest {
        given(ReopenLastBook.Off)
        fixture.launch.record = LaunchRecord(diedOpening = died, suspect = died)
        assertEquals(StartPlan.Restore(died), fixture.services.planStart(restoring = true))

        fixture.launch.record = LaunchRecord(unfinishedStarts = FAILED_STARTS, diedOpening = died, suspect = died)
        assertEquals(StartPlan.Open(diedOpening = died), fixture.services.planStart(restoring = true))
    }

    @Test
    fun `a place that is gone or lost its grant leaves its folder and book out`() = runTest {
        given(ReopenLastBook.Immediately)
        fixture.grants.held.clear()
        assertEquals(StartPlan.Open(), fixture.services.planStart())

        fixture.locations.rows.value = emptyList()
        assertEquals(StartPlan.Open(), fixture.services.planStart())
    }

    @Test
    fun `when the system does not tell the grants the start goes by the stored ones`() = runTest {
        given(ReopenLastBook.Off)
        fixture.grants.heldFails = true
        assertEquals(StartPlan.Open(FolderKey(place.id, "作品", "doc:作品")), fixture.services.planStart())
        assertEquals(1, fixture.launch.record.unfinishedStarts)
    }

    @Test
    fun `a text book is not reopened before the text reader exists`() = runTest {
        given(ReopenLastBook.Immediately, lastFolder = null)
        fixture.books.put(last.copy(format = BookFormat.Text(null)))
        assertEquals(StartPlan.Open(), fixture.services.planStart())
    }
}
