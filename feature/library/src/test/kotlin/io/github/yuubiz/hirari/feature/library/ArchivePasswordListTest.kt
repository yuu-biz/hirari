package io.github.yuubiz.hirari.feature.library

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.yuubiz.hirari.core.model.LibraryLocation
import io.github.yuubiz.hirari.core.model.LocationId
import io.github.yuubiz.hirari.core.model.ReadingDirection
import io.github.yuubiz.hirari.core.model.locationBookId
import io.github.yuubiz.hirari.core.source.FileChannelSource
import io.github.yuubiz.hirari.core.source.smb.SmbPurpose
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
class ArchivePasswordListTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val place = LibraryLocation.SmbShare(LocationId(1), "books", "host", 445, "books", "", null, null, null)

    @Test fun `traditional ZIP pages stay readable and request covers while AES pages retain the mark`() = runTest {
        val file = File.createTempFile("password-list", ".zip", context.cacheDir)
        try {
            ZipOutputStream(file.outputStream()).use { zip ->
                for (name in listOf("traditional.jpg", "aes.jpg")) {
                    zip.putNextEntry(ZipEntry(name)); zip.write(byteArrayOf(1)); zip.closeEntry()
                }
            }
            val bytes = file.readBytes()
            var entry = 0
            for (i in 0 until bytes.size - 46) {
                if (bytes[i] == 0x50.toByte() && bytes[i + 1] == 0x4b.toByte() && bytes[i + 2] == 1.toByte() && bytes[i + 3] == 2.toByte()) {
                    bytes[i + 8] = (bytes[i + 8].toInt() or 1).toByte()
                    if (entry++ == 1) bytes[i + 10] = 99
                }
            }
            file.writeBytes(bytes)
            val smb = object : SmbPlaces by FakeSmb() {
                override suspend fun open(place: LibraryLocation.SmbShare, path: String, purpose: SmbPurpose) =
                    FileChannelSource(file.inputStream().channel)
            }
            val id = locationBookId(place, "locked.zip")
            val archives = DeviceArchives(context.contentResolver, context.cacheDir, smb)
            val opened = assertIs<ArchiveOpenResult.Opened>(archives.open(place, "locked.zip", null, id))
            opened.contents.use { contents ->
                val pages = contents.rows(Charsets.UTF_8, id).pages.associateBy { it.name }
                assertEquals(2, pages.size)
                val traditional = pages.getValue("traditional.jpg")
                val aes = pages.getValue("aes.jpg")
                assertEquals(false, traditional.unreadable)
                assertEquals(true, aes.unreadable)
                val base = CoverSpec.Book(id, Instant.EPOCH, file.length(), null, ReadingDirection.RightToLeft,
                    CoverOrigin.Archive(place, "locked.zip", null))
                val covers = PageCovers(CoverLayout(Fixture().covers, 300), base)
                assertEquals(traditional.entryIndex, assertNotNull(covers.ref(traditional)).spec.let { (it as CoverSpec.Book).coverEntry })
                assertNull(covers.ref(aes))
            }
        } finally { file.delete() }
    }
}
