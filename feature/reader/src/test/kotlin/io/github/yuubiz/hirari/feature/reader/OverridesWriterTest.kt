package io.github.yuubiz.hirari.feature.reader

import io.github.yuubiz.hirari.core.data.BookRepository
import io.github.yuubiz.hirari.core.data.LibraryBook
import io.github.yuubiz.hirari.core.model.Book
import io.github.yuubiz.hirari.core.model.BookId
import io.github.yuubiz.hirari.core.model.BookOverrides
import io.github.yuubiz.hirari.core.model.ImageAdjust
import io.github.yuubiz.hirari.core.model.LocationId
import io.github.yuubiz.hirari.core.model.Rotation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

private val ID = BookId("book")

/** Stored overrides, with writes that wait for [gate] while it is set. */
private class SlowBooks : BookRepository {
    val stored = HashMap<BookId, BookOverrides>()
    var gate: CompletableDeferred<Unit>? = null

    override fun overrides(id: BookId): Flow<BookOverrides> = flow { emit(stored[id] ?: BookOverrides()) }

    override suspend fun setOverrides(id: BookId, overrides: BookOverrides) {
        gate?.await()
        stored[id] = overrides
    }

    override suspend fun record(book: Book) = Unit

    override suspend fun get(id: BookId): LibraryBook? = null

    override fun inFolder(location: LocationId, folder: String): Flow<List<LibraryBook>> = emptyFlow()

    override fun continueReading(limit: Int): Flow<List<LibraryBook>> = emptyFlow()

    override suspend fun setCover(id: BookId, entry: Int?) = Unit
}

class OverridesWriterTest {
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
}
