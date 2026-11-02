package io.github.yuubiz.hirari.core.data

import io.github.yuubiz.hirari.core.model.BookId
import io.github.yuubiz.hirari.core.model.BookProgress
import io.github.yuubiz.hirari.core.model.ReadState
import io.github.yuubiz.hirari.core.model.ReadingPosition
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class ProgressWriterTest {
    private class Write(val atMs: Long, val progress: List<BookProgress>)

    private class RecordingRepository(private val scope: TestScope) : ProgressRepository {
        val writes = mutableListOf<Write>()
        var failuresLeft = 0

        override suspend fun get(book: BookId): BookProgress? = null

        override suspend fun save(progress: Collection<BookProgress>) {
            if (failuresLeft > 0) {
                failuresLeft--
                delay(50)
                throw IOException("disk full")
            }
            writes += Write(scope.testScheduler.currentTime, progress.toList())
        }
    }

    private fun progress(book: String, viewStart: Int) =
        BookProgress(BookId(book), ReadState.Reading, ReadingPosition.Image(viewStart, 100), Instant.EPOCH)

    private fun writerTest(body: suspend TestScope.(ProgressWriter, RecordingRepository) -> Unit) = runTest {
        val repository = RecordingRepository(this)
        body(ProgressWriter(repository, backgroundScope), repository)
    }

    /** `advanceUntilIdle` does not wait for the background scope that the writer runs in. */
    private fun TestScope.settle() {
        advanceTimeBy(10_000)
        runCurrent()
    }

    @Test
    fun `a window stores only the latest view of the book when it ends`() = writerTest { writer, repository ->
        writer.submit(progress("a", 1))
        advanceTimeBy(100)
        writer.submit(progress("a", 2))
        advanceTimeBy(300)
        writer.submit(progress("a", 3))
        advanceTimeBy(99)
        runCurrent()
        assertEquals(emptyList(), repository.writes.map { it.atMs })

        settle()
        assertEquals(listOf(500L), repository.writes.map { it.atMs })
        assertEquals(listOf(progress("a", 3)), repository.writes.single().progress)
    }

    @Test
    fun `continuous page turns are stored every window instead of waiting for a pause`() =
        writerTest { writer, repository ->
            repeat(17) {
                writer.submit(progress("a", it))
                delay(120)
            }
            settle()
            assertEquals(listOf(500L, 1100L, 1700L, 2300L), repository.writes.map { it.atMs })
            assertEquals(listOf(progress("a", 16)), repository.writes.last().progress)
        }

    @Test
    fun `books changed in one window are stored together`() = writerTest { writer, repository ->
        writer.submit(progress("a", 1))
        writer.submit(progress("b", 2))
        settle()
        assertEquals(listOf(listOf(progress("a", 1), progress("b", 2))), repository.writes.map { it.progress })
    }

    @Test
    fun `flush stores the pending view at once and only once`() = writerTest { writer, repository ->
        writer.submit(progress("a", 1))
        advanceTimeBy(200)
        writer.flush().join()
        assertEquals(listOf(200L), repository.writes.map { it.atMs })

        settle()
        assertEquals(1, repository.writes.size)
    }

    @Test
    fun `a submit after a flush waits a full window of its own`() = writerTest { writer, repository ->
        writer.submit(progress("a", 1))
        advanceTimeBy(200)
        writer.flush().join()
        advanceTimeBy(100)
        writer.submit(progress("a", 2))
        advanceTimeBy(450)
        runCurrent()
        assertEquals(listOf(200L), repository.writes.map { it.atMs })

        settle()
        assertEquals(listOf(200L, 800L), repository.writes.map { it.atMs })
    }

    @Test
    fun `flush with nothing pending stores nothing`() = writerTest { writer, repository ->
        writer.flush().join()
        settle()
        assertEquals(emptyList(), repository.writes.map { it.atMs })
    }

    @Test
    fun `a failed write keeps the view for the next write`() = writerTest { writer, repository ->
        repository.failuresLeft = 1
        writer.submit(progress("a", 1))
        settle()
        assertEquals(emptyList(), repository.writes.map { it.atMs })

        writer.flush().join()
        assertEquals(listOf(progress("a", 1)), repository.writes.single().progress)
    }

    @Test
    fun `a failed write does not overwrite a view submitted while it ran`() = writerTest { writer, repository ->
        repository.failuresLeft = 1
        writer.submit(progress("a", 1))
        advanceTimeBy(520)
        writer.submit(progress("a", 2))
        settle()
        assertEquals(listOf(progress("a", 2)), repository.writes.single().progress)
    }
}
