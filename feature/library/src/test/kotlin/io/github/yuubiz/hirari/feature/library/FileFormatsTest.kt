package io.github.yuubiz.hirari.feature.library

import io.github.yuubiz.hirari.core.archive.ArchiveFormat
import io.github.yuubiz.hirari.core.image.PageCandidate
import io.github.yuubiz.hirari.core.image.pageCandidate
import io.github.yuubiz.hirari.core.model.Container
import io.github.yuubiz.hirari.core.model.FileFormats
import org.junit.Test
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileFormatsTest {
    private fun file(name: String) = FolderEntry(name, "doc:$name", directory = false, size = 1, modified = null)

    @Test
    fun `every image page extension is shown as an image in either case`() {
        for (extension in FileFormats.imagePages) {
            for (suffix in listOf(extension, extension.uppercase(Locale.ROOT))) {
                val name = "page.$suffix"
                assertEquals(PageCandidate.Image, pageCandidate(name, isDirectory = false), name)
                assertIs<FileItem.Image>(file(name).toItem(showDotFiles = false), name)
            }
        }
        // Keep the original mismatch visible even if the catalogue loses this alias.
        assertIs<FileItem.Image>(file("page.JFIF").toItem(showDotFiles = false))
    }

    @Test
    fun `every listed archive extension names a format supported by the archive layer`() {
        // ArchiveSession reports these formats after opening by content, without an extension gate.
        val supported = ArchiveFormat.entries.map { format ->
            when (format) {
                ArchiveFormat.Zip -> ArchiveKind.Zip
                ArchiveFormat.Rar, ArchiveFormat.Rar5 -> ArchiveKind.Rar
                ArchiveFormat.SevenZip -> ArchiveKind.SevenZip
                ArchiveFormat.Tar -> ArchiveKind.Tar
            }
        }.toSet()
        val expected = mapOf(
            Container.Zip to ArchiveKind.Zip,
            Container.Rar to ArchiveKind.Rar,
            Container.SevenZip to ArchiveKind.SevenZip,
            Container.Tar to ArchiveKind.Tar,
        )
        for ((extension, container) in FileFormats.archiveBooks) {
            for (suffix in listOf(extension, extension.uppercase(Locale.ROOT))) {
                val name = "book.$suffix"
                val item = assertIs<FileItem.Archive>(file(name).toItem(showDotFiles = false), name)
                assertTrue(item.kind in supported, name)
                assertEquals(expected.getValue(container), item.kind, name)
            }
        }
        assertEquals(supported, FileFormats.archiveBooks.keys.map {
            assertIs<FileItem.Archive>(file("book.$it").toItem(false)).kind
        }.toSet())
    }

    @Test
    fun `listing and page selection keep their different policies`() {
        for (extension in FileFormats.pdfs) {
            assertEquals(ArchiveKind.Pdf, assertIs<FileItem.Archive>(file("book.$extension").toItem(false)).kind)
        }
        for (extension in FileFormats.texts) {
            assertIs<FileItem.Text>(file("readme.${extension.uppercase(Locale.ROOT)}").toItem(false))
            assertEquals(PageCandidate.Excluded, pageCandidate("readme.$extension", false))
        }
        for (name in listOf("page", "page.unknown", "info.nfo", "ComicInfo.xml", "book.gz")) {
            assertNull(file(name).toItem(false), name)
        }
        assertEquals(PageCandidate.Sniff, pageCandidate("page.unknown", false))
        assertEquals(PageCandidate.Excluded, pageCandidate("info.nfo", false))
        assertNull(file(".page.jfif").toItem(false))
        assertIs<FileItem.Image>(file(".page.jfif").toItem(true))
        assertEquals(PageCandidate.Excluded, pageCandidate(".page.jfif", false))
    }
}
