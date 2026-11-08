package io.github.yuubiz.hirari.core.data

import io.github.yuubiz.hirari.core.model.BookId
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
class ProvisionalCoversTest {
    @Test fun `a cover is remembered per book until it is forgotten`() = runTest {
        val db = inMemoryThumbnailIndex()
        try {
            val covers = RoomProvisionalCovers(db.provisionalCovers())
            covers.remember(BookId("a"), 4)
            covers.remember(BookId("b"), 0)
            covers.remember(BookId("a"), 2)
            assertEquals(2, covers.entry(BookId("a")))
            covers.forget(BookId("a"))
            assertNull(covers.entry(BookId("a")))
            assertEquals(0, covers.entry(BookId("b")))
        } finally { db.close() }
    }
}
