package io.github.yuubiz.hirari.core.data

import io.github.yuubiz.hirari.core.model.BookId
import io.github.yuubiz.hirari.core.model.BookProgress
import io.github.yuubiz.hirari.core.model.ReadState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant

/** Reading progress of each book (docs/spec.md section 2.7). Safe to call from the main thread. */
interface ProgressRepository {
    suspend fun get(book: BookId): BookProgress?

    fun observe(book: BookId): Flow<BookProgress?>

    /** Replaces the stored progress of each book in [progress]. */
    suspend fun save(progress: Collection<BookProgress>)

    /**
     * 既読にする and 未読にする of docs/spec.md section 4.3. Marking a book read keeps its position; marking it
     * unread forgets the position, so it opens at the start.
     */
    suspend fun mark(book: BookId, state: ReadState, at: Instant)
}

internal class RoomProgressRepository(private val dao: BookProgressDao) : ProgressRepository {
    override suspend fun get(book: BookId): BookProgress? = dao.get(book.value)?.toModel()

    override fun observe(book: BookId): Flow<BookProgress?> = dao.observe(book.value).map { it?.toModel() }

    override suspend fun save(progress: Collection<BookProgress>) {
        if (progress.isNotEmpty()) dao.upsert(progress.map { it.toRow() })
    }

    override suspend fun mark(book: BookId, state: ReadState, at: Instant) {
        val position = if (state == ReadState.Unread) null else get(book)?.position
        save(listOf(BookProgress(book, state, position, at)))
    }
}
