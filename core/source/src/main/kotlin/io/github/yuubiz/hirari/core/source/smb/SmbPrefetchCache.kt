package io.github.yuubiz.hirari.core.source.smb

import io.github.yuubiz.hirari.core.source.COPY_RESERVE_BYTES
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** The most bytes [SmbPrefetchCache] keeps (docs/spec.md section 5.3). */
const val SMB_PREFETCH_MAX_BYTES: Long = 256L shl 20

/**
 * Entry bytes read ahead from books on SMB, as files in [dir], which is `cacheDir/smb/` (docs/spec.md sections
 * 5.3 and 7). Holds at most [maxBytes] and drops the least recently used entries beyond it. Does not write when
 * the disk would keep less than [reserveBytes] free. Survives restarts: a new instance over the same [dir] finds
 * the entries, oldest use first.
 *
 * Blocks on disk, so never call it on the main thread. Safe to share between threads.
 */
class SmbPrefetchCache(
    private val dir: File,
    private val maxBytes: Long = SMB_PREFETCH_MAX_BYTES,
    private val reserveBytes: Long = COPY_RESERVE_BYTES,
) {
    /** One entry of one book. [book] is the book's `BookId` value; [entry] is its index in the archive. */
    data class Key(val book: String, val entry: Int)

    private val sizes = LinkedHashMap<String, Long>(16, 0.75f, true)
    private var loaded = false

    /** The bytes the cache holds now. */
    val sizeBytes: Long
        @Synchronized get() {
            load()
            return sizes.values.sum()
        }

    /** The bytes of [key], or null when the cache does not hold them. Counts as a use. */
    @Synchronized
    fun get(key: Key, expectedSize: Long? = null): ByteArray? {
        load()
        val name = key.fileName()
        if (sizes[name] == null) return null
        val file = dir.resolve(name)
        if (expectedSize != null && file.length() != expectedSize) {
            remove(key)
            return null
        }
        return try {
            file.readBytes().also { file.setLastModified(System.currentTimeMillis()) }
        } catch (e: IOException) {
            sizes.remove(name)
            null
        }
    }

    /** Whether the cache holds the bytes of [key], without reading them or counting a use. */
    @Synchronized
    fun has(key: Key): Boolean {
        load()
        return sizes.containsKey(key.fileName())
    }

    /** Stores [bytes] for [key]. Returns false when they do not fit under the bound or the free space. */
    @Synchronized
    fun put(key: Key, bytes: ByteArray): Boolean {
        load()
        if (bytes.size > maxBytes) return false
        dir.mkdirs()
        if (dir.usableSpace - bytes.size < reserveBytes) return false
        val name = key.fileName()
        val temp = dir.resolve("$name$TEMP_SUFFIX")
        try {
            temp.writeBytes(bytes)
            Files.move(temp.toPath(), dir.resolve(name).toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: IOException) {
            temp.delete()
            return false
        }
        sizes.remove(name)
        sizes[name] = bytes.size.toLong()
        trim()
        return true
    }

    /** Whether one entry can ever fit, independent of the available disk space. */
    fun fits(bytes: Long): Boolean = bytes <= maxBytes

    /** Checks capacity before downloading an entry. */
    @Synchronized
    fun canWrite(bytes: Long): Boolean {
        load()
        dir.mkdirs()
        return bytes <= maxBytes && dir.usableSpace - bytes >= reserveBytes
    }

    @Synchronized
    fun remove(key: Key) {
        load()
        dir.resolve(key.fileName()).delete()
        sizes.remove(key.fileName())
    }

    /** Drops every entry of [book]. */
    @Synchronized
    fun removeBook(book: String) {
        load()
        val prefix = bookPrefix(book)
        sizes.keys.filter { it.startsWith(prefix) }.forEach { name ->
            dir.resolve(name).delete()
            sizes.remove(name)
        }
    }

    private fun trim() {
        var total = sizes.values.sum()
        val oldestFirst = sizes.entries.iterator()
        while (total > maxBytes && oldestFirst.hasNext()) {
            val (name, size) = oldestFirst.next()
            dir.resolve(name).delete()
            oldestFirst.remove()
            total -= size
        }
    }

    private fun load() {
        if (loaded) return
        loaded = true
        val files = dir.listFiles().orEmpty()
        files.filter { it.name.endsWith(TEMP_SUFFIX) }.forEach(File::delete)
        files.filter { it.isFile && !it.name.endsWith(TEMP_SUFFIX) }
            .sortedBy(File::lastModified)
            .forEach { sizes[it.name] = it.length() }
        trim()
    }

    private fun Key.fileName() = bookPrefix(book) + entry

    private fun bookPrefix(book: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(book.toByteArray())
        return digest.take(16).joinToString("") { "%02x".format(it) } + "-"
    }

    private companion object {
        const val TEMP_SUFFIX = ".part"
    }
}
