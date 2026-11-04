package io.github.yuubiz.hirari.core.data

import androidx.datastore.core.DataStore
import io.github.yuubiz.hirari.core.model.BookAddress
import io.github.yuubiz.hirari.core.model.LocationId
import io.github.yuubiz.hirari.core.model.UriString
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The record's own logic over an in-memory store, and its file format through the serializer. A file-backed
 * DataStore cannot write a file twice on a Windows JVM, whose rename does not replace an existing file.
 */
class LaunchRepositoryTest {
    private class MemoryStore(initial: StoredLaunch = StoredLaunch()) : DataStore<StoredLaunch> {
        val value = MutableStateFlow(initial)
        override val data: Flow<StoredLaunch> = value

        override suspend fun updateData(transform: suspend (t: StoredLaunch) -> StoredLaunch): StoredLaunch =
            transform(value.value).also { value.value = it }
    }

    /** What a restart reads back: the stored value through the file format. */
    private fun MemoryStore.restarted(): MemoryStore = runBlocking {
        val bytes = ByteArrayOutputStream().also { LaunchSerializer.writeTo(value.value, it) }.toByteArray()
        MemoryStore(LaunchSerializer.readFrom(ByteArrayInputStream(bytes)))
    }

    @Test
    fun `starts that never finish add up across restarts and a finished one clears them`() = runBlocking {
        val store = MemoryStore()
        DataStoreLaunchRepository(store).beginStart()
        DataStoreLaunchRepository(store).beginStart()
        val launch = DataStoreLaunchRepository(store.restarted())
        assertEquals(2, launch.beginStart().unfinishedStarts)
        launch.finishStart()
        assertEquals(0, launch.beginStart().unfinishedStarts)
    }

    @Test
    fun `the last folder survives a restart until it is cleared`() = runBlocking {
        val store = MemoryStore()
        val last = LastFolder(LocationId(3), "漫画/作品", "primary:漫画/作品")
        DataStoreLaunchRepository(store).setLastFolder(last)
        val launch = DataStoreLaunchRepository(store.restarted())
        assertEquals(last, launch.beginStart().lastFolder)
        launch.setLastFolder(null)
        assertEquals(null, launch.beginStart().lastFolder)
    }

    @Test
    fun `the folder last saved into survives a restart and leaves the start record alone`() = runBlocking {
        val store = MemoryStore()
        val folder = UriString("content://com.android.externalstorage.documents/tree/primary%3ABooks")
        assertEquals(null, DataStoreLaunchRepository(store).lastSaveFolder())
        DataStoreLaunchRepository(store).setLastSaveFolder(folder)
        val launch = DataStoreLaunchRepository(store.restarted())
        assertEquals(folder, launch.lastSaveFolder())
        assertEquals(LaunchRecord(), launch.beginStart())
        launch.finishStart()
        launch.setLastFolder(null)
        assertEquals(folder, launch.lastSaveFolder())
        launch.setLastSaveFolder(null)
        assertEquals(null, launch.lastSaveFolder())
    }

    @Test
    fun `the guide is unseen until it is marked and stays seen across restarts`() = runBlocking {
        val store = MemoryStore()
        assertEquals(false, DataStoreLaunchRepository(store).readerGuideSeen())
        DataStoreLaunchRepository(store).markReaderGuideSeen()
        val launch = DataStoreLaunchRepository(store.restarted())
        assertEquals(true, launch.readerGuideSeen())
        launch.beginStart()
        launch.finishStart()
        launch.setLastFolder(null)
        assertEquals(true, launch.readerGuideSeen())
    }

    @Test
    fun `a record written before the guide existed reads as not seen`() = runBlocking {
        val old = """{"unfinishedStarts":1}""".toByteArray()
        assertEquals(StoredLaunch(unfinishedStarts = 1), LaunchSerializer.readFrom(ByteArrayInputStream(old)))
    }

    private val book = OpeningBook(BookAddress.InLocation(LocationId(3), "漫画//作品/1巻.zip", folder = false, documentId = "primary:1"), "1巻.zip")
    private val other = OpeningBook(BookAddress.Document(UriString("content://downloads/2")), "2.zip")

    @Test
    fun `a book still opening when the app ends is named by the next start and stays the suspect until it shows`() = runBlocking {
        var store = MemoryStore()
        DataStoreLaunchRepository(store).beginOpening(book)
        store = store.restarted()
        val first = DataStoreLaunchRepository(store).beginStart()
        assertEquals(book, first.diedOpening)
        assertEquals(book, first.suspect)

        store = store.restarted()
        val launch = DataStoreLaunchRepository(store)
        val second = launch.beginStart()
        assertEquals(null, second.diedOpening, "only the start right after names it")
        assertEquals(book, second.suspect)

        val reopened = launch.beginOpening(OpeningBook(BookAddress.InLocation(LocationId(3), "漫画/作品/1巻.zip", folder = false), "1巻.zip"))
        launch.endOpening(reopened, shown = false)
        assertEquals(book, launch.beginStart().suspect, "leaving before it shows keeps it suspect")
        launch.endOpening(launch.beginOpening(book), shown = true)
        assertEquals(LaunchRecord(unfinishedStarts = 3), launch.beginStart().copy(lastFolder = null))
    }

    @Test
    fun `a reader that ends its record leaves the record of the reader that replaced it`() = runBlocking {
        val launch = DataStoreLaunchRepository(MemoryStore())
        val leaving = launch.beginOpening(book)
        val replacing = launch.beginOpening(book)
        launch.endOpening(leaving, shown = false)
        assertEquals(book, launch.beginStart().diedOpening)

        val shown = launch.beginOpening(other)
        launch.endOpening(shown, shown = true)
        launch.endOpening(shown, shown = true)
        assertEquals(null, launch.beginStart().diedOpening, "an end twice is an end once")
        launch.endOpening(replacing, shown = true)
        assertEquals(null, launch.beginStart().suspect, "the book showed in the end")
    }

    @Test
    fun `a record the next version names in a way this one does not know reads as none`() = runBlocking {
        val stored = """{"opening":{"kind":"cloud","name":"x"}}""".toByteArray()
        assertEquals(LaunchRecord(), LaunchSerializer.readFrom(ByteArrayInputStream(stored)).toRecord())
    }

    @Test
    fun `an unreadable file starts over`() = runBlocking {
        assertEquals(StoredLaunch(), LaunchSerializer.readFrom(ByteArrayInputStream("not json".toByteArray())))
        assertEquals(StoredLaunch(), LaunchSerializer.readFrom(ByteArrayInputStream("[1, 2]".toByteArray())))
    }
}
