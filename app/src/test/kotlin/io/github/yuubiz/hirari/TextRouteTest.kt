package io.github.yuubiz.hirari

import io.github.yuubiz.hirari.core.model.BookId
import io.github.yuubiz.hirari.core.model.LocationId
import io.github.yuubiz.hirari.feature.library.ArchiveFile
import io.github.yuubiz.hirari.feature.library.TextAddress
import io.github.yuubiz.hirari.feature.text.EntryArchive
import io.github.yuubiz.hirari.feature.text.TextBookRef
import io.github.yuubiz.hirari.core.model.UriString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class TextRouteTest {
    @Test
    fun bookmarkAddressesKeepTheirEntryAndOffsetThroughTheNavigationRoute() {
        val addresses = listOf(
            TextAddress.Document(UriString("content://books/novel.txt"), 120),
            TextAddress.InLocation(LocationId(1), "novel.txt", startOffset = 120),
            TextAddress.ArchiveEntry(ArchiveFile.Document(UriString("content://books/novels.zip")), 3, BookId("zip"), 120),
            TextAddress.ArchiveEntry(ArchiveFile.InLocation(LocationId(1), "novels.zip"), 3, BookId("zip"), 120),
        )
        for (address in addresses) {
            val route = address.toRoute()
            val restored = Json.decodeFromString<TextRoute>(Json.encodeToString(TextRoute.serializer(), route))
            assertEquals(120L, restored.startOffset)
            if (route.kind == TextRoute.Kind.InLocation || route.kind == TextRoute.Kind.LocationArchiveEntry) {
                assertEquals(route.toRef(), restored.toRef())
            }
            assertEquals(route, restored)
        }
    }

    @Test
    fun aTextEntryOfAnSmbArchiveReachesTheReaderByThePlaceAndThePathOfTheArchive() {
        val address = TextAddress.ArchiveEntry(ArchiveFile.InLocation(LocationId(2), "novels/soseki.zip"), entryIndex = 3, archiveId = BookId("zip"))
        assertEquals(
            TextBookRef.ArchiveEntry(EntryArchive.InLocation(LocationId(2), "novels/soseki.zip"), entryIndex = 3, archiveId = BookId("zip")),
            address.toRoute().toRef(),
        )
    }
}
