package io.github.yuubiz.hirari.feature.reader

import io.github.yuubiz.hirari.core.data.BookRepository
import io.github.yuubiz.hirari.core.data.HistoryItem
import io.github.yuubiz.hirari.core.data.HistoryRepository
import io.github.yuubiz.hirari.core.data.LibraryBook
import io.github.yuubiz.hirari.core.model.Book
import io.github.yuubiz.hirari.core.model.BookFormat
import io.github.yuubiz.hirari.core.model.BookId
import io.github.yuubiz.hirari.core.model.BookOverrides
import io.github.yuubiz.hirari.core.model.BookSource
import io.github.yuubiz.hirari.core.model.Container
import io.github.yuubiz.hirari.core.model.HistorySort
import io.github.yuubiz.hirari.core.model.LocationId
import io.github.yuubiz.hirari.core.model.UriString
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class ReadingSessionTest {
    private val book = Book(
        id = BookId("b1"),
        source = BookSource.External(UriString("content://docs/book.zip")),
        format = BookFormat.Images(Container.Zip),
        title = "book.zip",
        byteSize = 10,
        modified = Instant.EPOCH,
        pageCount = 3,
    )
    private val openedAt = Instant.ofEpochSecond(1_000)
    private val log = mutableListOf<String>()

    private val books = object : BookRepository {
        var failing = false

        override suspend fun record(book: Book) {
            if (failing) throw IOException("disk full")
            log += "book ${book.id.value} ${book.pageCount}"
        }

        override suspend fun get(id: BookId): LibraryBook? = null

        override fun inFolder(location: LocationId, folder: String): Flow<List<LibraryBook>> = emptyFlow()

        override fun continueReading(limit: Int): Flow<List<LibraryBook>> = emptyFlow()

        override suspend fun setCover(id: BookId, entry: Int?) = Unit

        override fun overrides(id: BookId): Flow<BookOverrides> = emptyFlow()

        override suspend fun setOverrides(id: BookId, overrides: BookOverrides) = Unit
    }

    private val history = object : HistoryRepository {
        override fun observe(sort: HistorySort): Flow<List<HistoryItem>> = emptyFlow()

        override suspend fun record(book: BookId, openedAt: Instant, limit: Int) {
            log += "history ${book.value} ${openedAt.epochSecond} $limit"
        }

        override suspend fun remove(book: BookId) = Unit

        override suspend fun clear() = Unit
    }

    private fun TestScope.session(limit: Int = 100) = ReadingSession(book, openedAt, limit, books, history, this)

    @Test
    fun openingIndexesTheBookAndNothingGoesToTheHistoryUntilItCloses() = runTest {
        session()
        advanceUntilIdle()
        assertEquals(listOf("book b1 3"), log)
    }

    @Test
    fun closingMovesTheBookToTheHistoryWithTheTimeItOpened() = runTest {
        val session = session(limit = 20)
        advanceUntilIdle()
        session.stop()
        advanceUntilIdle()
        assertEquals(listOf("book b1 3", "history b1 1000 20"), log)
    }

    @Test
    fun closingBeforeTheIndexWriteRunsStillWritesTheBookFirstAndTheHistoryOnce() = runTest {
        val session = session()
        session.stop()
        session.stop()
        advanceUntilIdle()
        assertEquals(listOf("book b1 3", "history b1 1000 100"), log)
    }

    @Test
    fun stoppingWritesTheHistoryAndTheLaterCloseWritesNothingMore() = runTest {
        val session = session()
        advanceUntilIdle()
        session.stop()
        advanceUntilIdle()
        assertEquals(listOf("book b1 3", "history b1 1000 100"), log)
        session.stop()
        advanceUntilIdle()
        assertEquals(listOf("book b1 3", "history b1 1000 100"), log)
    }

    @Test
    fun aFailedIndexWriteDoesNotKeepTheBookOutOfTheHistory() = runTest {
        books.failing = true
        val session = session()
        session.stop()
        advanceUntilIdle()
        assertEquals(listOf("history b1 1000 100"), log)
    }

    @Test
    fun aStopBeforeTheSessionExistsIsWrittenWhenItArrives() = runTest {
        val stops = StopRelay()
        stops.stop()
        stops.attach(session())
        advanceUntilIdle()
        assertEquals(listOf("book b1 3", "history b1 1000 100"), log)
    }
}
