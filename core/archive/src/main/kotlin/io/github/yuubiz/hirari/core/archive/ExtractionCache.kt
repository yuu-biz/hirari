package io.github.yuubiz.hirari.core.archive

import io.github.yuubiz.hirari.core.model.BookId
import java.io.File
import java.security.MessageDigest

/**
 * The extraction cache of every book, `cacheDir/extract/` (docs/spec.md section 3.3), as the settings screen clears
 * it. Every call touches the disk, so none may run on the main thread.
 */
class ExtractionCache(private val root: File, private val clock: () -> Long = System::currentTimeMillis) {
    /**
     * Deletes the entry files of every book and the book directories left empty, and returns the bytes freed. A
     * book open in a session extracts the entries it needs again ([Extraction]), so clearing needs no word with
     * the sessions. The unfinished file of a running extraction stays unless it is an hour old, as at the start of
     * a session. A file that cannot be deleted stays and does not count.
     */
    fun clear(): Long {
        val now = clock()
        var freed = 0L
        for (book in root.listFiles { it.isDirectory }.orEmpty()) {
            for (file in book.listFiles { it.isFile }.orEmpty()) {
                val stale = file.name.endsWith(Extraction.PART) && now - file.lastModified() > Extraction.PART_MILLIS
                if (!stale && !file.name.isEntryFile()) continue
                val size = file.length()
                if (file.delete()) freed += size
            }
            book.delete()
        }
        return freed
    }

    companion object {
        /** The directory shared by sessions of one archive generation. Does not create it or touch the disk. */
        fun directory(cacheDir: File, book: BookId, size: Long, lastModifiedMs: Long?): File {
            val identity = if (lastModifiedMs == null) "$size" else "$size\n$lastModifiedMs"
            val generation = MessageDigest.getInstance("SHA-1").digest(identity.toByteArray(Charsets.UTF_8))
                .take(8).joinToString("") { "%02x".format(it) }
            return File(cacheDir, "extract/${book.value}-$generation")
        }

        /** The cache of the app whose cache directory is [cacheDir]. */
        fun under(cacheDir: File) = ExtractionCache(File(cacheDir, "extract"))

        private fun String.isEntryFile() = isNotEmpty() && all { it in '0'..'9' }
    }
}
