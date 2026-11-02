package io.github.yuubiz.hirari.core.data

import android.content.Context
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import io.github.yuubiz.hirari.core.model.CredentialRef
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class CredentialStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val database = Room.databaseBuilder<CredentialDatabase>(context, CREDENTIAL_DATABASE).configure()
    private val dao = database.credentials()
    private val cipher = softwareCipher()
    private val store = EncryptedCredentialStore(dao, cipher)

    @After
    fun close() = database.close()

    private suspend fun readBack(ref: CredentialRef) = assertIs<StoredSecret.Found>(store.read(ref)).secret.concatToString()

    @Test
    fun `a secret reads back unchanged and the caller's array is left alone`() = runTest {
        val secret = "pässwörd 秘密 🔑".toCharArray()
        val ref = store.store(secret)
        assertEquals("pässwörd 秘密 🔑", secret.concatToString())
        assertEquals("pässwörd 秘密 🔑", readBack(ref))
        store.replace(ref, "next".toCharArray())
        assertEquals("next", readBack(ref))
    }

    @Test
    fun `references are random and say nothing about the secret`() = runTest {
        val first = store.store("same".toCharArray())
        val second = store.store("same".toCharArray())
        assertNotEquals(first, second)
        assertFalse("same" in first.alias)
        assertNotEquals(dao.get(first.alias)!!.iv.toList(), dao.get(second.alias)!!.iv.toList())
    }

    @Test
    fun `a deleted or unknown reference is missing`() = runTest {
        val ref = store.store("x".toCharArray())
        store.delete(ref)
        assertEquals(StoredSecret.Missing, store.read(ref))
        assertEquals(StoredSecret.Missing, store.read(CredentialRef("nothing")))
    }

    @Test
    fun `a value under another reference, a changed byte or another key cannot be opened`() = runTest {
        val first = store.store("first".toCharArray())
        val second = store.store("second".toCharArray())
        val row = dao.get(first.alias)!!
        dao.upsert(dao.get(second.alias)!!.let { CredentialRow(first.alias, it.iv, it.ciphertext) })
        assertEquals(StoredSecret.Unreadable, store.read(first))

        val tampered = row.ciphertext.copyOf().also { it[0] = (it[0] + 1).toByte() }
        dao.upsert(CredentialRow(first.alias, row.iv, tampered))
        assertEquals(StoredSecret.Unreadable, store.read(first))

        assertEquals(StoredSecret.Unreadable, EncryptedCredentialStore(dao, softwareCipher()).read(second))
    }

    @Test
    fun `adding a server leaves no password in the main database file`() = runTest {
        val password = "hirari-test-password-9d2e"
        val main = Room.databaseBuilder<HirariDatabase>(context, MAIN_DATABASE).configure()
        RoomLocationRepository(main, store).addSmbShare(SmbServer(displayName = "NAS", host = "nas", share = "s", user = "u"), password.toCharArray())
        main.close()
        val bytes = context.getDatabasePath(MAIN_DATABASE).parentFile!!.listFiles()!!
            .filter { it.name.startsWith(MAIN_DATABASE) }
            .flatMap { it.readBytes().toList() }
            .toByteArray()
        assertTrue(bytes.contains("nas".toByteArray()), "the server row is in the file")
        for (encoding in listOf(Charsets.UTF_8, Charsets.UTF_16LE, Charsets.UTF_16BE)) {
            assertFalse(bytes.contains(password.toByteArray(encoding)), "found the password as $encoding")
        }
    }

    @Test
    fun `the database file holds no plain secret`() = runTest {
        val password = "hirari-test-password-4f1c"
        store.store(password.toCharArray())
        database.close()
        val bytes = context.getDatabasePath(CREDENTIAL_DATABASE).parentFile!!.listFiles()!!
            .filter { it.name.startsWith(CREDENTIAL_DATABASE) }
            .flatMap { it.readBytes().toList() }
            .toByteArray()
        assertFalse(bytes.isEmpty())
        for (encoding in listOf(Charsets.UTF_8, Charsets.UTF_16LE, Charsets.UTF_16BE)) {
            assertFalse(bytes.contains(password.toByteArray(encoding)), "found the password as $encoding")
        }
    }
}

internal fun ByteArray.contains(part: ByteArray): Boolean =
    (0..size - part.size).any { start -> part.indices.all { this[start + it] == part[it] } }
