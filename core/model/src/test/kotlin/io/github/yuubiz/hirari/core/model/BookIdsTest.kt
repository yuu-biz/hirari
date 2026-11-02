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

    private val server = LibraryLocation.SmbShare(
        id = LocationId(1), displayName = "NAS", host = "nas.local", share = "books", basePath = "manga",
        domain = null, user = "reader", credential = CredentialRef("a"),
    )

    @Test
    fun aServerBookKeepsItsIdWhenTheAccountOrTheRowChanges() {
        val id = locationBookId(server, "a/b.zip")
        val again = server.copy(id = LocationId(9), displayName = "Other", user = "someone", credential = null, domain = "W")
        assertEquals(id, locationBookId(again, "a/b.zip"))
        assertEquals(id, locationBookId(server.copy(host = "NAS.local", share = "Books"), "a/b.zip"))
    }

    @Test
    fun aServerBookKeepsItsIdWhenTheStartingFolderMoves() {
        assertEquals(locationBookId(server, "a/b.zip"), locationBookId(server.copy(basePath = ""), "manga/a/b.zip"))
        assertNotEquals(locationBookId(server, "a/b.zip"), locationBookId(server.copy(port = 1445), "a/b.zip"))
    }

    @Test
    fun spellingsOfOnePathGiveOneId() {
        val tree = LibraryLocation.SafTree(LocationId(1), "Manga", UriString("content://a/tree/1"))
        val nfd = "a/ガ.zip"
        assertEquals(locationBookId(tree, "a/ガ.zip"), locationBookId(tree, nfd))
        assertEquals(locationBookId(tree, "a/b.zip"), locationBookId(tree, "/a/./b.zip"))
        assertEquals(locationBookId(tree, "a/b.zip"), locationBookId(tree, "a\\b.zip"))
        assertNotEquals(locationBookId(tree, "a/b.zip"), locationBookId(tree, "a/B.zip"))
    }

    @Test
    fun aFolderAndADocumentNeverShareAnId() {
        assertNotEquals(folderBookId(UriString("")), documentBookId(ByteArray(0), 0))
        assertNotEquals(folderBookId(UriString("content://a/tree/1")), folderBookId(UriString("content://a/tree/2")))
    }
}
