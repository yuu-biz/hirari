package io.github.yuubiz.hirari.core.archive

import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File

class LibarchiveTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun cacheFile(name: String, bytes: ByteArray): File =
        File(instrumentation.targetContext.cacheDir, name).apply { writeBytes(bytes) }

    private fun corpusFile(path: String): File =
        cacheFile(path.substringAfterLast('/'), instrumentation.context.assets.open(path).use { it.readBytes() })

    private fun entryNames(file: File): List<ByteArray> =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { Libarchive.entryNames(it) }

    @Test
    fun listsZipEntryNamesAsBytesInArchiveOrder() {
        val expected = listOf("001.txt", "chapter/002.txt", "日本語.txt").map { it.toByteArray(Charsets.UTF_8).toList() }

        val names = entryNames(corpusFile("zip/names.zip"))

        assertEquals(expected, names.map { it.toList() })
    }

    @Test
    fun throwsArchiveExceptionForBytesThatAreNotAZip() {
        val file = cacheFile("not-a-zip.bin", "not a zip".toByteArray())

        assertThrows(ArchiveException::class.java) { entryNames(file) }
    }
}
