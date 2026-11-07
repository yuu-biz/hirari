package io.github.yuubiz.hirari.core.data

import io.github.yuubiz.hirari.core.model.ColorPalette
import io.github.yuubiz.hirari.core.model.BookId
import io.github.yuubiz.hirari.core.model.BarDisplay
import io.github.yuubiz.hirari.core.model.Book
import io.github.yuubiz.hirari.core.model.BookFormat
import io.github.yuubiz.hirari.core.model.BookSource
import io.github.yuubiz.hirari.core.model.Container
import io.github.yuubiz.hirari.core.model.BookOverrides
import io.github.yuubiz.hirari.core.model.BookProgress
import io.github.yuubiz.hirari.core.model.BookmarkSort
import io.github.yuubiz.hirari.core.model.HistorySort
import io.github.yuubiz.hirari.core.model.ImageAdjust
import io.github.yuubiz.hirari.core.model.LayoutMode
import io.github.yuubiz.hirari.core.model.LibraryLocation
import io.github.yuubiz.hirari.core.model.ReadState
import io.github.yuubiz.hirari.core.model.ReadingPosition
import io.github.yuubiz.hirari.core.model.ReadingDirection
import io.github.yuubiz.hirari.core.model.Rotation
import io.github.yuubiz.hirari.core.model.ScaleMode
import io.github.yuubiz.hirari.core.model.TrimStrength
import io.github.yuubiz.hirari.core.model.UriString
import io.github.yuubiz.hirari.core.model.locationBookId
import androidx.datastore.core.DataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.IOException
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class SettingsTransferTest {
    @Test
    fun `bar display choices export and import and missing values use portrait`() = runBlocking {
        withStorage { storage ->
            for (display in BarDisplay.entries) {
                storage.settings.update { it.copy(barDisplay = display) }
                val text = storage.transfer.export(includeReading = false)
                storage.settings.update { it.copy(barDisplay = BarDisplay.Hidden) }
                storage.import(text)
                assertEquals(display, storage.settings.settings.first().barDisplay)
            }
            storage.import("""{"format":2,"settings":{}}""")
            assertEquals(BarDisplay.Portrait, storage.settings.settings.first().barDisplay)
        }
    }

    @get:Rule
    val folder = TemporaryFolder()

    private val password = "hirari-test-password-4f1c"
    private val user = "reader-user-7731"
    private val domain = "WORKGROUP-9932"
    private val server = SmbServer(displayName = "NAS", host = "nas.local", port = 1445, share = "books", basePath = "manga", domain = domain, user = user)

    /** A fresh install: its own database, credential database and settings file. */
    private suspend fun <T> withStorage(block: suspend (Storage) -> T): T = withDatabase { storage, _ -> block(storage) }

    private suspend fun <T> withDatabase(block: suspend (Storage, HirariDatabase) -> T): T {
        val job = Job()
        val database = inMemoryDatabase()
        val credentials = inMemoryCredentialDatabase()
        val thumbs = inMemoryThumbnailIndex()
        val file = File(folder.newFolder(), "settings.json")
        try {
            val settings = openSettings(CoroutineScope(Dispatchers.IO + job)) { file }
            val launch = openLaunch(CoroutineScope(Dispatchers.IO + job)) { File(folder.newFolder(), "launch.json") }
            val thumbnails = DiskThumbnailCache(folder.newFolder(), thumbs.thumbs())
            return block(Storage(database, credentials, softwareCipher(), settings, launch, thumbnails, FontStore(folder.newFolder())), database)
        } finally {
            job.cancelAndJoin()
            database.close()
            credentials.close()
            thumbs.close()
        }
    }

    private fun at(seconds: Long) = Instant.ofEpochSecond(1_700_000_000 + seconds)

    @Test
    @org.robolectric.annotation.Config(sdk = [28, 30])
    fun `wallpaper imports use standard before Android 12`() = runBlocking {
        withStorage { storage ->
            for (fields in listOf("\"colorPalette\":\"Wallpaper\"", "\"dynamicColor\":true", "\"dynamicColor\":false")) {
                storage.import("""{"format":2,"settings":{$fields}}""")
                assertEquals(ColorPalette.Standard, storage.settings.settings.first().colorPalette)
            }
        }
    }

    @Test
    @org.robolectric.annotation.Config(sdk = [31])
    fun `imports migrate the old toggle and preserve new palette and black choices`() = runBlocking {
        withStorage { storage ->
            for ((fields, expected) in listOf(
                "\"dynamicColor\":true" to ColorPalette.Wallpaper,
                "\"dynamicColor\":false" to ColorPalette.Standard,
                "\"dynamicColor\":true,\"colorPalette\":\"Ink\"" to ColorPalette.Ink,
                "\"colorPalette\":\"#123ABC\"" to ColorPalette.Custom(io.github.yuubiz.hirari.core.model.Rgb(0x123ABC)),
            )) {
                storage.import("""{"format":2,"settings":{$fields,"pureBlack":true}}""")
                assertEquals(expected, storage.settings.settings.first().colorPalette)
                assertTrue(storage.settings.settings.first().pureBlack)
            }
        }
    }

    @Test
    fun `export fails on an IO read failure even after defaults were supplied and the notice was taken`() = runBlocking {
        val failure = IOException("disk read failed")
        val repository = DataStoreSettingsRepository(object : DataStore<GlobalSettings> {
            override val data: Flow<GlobalSettings> = flow { throw failure }

            override suspend fun updateData(transform: suspend (GlobalSettings) -> GlobalSettings): GlobalSettings = throw failure
        })
        assertEquals(GlobalSettings(), repository.settings.first())
        assertEquals(failure, repository.takeReadFailure())
        withDatabase { _, database ->
            val transfer = SettingsTransfer(repository, database)
            for (includeReading in listOf(false, true)) {
                val error = assertFailsWith<SettingsExportReadException> { transfer.export(includeReading) }
                assertEquals(failure, error.cause)
            }
        }
    }

    @Test
    fun `importing a deleted server relinks its existing book index without restoring pins or favorites`() = runBlocking {
        withDatabase { storage, database ->
            val oldId = storage.locations.addSmbShare(server, password.toCharArray())
            val bookId = locationBookId(storage.locations.get(oldId)!!, "a.zip")
            storage.books.record(Book(bookId, BookSource.InLocation(oldId, "a.zip"), BookFormat.Images(Container.Zip), "a.zip", 1000, at(0), 100))
            storage.history.record(bookId, at(1), 100)
            storage.locations.pin(oldId, "series", at(2))
            storage.locations.addFavorite(oldId, "a.zip", FavoriteKind.Book, "a.zip", at(3))
            val exported = storage.transfer.export(includeReading = true)
            storage.locations.delete(oldId)

            val plan = assertIs<ImportPreparation.Ready>(storage.transfer.prepare(exported)).plan
            val broken = object : SettingsRepository {
                override val settings = storage.settings.settings

                override suspend fun update(transform: (GlobalSettings) -> GlobalSettings) = throw IOException("disk full")
            }
            assertFailsWith<IOException> { SettingsTransfer(broken, database).apply(plan) }
            assertEquals(emptyList(), storage.locations.locations.first())
            assertEquals(BookSource.InLocation(oldId, "a.zip"), storage.books.get(bookId)!!.book.source)
            assertEquals(emptyList(), storage.locations.pinned.first())
            assertEquals(emptyList(), storage.locations.favorites.first())

            storage.transfer.apply(plan)
            val id = storage.locations.locations.first().single().id
            assertTrue(oldId != id)
            val history = storage.history.observe(HistorySort.Newest).first().single()
            assertEquals(id, history.location!!.id)
            assertEquals(BookSource.InLocation(id, "a.zip"), history.book!!.source)
            assertEquals(emptyList(), storage.locations.pinned.first())
            assertEquals(emptyList(), storage.locations.favorites.first())
        }
    }

    @Test
    fun `favorites like pins stay out of settings export and import`() = runBlocking {
        withStorage { storage ->
            val id = storage.locations.addSmbShare(server, null)
            val before = storage.transfer.export(includeReading = true)
            val settingsOnly = storage.transfer.export(includeReading = false)
            storage.locations.pin(id, "series", at(1))
            storage.locations.addFavorite(id, "series/1.cbz", FavoriteKind.Book, "1.cbz", at(2))
            assertEquals(before, storage.transfer.export(includeReading = true))
            assertEquals(settingsOnly, storage.transfer.export(includeReading = false))
            withStorage { restored ->
                val plan = assertIs<ImportPreparation.Ready>(restored.transfer.prepare(before)).plan
                restored.transfer.apply(plan)
                assertEquals(emptyList(), restored.locations.favorites.first())
                assertEquals(emptyList(), restored.locations.pinned.first())
            }
        }
    }

    private val overrides = BookOverrides(direction = ReadingDirection.LeftToRight, trim = TrimStrength.Weak, rotation = Rotation.Half, adjust = ImageAdjust(level = -2))

    private suspend fun Storage.fill(): BookId {
        settings.update { everything }
        val id = locations.addSmbShare(server, password.toCharArray())
        val book = locationBookId(locations.get(id)!!, "series/vol1.zip")
        books.updateOverrides(book) { overrides }
        progress.save(listOf(BookProgress(book, ReadState.Reading, ReadingPosition.Image(40, 180, offset = 0.25f), at(1))))
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
            assertEquals(ImportChanges(settings = 77, bookOverrides = 1, servers = 1, progress = 1, bookmarks = 1, history = 1), changes)

            assertEquals(everything, storage.settings.settings.first())
            val restored = assertIs<LibraryLocation.SmbShare>(storage.locations.locations.first().single())
            assertEquals(listOf("NAS", "nas.local", "1445", "books", "manga"), with(restored) { listOf(displayName, host, port.toString(), share, basePath) })
            assertNull(restored.user)
            assertNull(restored.domain)
            assertNull(restored.credential)

            assertEquals(book, locationBookId(restored, "series/vol1.zip"))
            assertEquals(overrides, storage.books.overrides(book).first())
            assertEquals(BookProgress(book, ReadState.Reading, ReadingPosition.Image(40, 180, offset = 0.25f), at(1)), storage.progress.get(book))
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
    fun `two entries for one book import the newest whatever their order`() = runBlocking {
        fun entry(at: Int, viewStart: Int) =
            """{"book": "dup", "state": "Reading", "position": {"type": "image", "viewStart": $viewStart, "pageCount": 100}, "updatedAt": ${at(at.toLong()).toEpochMilli()}}"""
        for (entries in listOf(listOf(entry(5, 70), entry(3, 20)), listOf(entry(3, 20), entry(5, 70)))) {
            withStorage { storage ->
                val changes = storage.import("""{"format": 1, "progress": [${entries.joinToString()}]}""")
                assertEquals(1, changes.progress)
                assertEquals(BookProgress(BookId("dup"), ReadState.Reading, ReadingPosition.Image(70, 100), at(5)), storage.progress.get(BookId("dup")))
            }
        }
    }

    @Test
    fun `an export of format 1 moves the defaults of 2026-11-04 it still holds and format 2 keeps them`() = runBlocking {
        val old = """{"layoutMode": "Auto", "coverAlone": true, "scaleMode": "FitScreen", "tapAdvancesStops": false, "tapBandPercent": 30}"""
        withStorage { storage ->
            storage.import("""{"format": 1, "settings": $old}""")
            assertEquals(GlobalSettings(tapBandPercent = 30), storage.settings.settings.first())
        }
        withStorage { storage ->
            storage.import("""{"format": 2, "settings": $old}""")
            assertEquals(
                GlobalSettings(layoutMode = LayoutMode.Auto, coverAlone = true, scaleMode = ScaleMode.FitScreen, tapAdvancesStops = false, tapBandPercent = 30),
                storage.settings.settings.first(),
            )
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

    private data class Stored(
        val settings: GlobalSettings,
        val overrides: List<BookOverridesRow>,
        val servers: List<LocationRow>,
        val progress: List<BookProgressRow>,
        val bookmarks: List<BookmarkRow>,
        val history: List<HistoryRow>,
    )

    private suspend fun Storage.stored(database: HirariDatabase) = Stored(
        settings.settings.first(),
        database.bookOverrides().all(),
        database.locations().all(),
        database.bookProgress().all(),
        database.bookmarks().all(),
        database.history().all(),
    )

    /** An install that already holds data a file would replace, and a file that would change every part of it. */
    private suspend fun Storage.holdingOtherData(exported: String): ImportPlan {
        settings.update { GlobalSettings(tapBandPercent = 40) }
        val book = BookId("kept")
        books.updateOverrides(book) { BookOverrides(direction = ReadingDirection.RightToLeft) }
        progress.save(listOf(BookProgress(book, ReadState.Reading, ReadingPosition.Image(2, 9), at(-5))))
        bookmarks.add(book, ReadingPosition.Image(1, 9), "kept", at(-4))
        history.record(book, at(-3), limit = 100)
        return assertIs<ImportPreparation.Ready>(transfer.prepare(exported)).plan
    }

    @Test
    fun `a settings write that fails changes nothing`() = runBlocking {
        val (_, exported) = withStorage { storage -> storage.fill() to storage.transfer.export(includeReading = true) }
        withDatabase { storage, database ->
            val plan = storage.holdingOtherData(exported)
            val before = storage.stored(database)
            val broken = object : SettingsRepository {
                override val settings = storage.settings.settings

                override suspend fun update(transform: (GlobalSettings) -> GlobalSettings) = throw IOException("disk full")
            }
            assertFailsWith<IOException> { SettingsTransfer(broken, database).apply(plan) }
            assertEquals(before, storage.stored(database))
        }
    }

    @Test
    fun `a database write that fails halfway changes nothing`() = runBlocking {
        val (_, exported) = withStorage { storage -> storage.fill() to storage.transfer.export(includeReading = true) }
        withDatabase { storage, database ->
            val plan = storage.holdingOtherData(exported)
            val clash = plan.bookmarks.single().copy(id = 7)
            val torn = ImportPlan(plan.settings, plan.bookOverrides, plan.servers, plan.progress, listOf(clash, clash.copy(label = "other")), plan.history)
            val before = storage.stored(database)
            assertFails { storage.transfer.apply(torn) }
            assertEquals(before, storage.stored(database))
            storage.transfer.apply(plan)
            assertEquals(everything, storage.settings.settings.first())
            assertEquals(1, database.locations().all().size)
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
