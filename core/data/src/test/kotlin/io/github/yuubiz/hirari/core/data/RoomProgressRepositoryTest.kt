package io.github.yuubiz.hirari.core.data

import android.content.Context
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import io.github.yuubiz.hirari.core.model.BookId
import io.github.yuubiz.hirari.core.model.BookProgress
import io.github.yuubiz.hirari.core.model.ReadState
import io.github.yuubiz.hirari.core.model.ReadingPosition
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
class RoomProgressRepositoryTest {
    private val database = Room.inMemoryDatabaseBuilder<HirariDatabase>(ApplicationProvider.getApplicationContext<Context>())
        .configure()
    private val repository = RoomProgressRepository(database.bookProgress())

    @After
    fun close() = database.close()

    private fun progress(book: String, state: ReadState, position: ReadingPosition?) =
        BookProgress(BookId(book), state, position, Instant.ofEpochMilli(1_700_000_000_123))

    @Test
    fun `an unknown book has no progress`() = runTest {
        assertNull(repository.get(BookId("missing")))
    }

    @Test
    fun `each kind of position reads back unchanged`() = runTest {
        val saved = listOf(
            progress("image", ReadState.Reading, ReadingPosition.Image(viewStart = 12, pageCount = 200)),
            progress("text", ReadState.Read, ReadingPosition.Text(charOffset = 5_000_000_000, totalChars = 6_000_000_000)),
            progress("unread", ReadState.Unread, null),
        )
        repository.save(saved)
        assertEquals(saved, saved.map { repository.get(it.book) })
    }

    @Test
    fun `saving a book again replaces its progress`() = runTest {
        repository.save(listOf(progress("a", ReadState.Reading, ReadingPosition.Image(3, 10))))
        val later = progress("a", ReadState.Read, ReadingPosition.Image(9, 10))
        repository.save(listOf(later))
        assertEquals(later, repository.get(BookId("a")))
    }
}
