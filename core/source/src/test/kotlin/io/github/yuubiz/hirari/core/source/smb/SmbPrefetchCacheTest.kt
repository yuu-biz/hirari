package io.github.yuubiz.hirari.core.source.smb

import io.github.yuubiz.hirari.core.source.smb.SmbPrefetchCache.Key
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SmbPrefetchCacheTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val dir by lazy { temp.root.resolve("smb") }

    private fun cache(maxBytes: Long = 300, reserveBytes: Long = 0) = SmbPrefetchCache(dir, maxBytes, reserveBytes)

    private fun bytes(size: Int, seed: Int) = ByteArray(size) { (it + seed).toByte() }

    private fun SmbPrefetchCache.held(vararg keys: Key) = keys.filter { get(it) != null }

    @Test
    fun `returns what was stored and nothing for other entries`() {
        val cache = cache()
        assertTrue(cache.put(Key("book", 3), bytes(100, 1)))
        assertArrayEquals(bytes(100, 1), cache.get(Key("book", 3)))
        assertNull(cache.get(Key("book", 4)))
        assertNull(cache.get(Key("other", 3)))
    }

    @Test
    fun `asking whether an entry is held does not count as a use`() {
        val cache = cache()
        val (a, b, c, d) = List(4) { Key("book", it) }
        cache.put(a, bytes(100, 0))
        cache.put(b, bytes(100, 1))
        cache.put(c, bytes(100, 2))
        assertTrue(cache.has(a))
        assertFalse(cache.has(d))

        cache.put(d, bytes(100, 3))
        assertFalse(cache.has(a))
        assertEquals(listOf(b, c, d), listOf(a, b, c, d).filter(cache::has))
    }

    @Test
    fun `beyond the bound the least recently used entries go first`() {
        val cache = cache()
        val (a, b, c, d) = List(4) { Key("book", it) }
        cache.put(a, bytes(100, 0))
        cache.put(b, bytes(100, 1))
        cache.put(c, bytes(100, 2))
        cache.get(a)
        cache.put(d, bytes(100, 3))
        assertEquals(listOf(a, c, d), cache.held(a, b, c, d))
        assertEquals(300L, cache.sizeBytes)
        assertEquals(3, dir.listFiles()!!.size)
    }

    @Test
    fun `storing an entry again replaces it`() {
        val cache = cache()
        cache.put(Key("book", 0), bytes(100, 0))
        cache.put(Key("book", 0), bytes(150, 9))
        assertArrayEquals(bytes(150, 9), cache.get(Key("book", 0)))
        assertEquals(150L, cache.sizeBytes)
    }

    @Test
    fun `an entry larger than the bound or the free space is not stored`() {
        assertFalse(cache().put(Key("book", 0), bytes(301, 0)))
        assertFalse(cache(reserveBytes = Long.MAX_VALUE / 2).put(Key("book", 0), bytes(10, 0)))
        assertEquals(0L, cache().sizeBytes)
    }

    @Test
    fun `a new instance finds the entries in their order of use and drops partial writes`() {
        val first = cache()
        val keys = List(3) { Key("book", it) }
        keys.forEachIndexed { i, key -> first.put(key, bytes(100, i)) }
        dir.listFiles()!!.forEach { file ->
            val entry = file.name.substringAfterLast('-').toInt()
            file.setLastModified(1_000_000L * (if (entry == 0) 9 else entry))
        }
        dir.resolve("leftover.part").writeBytes(bytes(50, 0))

        val second = cache()
        assertEquals(300L, second.sizeBytes)
        assertFalse(dir.resolve("leftover.part").exists())
        second.put(Key("book", 3), bytes(100, 3))
        assertEquals("entry 1 was used longest ago", listOf(keys[0], keys[2]), second.held(*keys.toTypedArray()))
    }

    @Test
    fun `removing a book drops only its entries`() {
        val cache = cache()
        cache.put(Key("one", 0), bytes(50, 0))
        cache.put(Key("one", 1), bytes(50, 1))
        cache.put(Key("two", 0), bytes(50, 2))
        cache.removeBook("one")
        assertEquals(listOf(Key("two", 0)), cache.held(Key("one", 0), Key("one", 1), Key("two", 0)))
        assertEquals(1, dir.listFiles()!!.size)
    }
}
