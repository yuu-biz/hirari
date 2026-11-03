package io.github.yuubiz.hirari.feature.reader

import io.github.yuubiz.hirari.core.data.BookRepository
import io.github.yuubiz.hirari.core.data.LibraryBook
import io.github.yuubiz.hirari.core.model.Book
import io.github.yuubiz.hirari.core.model.BookId
import io.github.yuubiz.hirari.core.model.BookOverrides
import io.github.yuubiz.hirari.core.model.CoverCrop
import io.github.yuubiz.hirari.core.model.ImageAdjust
import io.github.yuubiz.hirari.core.model.LocationId
import io.github.yuubiz.hirari.core.model.Rotation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.nio.charset.Charset
import kotlin.test.Test
import kotlin.test.assertEquals

private val ID = BookId("book")

/** Stored overrides, with writes that wait for [gate] while it is set. */
private class SlowBooks : BookRepository {
    val stored = HashMap<BookId, BookOverrides>()
    var gate: CompletableDeferred<Unit>? = null
    var refuse = false
    var readGate: CompletableDeferred<Unit>? = null

    override fun overrides(id: BookId): Flow<BookOverrides> {
        val gate = readGate
        return flow {
            gate?.await()
            emit(stored[id] ?: BookOverrides())
        }
    }

    override suspend fun updateOverrides(id: BookId, change: (BookOverrides) -> BookOverrides) {
        gate?.await()
        if (refuse) throw java.io.IOException("disk full")
        stored[id] = change(stored[id] ?: BookOverrides())
    }

    override suspend fun record(book: Book) = Unit

    override suspend fun get(id: BookId): LibraryBook? = null

    override fun inFolder(location: LocationId, folder: String): Flow<List<LibraryBook>> = emptyFlow()

    override fun continueReading(limit: Int): Flow<List<LibraryBook>> = emptyFlow()

    override suspend fun setCover(id: BookId, entry: Int?, crop: CoverCrop?) = Unit
}

class OverridesWriterTest {
    @Test
    fun aReaderChangeWrittenAfterTheArchiveListChoseACharsetKeepsBoth() = runTest {
        val books = SlowBooks()
        val store = BookOverridesStore(books, CoroutineScope(StandardTestDispatcher(testScheduler)))
        val charset = Charset.forName("EUC-JP")
        store.open(ID).update { it.copy(rotation = Rotation.Quarter) }
        books.updateOverrides(ID) { it.copy(nameCharset = charset) }
        assertEquals(BookOverrides(rotation = Rotation.Quarter, nameCharset = charset), store.open(ID).current)
        advanceUntilIdle()
        assertEquals(BookOverrides(rotation = Rotation.Quarter, nameCharset = charset), books.stored[ID])
    }

    @Test
    fun aReaderOpenedBeforeTheLastOneWroteStartsFromItsChangeAndKeepsIt() = runTest {
        val books = SlowBooks().apply { gate = CompletableDeferred() }
        val store = BookOverridesStore(books, CoroutineScope(StandardTestDispatcher(testScheduler)))
        store.open(ID).update { it.copy(rotation = Rotation.Quarter) }
        runCurrent()
        val reopened = store.open(ID)
        assertEquals(Rotation.Quarter, reopened.current.rotation)
        reopened.update { it.copy(adjust = ImageAdjust(sharpen = true)) }
        books.gate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(BookOverrides(rotation = Rotation.Quarter, adjust = ImageAdjust(sharpen = true)), books.stored[ID])
    }

    @Test
    fun onceWrittenAReaderReadsWhatIsStored() = runTest {
        val books = SlowBooks()
        val store = BookOverridesStore(books, CoroutineScope(StandardTestDispatcher(testScheduler)))
        store.open(ID).update { it.copy(rotation = Rotation.Half) }
        advanceUntilIdle()
        books.stored[ID] = BookOverrides()
        assertEquals(BookOverrides(), store.open(ID).current)
    }

    @Test
    fun aChangeTheDatabaseRefusedIsStillWhereAReopenedReaderStarts() = runTest {
        val books = SlowBooks().apply { refuse = true }
        val store = BookOverridesStore(books, CoroutineScope(StandardTestDispatcher(testScheduler)))
        store.open(ID).update { it.copy(rotation = Rotation.Quarter) }
        advanceUntilIdle()
        val reopened = store.open(ID)
        assertEquals(Rotation.Quarter, reopened.current.rotation)
        books.refuse = false
        reopened.update { it.copy(adjust = ImageAdjust(sharpen = true)) }
        advanceUntilIdle()
        assertEquals(BookOverrides(rotation = Rotation.Quarter, adjust = ImageAdjust(sharpen = true)), books.stored[ID])
    }

    @Test
    fun aReaderOpenedWhileAnotherChangesTheSameFieldStartsFromTheNewerValue() = runTest {
        val books = SlowBooks().apply { gate = CompletableDeferred() }
        val store = BookOverridesStore(books, CoroutineScope(StandardTestDispatcher(testScheduler)))
        val first = store.open(ID)
        val second = store.open(ID)
        first.update { it.copy(rotation = Rotation.Quarter) }
        runCurrent()
        books.readGate = CompletableDeferred()
        val reopening = async { store.open(ID) }
        runCurrent()
        val readGate = books.readGate!!
        books.readGate = null
        second.update { it.copy(rotation = Rotation.Half) }
        books.gate!!.complete(Unit)
        advanceUntilIdle()
        readGate.complete(Unit)
        assertEquals(Rotation.Half, books.stored[ID]?.rotation)
        assertEquals(Rotation.Half, reopening.await().current.rotation)
    }

    @Test
    fun aChangeKeepsOnlyTheFieldsTheReaderChangedWhenItIsWrittenAndWhenAReaderReopens() = runTest {
        val books = SlowBooks().apply { gate = CompletableDeferred() }
        val store = BookOverridesStore(books, CoroutineScope(StandardTestDispatcher(testScheduler)))
        val charset = Charset.forName("EUC-JP")
        val writer = store.open(ID)
        val snapshot = writer.current.copy(rotation = Rotation.Quarter)
        writer.update { snapshot }
        runCurrent()
        books.stored[ID] = BookOverrides(nameCharset = charset)
        assertEquals(BookOverrides(rotation = Rotation.Quarter, nameCharset = charset), store.open(ID).current)
        books.gate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(BookOverrides(rotation = Rotation.Quarter, nameCharset = charset), books.stored[ID])
    }

    @Test
    fun aFieldTheReaderClearedIsClearedAgainOnTheStoredRowOnlyForThatField() = runTest {
        val books = SlowBooks().apply { gate = CompletableDeferred() }
        val store = BookOverridesStore(books, CoroutineScope(StandardTestDispatcher(testScheduler)))
        books.stored[ID] = BookOverrides(rotation = Rotation.Quarter, reverseOrder = true)
        store.open(ID).update { it.copy(rotation = null) }
        runCurrent()
        books.stored[ID] = BookOverrides(rotation = Rotation.Quarter, reverseOrder = false)
        assertEquals(BookOverrides(reverseOrder = false), store.open(ID).current)
        books.gate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(BookOverrides(reverseOrder = false), books.stored[ID])
    }
}
