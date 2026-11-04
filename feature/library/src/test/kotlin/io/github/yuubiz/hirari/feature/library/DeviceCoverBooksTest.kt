package io.github.yuubiz.hirari.feature.library

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.yuubiz.hirari.core.image.BookResult
import io.github.yuubiz.hirari.core.image.ImageDecoding
import io.github.yuubiz.hirari.core.model.LibraryLocation
import io.github.yuubiz.hirari.core.model.LocationId
import io.github.yuubiz.hirari.core.model.ReadingDirection
import io.github.yuubiz.hirari.core.model.locationBookId
import io.github.yuubiz.hirari.core.source.smb.SmbPurpose
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What the covers ask an SMB server for is background work (docs/spec.md sections 4.4 and 8.3). */
@RunWith(RobolectricTestRunner::class)
class DeviceCoverBooksTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val smb = FakeSmb()
    private val books = DeviceCoverBooks(context.contentResolver, context.cacheDir, FakeLister(), smb)
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
