package io.github.yuubiz.hirari.feature.text

import io.github.yuubiz.hirari.core.data.BookRepository
import io.github.yuubiz.hirari.core.data.GlobalSettings
import io.github.yuubiz.hirari.core.data.LibraryBook
import io.github.yuubiz.hirari.core.data.SettingsRepository
import io.github.yuubiz.hirari.core.model.Book
import io.github.yuubiz.hirari.core.model.BookId
import io.github.yuubiz.hirari.core.model.BookOverrides
import io.github.yuubiz.hirari.core.model.FallbackCharset
import io.github.yuubiz.hirari.core.model.LocationId
import io.github.yuubiz.hirari.core.model.Rotation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import java.nio.charset.Charset
import kotlin.test.Test
import kotlin.test.assertEquals

private class StoredOverrides(private val stored: Map<BookId, BookOverrides>) : BookRepository {
    override fun overrides(id: BookId): Flow<BookOverrides> = flowOf(stored[id] ?: BookOverrides())

    override suspend fun updateOverrides(id: BookId, change: (BookOverrides) -> BookOverrides) = error("unused")

    override suspend fun record(book: Book) = error("unused")

    override suspend fun get(id: BookId): LibraryBook? = error("unused")

    override fun inFolder(location: LocationId, folder: String): Flow<List<LibraryBook>> = error("unused")

    override fun continueReading(limit: Int): Flow<List<LibraryBook>> = error("unused")

    override suspend fun setCover(id: BookId, entry: Int?) = error("unused")
}

private class FixedSettings(value: GlobalSettings) : SettingsRepository {
    override val settings: Flow<GlobalSettings> = flowOf(value)

    override suspend fun update(transform: (GlobalSettings) -> GlobalSettings) = error("unused")
}

class EntryCharsetsTest {
    private val archive = BookId("archive")
    private val settings = FixedSettings(GlobalSettings(fallbackCharset = FallbackCharset.EucJp))

    @Test
    fun theNamesOfATextEntryUseTheCharsetChosenForItsArchiveAndTheFallbackSetting() = runTest {
        val utf8 = Charset.forName("UTF-8")
        val books = StoredOverrides(mapOf(archive to BookOverrides(rotation = Rotation.Half, nameCharset = utf8)))
        assertEquals(EntryCharsets(utf8, Charset.forName("EUC-JP")), entryCharsets(archive, books, settings))
    }

    @Test
    fun anArchiveWithNoChosenCharsetIsDetected() = runTest {
        assertEquals(EntryCharsets(null, Charset.forName("EUC-JP")), entryCharsets(archive, StoredOverrides(emptyMap()), settings))
    }
}
