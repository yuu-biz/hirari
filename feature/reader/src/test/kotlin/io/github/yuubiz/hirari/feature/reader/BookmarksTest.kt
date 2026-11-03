package io.github.yuubiz.hirari.feature.reader

import io.github.yuubiz.hirari.core.data.defaultBookmarkLabel
import io.github.yuubiz.hirari.core.model.BookId
import io.github.yuubiz.hirari.core.model.Bookmark
import io.github.yuubiz.hirari.core.model.KeyBindings
import io.github.yuubiz.hirari.core.model.KeyCodes
import io.github.yuubiz.hirari.core.model.KeyResult
import io.github.yuubiz.hirari.core.model.ReadingDirection
import io.github.yuubiz.hirari.core.model.ReadingPosition
import io.github.yuubiz.hirari.core.model.VolumeKeys
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BookmarksTest {
    private fun mark(position: ReadingPosition, label: String = defaultBookmarkLabel(position)) = Bookmark(1, BookId("b"), position, label, Instant.EPOCH)

    @Test
    fun aBookmarkKeepsThePositionTheSavedPositionWouldAndIsNamedByItsPage() {
        val position = bookmarkAt(11, 200)
        assertEquals(ReadingPosition.Image(11, 200), position)
        assertEquals("p. 12", defaultBookmarkLabel(position))
    }

    @Test
    fun aBlankNameIsTheDefaultOneAndATypedNameIsTrimmed() {
        assertNull(bookmarkLabel("   "))
        assertEquals("扉", bookmarkLabel(" 扉 "))
    }

    @Test
    fun aBookmarkGoesToItsPageAndAPagePastTheEndGoesToTheLast() {
        assertEquals(11, mark(ReadingPosition.Image(11, 200)).page(200))
        assertEquals(99, mark(ReadingPosition.Image(150, 200)).page(100))
        assertNull(mark(ReadingPosition.Text(10, 100)).page(100))
    }

    @Test
    fun theListShowsThePageOnlyBesideANameOfItsOwn() {
        assertEquals(false, mark(ReadingPosition.Image(11, 200)).showsPage)
        assertEquals(true, mark(ReadingPosition.Image(11, 200), label = "扉").showsPage)
    }

    @Test
    fun theNameFieldGetsEveryKeyButBackAndMenu() {
        val keys = KeyBindings(ReadingDirection.RightToLeft, mirrored = false, volumeKeys = VolumeKeys.DownAdvances)
        for (sheet in listOf(ReaderSheet.AddBookmark(3), ReaderSheet.Bookmarks)) {
            assertEquals(KeyResult.NotHandled, keys.onKey(KeyCodes.DEL, pressed = true, sheet = sheet))
            assertEquals(KeyResult.NotHandled, keys.onKey(KeyCodes.DPAD_LEFT, pressed = true, sheet = sheet))
        }
    }
}
