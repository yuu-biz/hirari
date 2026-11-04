package io.github.yuubiz.hirari.feature.text

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.yuubiz.hirari.core.model.BookFormat
import io.github.yuubiz.hirari.core.model.BookId
import io.github.yuubiz.hirari.core.model.BookSource
import io.github.yuubiz.hirari.core.model.LibraryLocation
import io.github.yuubiz.hirari.core.model.LocationId
import io.github.yuubiz.hirari.core.model.locationBookId
import io.github.yuubiz.hirari.core.source.ByteSource
import io.github.yuubiz.hirari.core.source.smb.PasswordUnreadableException
import io.github.yuubiz.hirari.core.source.smb.SmbConnectException
import io.github.yuubiz.hirari.core.source.smb.SmbConnectFailure
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.FileNotFoundException
import java.nio.ByteBuffer
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A file of [bytes] that reads at most [chunk] bytes a call, as a network read may, or claims [size] bytes. */
private class ServerFile(private val bytes: ByteArray, override val size: Long = bytes.size.toLong(), private val chunk: Int = 3) : ByteSource {
    var closed = false

    override fun read(offset: Long, dst: ByteBuffer): Int {
        if (offset >= bytes.size) return -1
        val count = minOf(chunk, dst.remaining(), bytes.size - offset.toInt())
        dst.put(bytes, offset.toInt(), count)
        return count
    }

    override fun close() {
        closed = true
    }
}

/** The text reader opening the texts of an SMB place through the shared connection (docs/spec.md section 4.9). */
@RunWith(RobolectricTestRunner::class)
class SmbTextTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val nas = LibraryLocation.SmbShare(LocationId(2), "NAS", "nas.local", 445, "books", "novels", null, null, null)
    private val asked = mutableListOf<String>()

    private fun open(ref: TextBookRef, file: () -> ByteSource): TextOpenResult = runBlocking {
        val smb = SmbFiles { place, path ->
            check(place == nas)
            asked += path
            file()
        }
        val resolver = ApplicationProvider.getApplicationContext<Context>().contentResolver
        opener(ref, TextSources(resolver, OnePlace(nas), smb, temp.newFolder()) { EntryCharsets(null, Charsets.UTF_8) }).open()
    }

    @Test
    fun aTextOfAnSmbPlaceOpensWithTheIdAndIndexEntryOfItsPathAndClosesTheFile() {
        val bytes = "吾輩は猫である。\n".toByteArray(Charsets.UTF_8)
        val file = ServerFile(bytes)
        val opened = open(TextBookRef.InLocation(nas.id, "/soseki//neko.txt")) { file } as TextOpenResult.Opened

        assertEquals(listOf("soseki/neko.txt"), asked)
        assertContentEquals(bytes, opened.bytes)
        assertEquals(locationBookId(nas, "soseki/neko.txt"), opened.text.id)
        assertEquals("neko.txt", opened.text.title)
        val book = opened.text.book!!
        assertEquals(BookSource.InLocation(nas.id, "soseki/neko.txt"), book.source)
        assertEquals(BookFormat.Text(charset = null), book.format)
        assertEquals(bytes.size.toLong(), book.byteSize)
        assertTrue(file.closed, "the file closes once its bytes are read")
    }

    @Test
    fun aTextLongerThanTheReaderHoldsIsNotRead() {
        val file = ServerFile(ByteArray(0), size = MAX_TEXT_BYTES + 1L)
        assertEquals(TextOpenResult.Failed(TextFailure.TooLarge), open(TextBookRef.InLocation(nas.id, "big.txt")) { file })
        assertTrue(file.closed)
    }

    @Test
    fun aServerThatCannotBeReachedOrAPasswordThatCannotBeReadSaysWhy() {
        val unreachable = open(TextBookRef.InLocation(nas.id, "a.txt")) { throw SmbConnectException(SmbConnectFailure.Unreachable, "down") }
        assertEquals(TextOpenResult.Failed(TextFailure.ReadError), unreachable)
        assertEquals(TextOpenResult.Failed(TextFailure.NoPermission), open(TextBookRef.InLocation(nas.id, "a.txt")) { throw PasswordUnreadableException() })
        assertEquals(TextOpenResult.Failed(TextFailure.NotFound), open(TextBookRef.InLocation(nas.id, "a.txt")) { throw FileNotFoundException("a.txt") })
    }

    @Test
    fun anEntryOfAnArchiveOfAPlaceThatIsGoneIsNotFound() {
        val ref = TextBookRef.ArchiveEntry(EntryArchive.InLocation(LocationId(9), "a.zip"), entryIndex = 0, archiveId = BookId("a"))
        assertEquals(TextOpenResult.Failed(TextFailure.NotFound), open(ref) { error("not asked") })
        assertEquals(emptyList(), asked)
    }
}
