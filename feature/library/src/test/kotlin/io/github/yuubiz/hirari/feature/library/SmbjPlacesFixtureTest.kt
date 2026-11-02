package io.github.yuubiz.hirari.feature.library

import io.github.yuubiz.hirari.core.data.CredentialStore
import io.github.yuubiz.hirari.core.data.StoredSecret
import io.github.yuubiz.hirari.core.model.CredentialRef
import io.github.yuubiz.hirari.core.model.LibraryLocation
import io.github.yuubiz.hirari.core.model.LocationId
import io.github.yuubiz.hirari.core.source.smb.SmbShares
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import io.github.yuubiz.hirari.core.data.SmbServer as ServerForm

/**
 * [SmbjPlaces] against the Samba container of tools/smb-fixture. Runs only with the `-Dhirari.smb.*` options that
 * `tools/smb-fixture/fixture.sh props` prints, and is skipped otherwise.
 */
class SmbjPlacesFixtureTest {
    private val host = System.getProperty("hirari.smb.host")
    private val port = System.getProperty("hirari.smb.port")?.toInt() ?: 445
    private val scope = CoroutineScope(SupervisorJob())
    private val credentials = MemoryCredentials()
    private var allowed = true
    private val places = SmbjPlaces(SmbShares(scope), credentials, { allowed }, scope)

    @Before
    fun fixture() = assumeTrue("SMB fixture not configured (tools/smb-fixture/fixture.sh props)", host != null)

    @After
    fun stop() = scope.cancel()

    private fun place(share: String, folder: String = "", user: String? = null, credential: CredentialRef? = null) =
        LibraryLocation.SmbShare(LocationId(1), "NAS", host!!, port, share, folder, null, user, credential)

    private fun form(share: String = "books", folder: String = "", user: String? = USER) = ServerForm("NAS", host!!, port, share, folder, null, user)

    private fun Listing.names() = assertIs<Listing.Listed>(this).entries.map { it.name }

    @Test
    fun `a guest lists the public share and a user the folder of a password share`() = runBlocking {
        assertTrue("big.bin" in places.list(place("public"), "").names())
        val ref = credentials.store(PASSWORD.toCharArray())
        assertEquals(listOf("第1巻.bin"), places.list(place("books", folder = "漫画", user = USER, credential = ref), "").names())
        assertEquals(listOf("第1巻.bin"), places.list(place("books", user = USER, credential = ref), "漫画").names())
    }

    @Test
    fun `listing fails with a reason the file list can show`() = runBlocking {
        val wrong = credentials.store("not-the-password".toCharArray())
        assertEquals(Listing.Failed(ListFailure.AuthenticationFailed), places.list(place("books", user = USER, credential = wrong), ""))
        assertEquals(Listing.Failed(ListFailure.PasswordUnreadable), places.list(place("books", user = USER, credential = MemoryCredentials.UNREADABLE), ""))
        assertEquals(Listing.Failed(ListFailure.NotFound), places.list(place("public"), "なし"))
        allowed = false
        assertEquals(Listing.Failed(ListFailure.NoLocalNetwork), places.list(place("public"), ""))
    }

    @Test
    fun `a connection test tells each failure of the form apart`() = runBlocking {
        assertEquals(SmbTestResult.Connected, places.test(form(folder = "漫画"), PASSWORD.toCharArray(), null))
        assertEquals(SmbTestResult.Connected, places.test(form(), null, credentials.store(PASSWORD.toCharArray())))
        assertEquals(SmbTestResult.Connected, places.test(form("public", user = null), null, null))
        assertEquals(SmbTestResult.Failed(SmbTestFailure.AuthenticationFailed), places.test(form(), "wrong".toCharArray(), null))
        assertEquals(SmbTestResult.Failed(SmbTestFailure.ShareNotFound), places.test(form("nothing"), PASSWORD.toCharArray(), null))
        assertEquals(SmbTestResult.Failed(SmbTestFailure.FolderNotFound), places.test(form(folder = "なし"), PASSWORD.toCharArray(), null))
        assertEquals(SmbTestResult.Failed(SmbTestFailure.PasswordUnreadable), places.test(form(), null, MemoryCredentials.UNREADABLE))
    }

    @Test
    fun `a forgotten place logs on again with the password stored now`() = runBlocking {
        val ref = credentials.store("not-the-password".toCharArray())
        val nas = place("books", user = USER, credential = ref)
        assertEquals(Listing.Failed(ListFailure.AuthenticationFailed), places.list(nas, ""))
        credentials.replace(ref, PASSWORD.toCharArray())
        places.forget(nas.id)
        assertTrue("big.bin" in places.list(nas, "").names())
    }

    private class MemoryCredentials : CredentialStore {
        private val secrets = mutableMapOf<CredentialRef, CharArray>()
        private var next = 0

        override suspend fun store(secret: CharArray) = CredentialRef("ref-${next++}").also { replace(it, secret) }

        override suspend fun replace(ref: CredentialRef, secret: CharArray) {
            secrets[ref] = secret.copyOf()
        }

        override suspend fun read(ref: CredentialRef): StoredSecret =
            if (ref == UNREADABLE) StoredSecret.Unreadable else secrets[ref]?.let { StoredSecret.Found(it.copyOf()) } ?: StoredSecret.Missing

        override suspend fun delete(ref: CredentialRef) {
            secrets.remove(ref)
        }

        companion object {
            val UNREADABLE = CredentialRef("unreadable")
        }
    }

    private companion object {
        const val USER = "bookworm7"
        const val PASSWORD = "hirari-fixture-Pw7q"
    }
}
