package io.github.yuubiz.hirari.core.data

import io.github.yuubiz.hirari.core.model.BookId
import io.github.yuubiz.hirari.core.model.BookOverrides
import io.github.yuubiz.hirari.core.model.BookProgress
import io.github.yuubiz.hirari.core.model.BookmarkSort
import io.github.yuubiz.hirari.core.model.HistorySort
import io.github.yuubiz.hirari.core.model.ImageAdjust
import io.github.yuubiz.hirari.core.model.LibraryLocation
import io.github.yuubiz.hirari.core.model.ReadState
import io.github.yuubiz.hirari.core.model.ReadingPosition
import io.github.yuubiz.hirari.core.model.ReadingDirection
import io.github.yuubiz.hirari.core.model.Rotation
import io.github.yuubiz.hirari.core.model.TrimStrength
import io.github.yuubiz.hirari.core.model.UriString
import io.github.yuubiz.hirari.core.model.locationBookId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class SettingsTransferTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val password = "hirari-test-password-4f1c"
    private val user = "reader-user-7731"
    private val domain = "WORKGROUP-9932"
    private val server = SmbServer(displayName = "NAS", host = "nas.local", port = 1445, share = "books", basePath = "manga", domain = domain, user = user)

    /** A fresh install: its own database, credential database and settings file. */
    private suspend fun <T> withStorage(block: suspend (Storage) -> T): T {
        val job = Job()
        val database = inMemoryDatabase()
        val credentials = inMemoryCredentialDatabase()
        val thumbs = inMemoryThumbnailIndex()
        val file = File(folder.newFolder(), "settings.json")
        try {
            val settings = openSettings(CoroutineScope(Dispatchers.IO + job)) { file }
            val thumbnails = DiskThumbnailCache(folder.newFolder(), thumbs.thumbs())
            return block(Storage(database, credentials, softwareCipher(), settings, thumbnails))
        } finally {
            job.cancelAndJoin()
            database.close()
            credentials.close()
            thumbs.close()
        }
    }

    private fun at(seconds: Long) = Instant.ofEpochSecond(1_700_000_000 + seconds)

    private val overrides = BookOverrides(direction = ReadingDirection.LeftToRight, trim = TrimStrength.Weak, rotation = Rotation.Half, adjust = ImageAdjust(level = -2))

    private suspend fun Storage.fill(): BookId {
        settings.update { everything }
        val id = locations.addSmbShare(server, password.toCharArray())
        val book = locationBookId(locations.get(id)!!, "series/vol1.zip")
        books.setOverrides(book, overrides)
        progress.save(listOf(BookProgress(book, ReadState.Reading, ReadingPosition.Image(40, 180), at(1))))
        bookmarks.add(book, ReadingPosition.Image(12, 180), "fight", at(2))
        history.record(book, at(3), limit = 100)
        return book
    }

    private suspend fun Storage.import(text: String): ImportChanges {
        val ready = assertIs<ImportPreparation.Ready>(transfer.prepare(text))
        transfer.apply(ready.plan)
        return ready.changes
    }

    @Test
    fun `an export holds neither the password nor the user name of a server`() = runBlocking {
        val exported = withStorage { storage ->
            storage.fill()
            storage.transfer.export(includeReading = true)
        }
        assertFalse(password in exported, "the password is in the export")
        assertFalse(user in exported, "the user name is in the export")
        assertFalse(domain in exported, "the domain is in the export")
        assertTrue("nas.local" in exported && "\"books\"" in exported && "manga" in exported)
    }

    @Test
    fun `export, a fresh install and import bring back settings, overrides, servers and reading`() = runBlocking {
        val (book, exported) = withStorage { storage -> storage.fill() to storage.transfer.export(includeReading = true) }
        withStorage { storage ->
            val changes = storage.import(exported)
            assertEquals(ImportChanges(settings = 63, bookOverrides = 1, servers = 1, progress = 1, bookmarks = 1, history = 1), changes)

            assertEquals(everything, storage.settings.settings.first())
            val restored = assertIs<LibraryLocation.SmbShare>(storage.locations.locations.first().single())
            assertEquals(listOf("NAS", "nas.local", "1445", "books", "manga"), with(restored) { listOf(displayName, host, port.toString(), share, basePath) })
            assertNull(restored.user)
            assertNull(restored.domain)
            assertNull(restored.credential)

            assertEquals(book, locationBookId(restored, "series/vol1.zip"))
            assertEquals(overrides, storage.books.overrides(book).first())
            assertEquals(BookProgress(book, ReadState.Reading, ReadingPosition.Image(40, 180), at(1)), storage.progress.get(book))
            assertEquals(listOf("fight"), storage.bookmarks.inBook(book).first().map { it.label })
            assertEquals(listOf(book), storage.history.observe(HistorySort.Newest).first().map { it.entry.book })
        }
    }

    @Test
    fun `without reading the export leaves positions, bookmarks and history out`() = runBlocking {
        val exported = withStorage { storage ->
            storage.fill()
            storage.transfer.export(includeReading = false)
        }
        withStorage { storage ->
            val changes = storage.import(exported)
            assertEquals(listOf(0, 0, 0), listOf(changes.progress, changes.bookmarks, changes.history))
            assertEquals(everything, storage.settings.settings.first())
            assertEquals(emptyList(), storage.bookmarks.observe(BookmarkSort.Newest).first())
        }
    }

    @Test
    fun `importing the same file again changes nothing`() = runBlocking {
        withStorage { storage ->
            storage.fill()
            val exported = storage.transfer.export(includeReading = true)
            assertEquals(0, storage.import(exported).total)
            assertEquals(1, storage.locations.locations.first().size)
            assertEquals(1, storage.bookmarks.observe(BookmarkSort.Newest).first().single().bookmarks.size)
        }
    }

    @Test
    fun `older progress in a file does not replace newer progress`() = runBlocking {
        withStorage { storage ->
            val book = storage.fill()
            val exported = storage.transfer.export(includeReading = true)
            val newer = BookProgress(book, ReadState.Read, ReadingPosition.Image(179, 180), at(10))
            storage.progress.save(listOf(newer))
            assertEquals(0, storage.import(exported).progress)
            assertEquals(newer, storage.progress.get(book))
        }
    }

    @Test
    fun `a bad value or entry falls back alone and the rest imports`() = runBlocking {
        val file = """
            {"format": 7, "future": {"x": 1},
             "settings": {"coverAlone": "yes", "tapBandPercent": 95, "scaleMode": "Zoom", "librarySort": "Oldest"},
             "bookOverrides": {"a": {"trim": "Strong", "rotation": 45, "adjustLevel": 9}, "b": "not an object"},
             "servers": [{"host": "nas", "share": "s", "port": 70000}, {"host": "no share"}, {"displayName": "x", "host": "", "share": "s"}],
             "progress": [
               {"book": "good", "state": "Read", "position": {"type": "image", "viewStart": 3, "pageCount": 10}, "updatedAt": 5},
               {"book": "bad state", "state": "Skimmed", "updatedAt": 5},
               {"book": "outside", "state": "Reading", "position": {"type": "image", "viewStart": 10, "pageCount": 10}, "updatedAt": 5},
               {"book": "text", "state": "Reading", "position": {"type": "text", "charOffset": 50, "totalChars": 100}, "updatedAt": 5},
               "junk"
             ],
             "bookmarks": [{"book": "good", "position": {"type": "scroll"}, "label": "x", "createdAt": 1}],
             "history": [{"book": "good", "openedAt": 9}, {"book": 3}]
            }
        """.trimIndent()
        withStorage { storage ->
            storage.import(file)
            assertEquals(GlobalSettings(tapBandPercent = 90, librarySort = everything.librarySort), storage.settings.settings.first())
            assertEquals(BookOverrides(trim = TrimStrength.Strong, adjust = ImageAdjust(level = 5)), storage.books.overrides(BookId("a")).first())
            assertEquals(BookOverrides(), storage.books.overrides(BookId("b")).first())
            val restored = assertIs<LibraryLocation.SmbShare>(storage.locations.locations.first().single())
            assertEquals(listOf("nas", "nas", "65535"), listOf(restored.displayName, restored.host, restored.port.toString()))
            assertEquals(ReadState.Read, storage.progress.get(BookId("good"))?.state)
            assertEquals(ReadingPosition.Text(50, 100), storage.progress.get(BookId("text"))?.position)
            assertNull(storage.progress.get(BookId("bad state")))
            assertNull(storage.progress.get(BookId("outside")))
            assertEquals(emptyList(), storage.bookmarks.observe(BookmarkSort.Newest).first())
            assertEquals(listOf("good"), storage.history.observe(HistorySort.Newest).first().map { it.entry.book.value })
        }
    }

    @Test
    fun `a file that is not a settings file is refused whole`() = runBlocking {
        withStorage { storage ->
            for (text in listOf("hello", "[1]", """{"settings": {}}""", """{"format": "one"}""")) {
                assertEquals(ImportPreparation.NotASettingsFile, storage.transfer.prepare(text), text)
            }
        }
    }

    @Test
    fun `a SAF place is not exported`() = runBlocking {
        val exported = withStorage { storage ->
            storage.locations.addSafTree("Manga", UriString("content://com.example/tree/secret-folder"))
            storage.transfer.export(includeReading = true)
        }
        assertFalse("secret-folder" in exported)
    }
}
