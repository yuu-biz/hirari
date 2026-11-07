package io.github.yuubiz.hirari.core.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import io.github.yuubiz.hirari.core.model.BookId
import io.github.yuubiz.hirari.core.model.CredentialRef
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import io.github.yuubiz.hirari.core.model.ReadingDirection
import java.io.File
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
class BookPasswordStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun `saving a password invalidates only that books old failures and clearing passwords leaves covers alone`() = runTest {
        val credentials = inMemoryCredentialDatabase()
        val index = inMemoryThumbnailIndex()
        val dir = File(context.cacheDir, "password-thumbs")
        fun cache() = DiskThumbnailCache(dir, index.thumbs(), now = { Instant.EPOCH })
        fun key(id: String) = ThumbKey(BookId(id), Instant.EPOCH, 100, null, 300, ReadingDirection.RightToLeft)
        val failed = key("locked")
        val page = failed.copy(coverEntry = 2, sizePx = 600)
        val ready = failed.copy(coverEntry = 0)
        val other = key("other")
        try {
            val thumbs = cache()
            // Rows written before passwords were supported have only the digest, not a book id.
            for (key in listOf(failed, page, other)) index.thumbs().upsert(ThumbRow(key.digest, 0, 0, true))
            thumbs.put(ready, byteArrayOf(1))
            val passwords = EncryptedBookPasswords(credentials.bookPasswords(), softwareCipher(), thumbs::clearFailures)
            passwords.put(failed.book, "right".toCharArray())
            val reopened = cache()
            assertEquals(ThumbLookup.Miss, reopened.get(failed))
            assertEquals(ThumbLookup.Miss, reopened.get(page))
            assertIs<ThumbLookup.Hit>(reopened.get(ready))
            assertEquals(ThumbLookup.Miss, reopened.get(other)) // legacy rows without an owner are rebuilt when requested
            reopened.markFailed(failed)
            assertEquals(ThumbLookup.Failed, reopened.get(failed))
            passwords.delete(failed.book)
            passwords.put(other.book, "other".toCharArray())
            reopened.markFailed(other)
            passwords.clear()
            assertEquals(ThumbLookup.Failed, reopened.get(other))
            assertEquals(ThumbLookup.Failed, reopened.get(failed))
            assertIs<ThumbLookup.Hit>(reopened.get(ready))
        } finally { credentials.close(); index.close(); dir.deleteRecursively() }
    }

    @Test fun `version 1 upgrades without losing SMB secrets and clearing books leaves SMB intact`() = runTest {
        val cipher = softwareCipher()
        val old = cipher.seal("server secret".encodeToByteArray(), "old-alias".encodeToByteArray())
        val file = context.getDatabasePath(CREDENTIAL_DATABASE).also { it.parentFile!!.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("CREATE TABLE credentials (alias TEXT NOT NULL PRIMARY KEY, iv BLOB NOT NULL, ciphertext BLOB NOT NULL)")
            db.execSQL("INSERT INTO credentials VALUES (?, ?, ?)", arrayOf("old-alias", old.iv, old.ciphertext))
            db.version = 1
        }
        val db = openCredentialDatabase(context)
        try {
            val smb = EncryptedCredentialStore(db.credentials(), cipher)
            val books = EncryptedBookPasswords(db.bookPasswords(), cipher)
            val id = BookId("first")
            assertNull(books.read(id))
            books.put(id, "book secret".toCharArray())
            books.put(BookId("second"), "another".toCharArray())
            assertEquals("book secret", books.read(id)!!.concatToString())
            assertFalse(db.bookPasswords().get(id.value)!!.ciphertext.contentEquals("book secret".encodeToByteArray()))
            assertEquals(2, books.clear())
            assertEquals(0, books.clear())
            assertNull(books.read(id))
            assertEquals("server secret", assertIs<StoredSecret.Found>(smb.read(CredentialRef("old-alias"))).secret.concatToString())
        } finally { db.close() }
    }

    @Test fun `book ids bind the ciphertext and cannot collide with SMB aliases`() = runTest {
        val db = inMemoryCredentialDatabase()
        try {
            val cipher = softwareCipher()
            val books = EncryptedBookPasswords(db.bookPasswords(), cipher)
            val id = BookId("same")
            books.put(id, "private".toCharArray())
            val row = db.bookPasswords().get(id.value)!!
            db.bookPasswords().put(BookPasswordRow("other", row.iv, row.ciphertext))
            assertNull(books.read(BookId("other")))
            db.credentials().upsert(CredentialRow("same", row.iv, row.ciphertext))
            assertEquals(StoredSecret.Unreadable, EncryptedCredentialStore(db.credentials(), cipher).read(CredentialRef("same")))
        } finally { db.close() }
    }
}
