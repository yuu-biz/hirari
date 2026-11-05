package io.github.yuubiz.hirari.core.archive

import java.io.File

/**
 * The extraction cache of every book, `cacheDir/extract/` (docs/spec.md section 3.3), as the settings screen clears
 * it. Every call touches the disk, so none may run on the main thread.
 */
class ExtractionCache(private val root: File, private val clock: () -> Long = System::currentTimeMillis) {
    /**
     * Deletes the entry files and generation stamps of every book and the book directories left empty, and returns the bytes freed. A
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
                if (!stale && !file.name.isEntryFile() && file.name != Extraction.GENERATION) continue
                val size = file.length()
                if (file.delete()) freed += size
            }
            book.delete()
        }
        return freed
    }

    companion object {
        /** The cache of the app whose cache directory is [cacheDir]. */
        fun under(cacheDir: File) = ExtractionCache(File(cacheDir, "extract"))

        private fun String.isEntryFile() = isNotEmpty() && all { it in '0'..'9' }
    }
}
