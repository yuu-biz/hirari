package io.github.yuubiz.hirari

import io.github.yuubiz.hirari.core.model.BookId
import io.github.yuubiz.hirari.core.model.LocationId
import io.github.yuubiz.hirari.feature.library.ArchiveFile
import io.github.yuubiz.hirari.feature.library.TextAddress
import io.github.yuubiz.hirari.feature.text.EntryArchive
import io.github.yuubiz.hirari.feature.text.TextBookRef
import org.junit.Assert.assertEquals
import org.junit.Test

class TextRouteTest {
    @Test
    fun aTextEntryOfAnSmbArchiveReachesTheReaderByThePlaceAndThePathOfTheArchive() {
        val address = TextAddress.ArchiveEntry(ArchiveFile.OnServer(LocationId(2), "novels/soseki.zip"), entryIndex = 3, archiveId = BookId("zip"))
        assertEquals(
            TextBookRef.ArchiveEntry(EntryArchive.OnServer(LocationId(2), "novels/soseki.zip"), entryIndex = 3, archiveId = BookId("zip")),
            address.toRoute().toRef(),
        )
    }
}
