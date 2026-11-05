package io.github.yuubiz.hirari.feature.library

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.yuubiz.hirari.core.image.BookResult
import io.github.yuubiz.hirari.core.image.ImageDecoding
import io.github.yuubiz.hirari.core.model.FallbackCharset
import io.github.yuubiz.hirari.core.model.LibraryLocation
import io.github.yuubiz.hirari.core.model.LocationId
import io.github.yuubiz.hirari.core.model.ReadingDirection
import io.github.yuubiz.hirari.core.model.locationBookId
import io.github.yuubiz.hirari.core.source.smb.SmbPurpose
import io.github.yuubiz.hirari.core.source.FileChannelSource
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.io.File
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What the covers ask an SMB server for is background work (docs/spec.md sections 4.4 and 8.3). */
@RunWith(RobolectricTestRunner::class)
class DeviceCoverBooksTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val smb = FakeSmb()
    private val indexed = FakeBooks()
    private val settings = FakeSettings()
    private val books = DeviceCoverBooks(context.contentResolver, context.cacheDir, FakeLister(), smb, indexed, settings)
    private val nas = LibraryLocation.SmbShare(LocationId(2), "NAS", "nas.local", 445, "books", "", null, null, null)
    private val at = Instant.parse("2026-02-03T04:05:06Z")
    private val decoding = ImageDecoding(budgetBytes = 64L shl 20, parallelism = 1)

    private fun spec(origin: CoverOrigin) =
        CoverSpec.Book(locationBookId(nas, "s/1.zip"), at, 100, null, ReadingDirection.RightToLeft, origin)

    /** The files of a folder book open when a page is read. */
    private suspend fun readFirstPage(result: BookResult) {
        (result as BookResult.Opened).book.use { it.raw(0) }
    }

    @Test
    fun `a CP932 archive cover uses the same first page as the list with a chosen charset`() = runTest {
        val cp932 = Charset.forName("windows-31j")
        val file = zip(cp932, listOf("あ.jpg", "ア.jpg"))
        try {
            val stored = object : SmbPlaces by smb {
                override suspend fun open(place: LibraryLocation.SmbShare, path: String, purpose: SmbPurpose) =
                    FileChannelSource(file.inputStream().channel)
            }
            val covers = DeviceCoverBooks(context.contentResolver, context.cacheDir, FakeLister(), stored, indexed, settings)
            val spec = spec(CoverOrigin.Archive(nas, "s/1.zip", null))
            val archive = DeviceArchives(context.contentResolver, context.cacheDir, stored)
                .open(nas, "s/1.zip", null, spec.id) as ArchiveOpenResult.Opened
            archive.contents.use { contents ->
                val firstEntries = mutableListOf<Int>()
                for (chosen in listOf(cp932, Charsets.UTF_8)) {
                    indexed.updateOverrides(spec.id) { it.copy(nameCharset = chosen) }
                    val charset = contents.charset(chosen, cp932)
                    val rows = contents.rows(charset, spec.id)
                    (covers.open(spec, decoding) as BookResult.Opened).book.use { cover ->
                        assertEquals(rows.pages.map { it.entryIndex }, cover.pages.map { it.entryIndex })
                        assertEquals(rows.pages.first().name, cover.pages.first().name)
                        firstEntries += cover.pages.first().entryIndex
                    }
                }
                assertEquals(listOf(0, 1), firstEntries, "the fixture must change its first page with the choice")
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun `an automatic cover follows the global fallback when no charset can be detected`() = runTest {
        val file = zip(Charsets.ISO_8859_1, listOf("\u0085.jpg", "\u00a6.jpg"))
        try {
            val stored = object : SmbPlaces by smb {
                override suspend fun open(place: LibraryLocation.SmbShare, path: String, purpose: SmbPurpose) =
                    FileChannelSource(file.inputStream().channel)
            }
            val covers = DeviceCoverBooks(context.contentResolver, context.cacheDir, FakeLister(), stored, indexed, settings)
            settings.value.value = settings.value.value.copy(fallbackCharset = FallbackCharset.Utf8)
            (covers.open(spec(CoverOrigin.Archive(nas, "s/1.zip", null)), decoding) as BookResult.Opened).book.use {
                assertEquals(listOf("�.jpg", "�.jpg"), it.pages.map { page -> page.name })
            }
        } finally {
            file.delete()
        }
    }

    private fun zip(charset: Charset, names: List<String>): File =
        File.createTempFile("cover-names", ".zip", context.cacheDir).also { file ->
            ZipOutputStream(file.outputStream(), charset).use { zip ->
                names.forEachIndexed { index, name ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(byteArrayOf(index.toByte()))
                    zip.closeEntry()
                }
            }
        }

    @Test
    fun `opening an archive for its cover asks the server in the background`() = runTest {
        books.open(spec(CoverOrigin.Archive(nas, "s/1.zip", null)), decoding)
        assertEquals(listOf("s/1.zip" to SmbPurpose.Background), smb.opened)
    }

    @Test
    fun `opening an image for its cover asks the server in the background`() = runTest {
        readFirstPage(books.open(spec(CoverOrigin.Image(nas, "s/1.png", null)), decoding))
        assertEquals(listOf("s/1.png" to SmbPurpose.Background), smb.opened)
    }

    @Test
    fun `opening a folder of images for its cover lists and opens in the background`() = runTest {
        smb.folders["s"] = listOf(FolderEntry("1.png", null, false, 10, at))
        readFirstPage(books.open(spec(CoverOrigin.ImageFolder(nas, "s", null)), decoding))
        assertEquals(listOf(SmbPurpose.Background), smb.listPurposes)
        assertTrue(smb.opened.isNotEmpty())
        assertTrue(smb.opened.all { it.second == SmbPurpose.Background })
    }

    @Test
    fun `listing a folder to search it for a cover is background work`() = runTest {
        smb.folders["s"] = emptyList()
        books.list(nas, "s", null)
        assertEquals(listOf(SmbPurpose.Background), smb.listPurposes)
    }
}
