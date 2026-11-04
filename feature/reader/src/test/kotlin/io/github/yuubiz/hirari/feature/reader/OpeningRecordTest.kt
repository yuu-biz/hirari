package io.github.yuubiz.hirari.feature.reader

import io.github.yuubiz.hirari.core.data.BookOpening
import io.github.yuubiz.hirari.core.data.LastFolder
import io.github.yuubiz.hirari.core.data.LaunchRecord
import io.github.yuubiz.hirari.core.data.LaunchRepository
import io.github.yuubiz.hirari.core.data.OpeningBook
import io.github.yuubiz.hirari.core.model.BookAddress
import io.github.yuubiz.hirari.core.model.LocationId
import io.github.yuubiz.hirari.core.model.UriString
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** docs/spec.md section 6.1, 落ちた本を再び開くこと: the reader's side of the record, on virtual time. */
@OptIn(ExperimentalCoroutinesApi::class)
class OpeningRecordTest {
    /** The record as the store keeps it, with each write taking [WRITE_MS]. */
    private class Launch(private val failing: Boolean = false) : LaunchRepository {
        val steps = mutableListOf<String>()
        private var serial = 0L

        override suspend fun beginOpening(book: OpeningBook): BookOpening {
            delay(WRITE_MS)
            if (failing) throw IOException("no space left on device")
            steps += "write ${book.name}"
            return BookOpening(++serial, book)
        }

        override suspend fun endOpening(opening: BookOpening, shown: Boolean) {
            delay(WRITE_MS)
            steps += if (shown) "shown ${opening.serial}" else "end ${opening.serial}"
        }

        override suspend fun beginStart(): LaunchRecord = LaunchRecord()

        override suspend fun finishStart() = Unit

        override suspend fun setLastFolder(folder: LastFolder?) = Unit

        override suspend fun readerGuideSeen(): Boolean = false

        override suspend fun markReaderGuideSeen() = Unit

        override suspend fun lastSaveFolder(): UriString? = null

        override suspend fun setLastSaveFolder(folder: UriString?) = Unit
    }

    private val book = OpeningBook(BookAddress.InLocation(LocationId(1), "作品/1巻.zip", folder = false), "1巻.zip")

    private fun TestScope.record(launch: Launch) = OpeningRecord(backgroundScope, launch, book)

    /** Lets the writes asked so far land; the writer runs in the background scope, which advanceUntilIdle leaves. */
    private fun TestScope.settle() {
        advanceTimeBy(10 * WRITE_MS)
        runCurrent()
    }

    @Test
    fun `the book opens only once its record is written, and the record ends once the reader shows it`() = runTest {
        val launch = Launch()
        val record = record(launch)
        val begun = async { record.begin() }
        advanceTimeBy(WRITE_MS - 1)
        assertFalse(begun.isCompleted, "the book opened before its record was written")
        advanceTimeBy(2)
        assertTrue(begun.isCompleted)
        assertEquals(listOf("write 1巻.zip"), launch.steps)

        record.shown()
        record.shown()
        record.left()
        settle()
        assertEquals(listOf("write 1巻.zip", "shown 1"), launch.steps)
    }

    @Test
    fun `leaving while the record is still written ends it after the write`() = runTest {
        val launch = Launch()
        val record = record(launch)
        val begun = async { record.begin() }
        runCurrent()
        begun.cancel()
        record.left()
        settle()
        assertEquals(listOf("write 1巻.zip", "end 1"), launch.steps)
    }

    @Test
    fun `a stopped screen ends the record and writes it again when it starts, and a stop before the write writes none`() = runTest {
        val launch = Launch()
        val early = record(launch)
        early.stopped()
        early.begin()
        early.left()
        settle()
        assertEquals(emptyList(), launch.steps, "a stopped reader recorded its book")

        val record = record(launch)
        record.begin()
        record.stopped()
        record.started()
        record.shown()
        settle()
        assertEquals(listOf("write 1巻.zip", "end 1", "write 1巻.zip", "shown 2"), launch.steps)
    }

    @Test
    fun `a book whose record cannot be written still opens`() = runTest {
        val launch = Launch(failing = true)
        val record = record(launch)
        record.begin()
        record.shown()
        settle()
        assertEquals(emptyList(), launch.steps)
    }

    private companion object {
        const val WRITE_MS = 50L
    }
}
