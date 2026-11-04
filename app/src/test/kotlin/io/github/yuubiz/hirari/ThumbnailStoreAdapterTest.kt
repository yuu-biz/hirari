package io.github.yuubiz.hirari

import io.github.yuubiz.hirari.core.data.ThumbKey
import io.github.yuubiz.hirari.core.data.ThumbLookup
import io.github.yuubiz.hirari.core.data.ThumbnailCache
import io.github.yuubiz.hirari.core.image.thumbnail.StoredThumb
import io.github.yuubiz.hirari.core.image.thumbnail.ThumbnailKey
import io.github.yuubiz.hirari.core.model.BookId
import io.github.yuubiz.hirari.core.model.ReadingDirection
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.io.Closeable
import java.io.File
import java.time.Instant

class ThumbnailStoreAdapterTest {
    private class Recording : ThumbnailCache {
        val keys = mutableListOf<ThumbKey>()
        var lookup: ThumbLookup = ThumbLookup.Miss
        var holds = 0
        var released = 0

        override suspend fun get(key: ThumbKey): ThumbLookup = lookup.also { keys += key }

        override suspend fun put(key: ThumbKey, webp: ByteArray): File = File("put").also { keys += key }

        override suspend fun markFailed(key: ThumbKey) {
            keys += key
        }

        override suspend fun remove(key: ThumbKey) {
            keys += key
        }

        override fun hold(key: ThumbKey): Closeable {
            keys += key
            holds++
            return Closeable { released++ }
        }

        override suspend fun clear() = Unit

        override suspend fun reconcile() = Unit

        override suspend fun <T> making(key: ThumbKey, block: suspend () -> T): T {
            keys += key
            return block()
        }

        var interrupted = false

        override suspend fun settleInterrupted(): Boolean = interrupted
    }

    private val book = ThumbnailKey(BookId("book"), Instant.ofEpochMilli(1234), 99, coverEntry = 3, sizePx = 320, direction = ReadingDirection.LeftToRight)

    @Test
    fun `every call reaches the cache with the same fields`() = runBlocking {
        val cache = Recording()
        val store = ThumbnailStoreAdapter(cache)
        store.get(book)
        store.put(book, ByteArray(1))
        store.markFailed(book)
        store.remove(book)
        store.hold(book).close()
        store.making(book) {}
        val expected = ThumbKey(BookId("book"), Instant.ofEpochMilli(1234), 99, 3, 320, ReadingDirection.LeftToRight)
        assertEquals(List(6) { expected }, cache.keys)
        assertEquals(1, cache.holds)
        assertEquals(1, cache.released)
    }

    @Test
    fun `the lookups map one to one`() = runBlocking {
        val cache = Recording()
        val store = ThumbnailStoreAdapter(cache)
        val file = File("a.webp")
        cache.lookup = ThumbLookup.Hit(file)
        assertEquals(StoredThumb.Hit(file), store.get(book))
        cache.lookup = ThumbLookup.Failed
        assertEquals(StoredThumb.Failed, store.get(book))
        cache.lookup = ThumbLookup.Miss
        assertEquals(StoredThumb.Miss, store.get(book))
    }

    @Test
    fun `a folder key is not the key of a book without a chosen cover`() {
        val folder = book.copy(coverEntry = -2).toCacheKey().digest
        assertNotEquals(folder, book.copy(coverEntry = null).toCacheKey().digest)
        assertNotEquals(folder, book.toCacheKey().digest)
    }
}
