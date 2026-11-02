package io.github.yuubiz.hirari.core.data

import io.github.yuubiz.hirari.core.model.BookId
import io.github.yuubiz.hirari.core.model.BookProgress

/** Reading progress of each book (docs/spec.md section 2.7). Safe to call from the main thread. */
interface ProgressRepository {
    suspend fun get(book: BookId): BookProgress?

    /** Replaces the stored progress of each book in [progress]. */
    suspend fun save(progress: Collection<BookProgress>)
}

internal class RoomProgressRepository(private val dao: BookProgressDao) : ProgressRepository {
    override suspend fun get(book: BookId): BookProgress? = dao.get(book.value)?.toModel()

    override suspend fun save(progress: Collection<BookProgress>) {
        if (progress.isNotEmpty()) dao.upsert(progress.map { it.toRow() })
    }
}
