package io.github.yuubiz.hirari.feature.text

import io.github.yuubiz.hirari.core.model.BookFormat
import io.github.yuubiz.hirari.core.model.BookId
import io.github.yuubiz.hirari.core.model.BookSource
import io.github.yuubiz.hirari.core.model.LocationId
import io.github.yuubiz.hirari.core.model.TextEntry
import io.github.yuubiz.hirari.core.model.entryBookId
import io.github.yuubiz.hirari.core.source.FileChannelSource
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class OpenEntryTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `an archive text carries its archive address and stored entry into the library index`() = runTest {
        val file = temp.newFile("novels.zip")
        ZipOutputStream(file.outputStream()).use { zip ->
            for (name in listOf("first.txt", "folder/second.txt")) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(name.toByteArray())
                zip.closeEntry()
            }
        }
        val archiveId = BookId("archive")
        val archive = EntryArchive.InLocation(LocationId(2), "novels.zip")
        val source = FileChannelSource(FileChannel.open(file.toPath(), StandardOpenOption.READ))
        val opened = assertIs<TextOpenResult.Opened>(openEntry(source, archiveId, 1, EntryCharsets(null, Charsets.UTF_8), archive))
        try {
            val book = assertNotNull(opened.text.book)
            assertEquals(entryBookId(archiveId, "folder/second.txt".toByteArray()), book.id)
            assertEquals(BookSource.InLocation(archive.location, archive.path), book.source)
            assertEquals(TextEntry(archiveId, 1), book.textEntry)
            assertEquals(BookFormat.Text(null), book.format)
            assertEquals("folder/second.txt", opened.bytes.toString(Charsets.UTF_8))
        } finally {
            opened.text.close()
        }
    }
}
