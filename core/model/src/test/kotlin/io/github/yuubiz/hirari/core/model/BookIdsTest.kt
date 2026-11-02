package io.github.yuubiz.hirari.core.model

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class BookIdsTest {
    private val bytes = Random(7).nextBytes(BOOK_ID_PREFIX_BYTES + 1000)

    @Test
    fun theSameBytesAndSizeGiveTheSameId() {
        assertEquals(documentBookId(bytes.copyOf(BOOK_ID_PREFIX_BYTES), 5_000_000), documentBookId(bytes.copyOf(BOOK_ID_PREFIX_BYTES), 5_000_000))
    }

    @Test
    fun bytesPastThePrefixDoNotCount() {
        val changed = bytes.copyOf().also { it[BOOK_ID_PREFIX_BYTES + 10] = (it[BOOK_ID_PREFIX_BYTES + 10] + 1).toByte() }
        assertEquals(documentBookId(bytes, bytes.size.toLong()), documentBookId(changed, bytes.size.toLong()))
    }

    @Test
    fun aDifferentByteInThePrefixOrADifferentSizeGivesAnotherId() {
        val changed = bytes.copyOf().also { it[100] = (it[100] + 1).toByte() }
        val id = documentBookId(bytes, 1000)
        assertNotEquals(id, documentBookId(changed, 1000))
        assertNotEquals(id, documentBookId(bytes, 1001))
    }

    @Test
    fun anIdIsTheHexOfASha256() {
        val id = documentBookId(ByteArray(10), 10).value
        assertEquals(64, id.length)
        assertTrue(id.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun aFolderAndADocumentNeverShareAnId() {
        assertNotEquals(folderBookId(UriString("")), documentBookId(ByteArray(0), 0))
        assertNotEquals(folderBookId(UriString("content://a/tree/1")), folderBookId(UriString("content://a/tree/2")))
    }
}
