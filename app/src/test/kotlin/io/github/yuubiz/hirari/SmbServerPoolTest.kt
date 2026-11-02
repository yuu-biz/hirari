package io.github.yuubiz.hirari

import io.github.yuubiz.hirari.core.data.CredentialStore
import io.github.yuubiz.hirari.core.data.StoredSecret
import io.github.yuubiz.hirari.core.model.CredentialRef
import io.github.yuubiz.hirari.core.model.LibraryLocation
import io.github.yuubiz.hirari.core.model.LocationId
import io.github.yuubiz.hirari.core.source.smb.ConnectionLostException
import io.github.yuubiz.hirari.core.source.smb.SmbConnectionState
import io.github.yuubiz.hirari.feature.library.ListFailure
import io.github.yuubiz.hirari.feature.library.Listing
import io.github.yuubiz.hirari.feature.library.SmbjPlaces
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The library and the reader over one [SmbServerPool], without a server to connect to. */
class SmbServerPoolTest {
    private val scope = CoroutineScope(SupervisorJob())
    private val credentials = Passwords()
    private val pool = SmbServerPool(credentials, scope, emptyFlow())
    private val library = SmbjPlaces(pool.shares, credentials, { true }, scope)

    private fun nas(credential: CredentialRef?) =
        LibraryLocation.SmbShare(LocationId(1), "NAS", "nas.invalid", 445, "books", "漫画", null, "reader", credential)

    @After
    fun stop() = scope.cancel()

    @Test
    fun `the library and the reader share one server and read the password once`() = runBlocking {
        val nas = nas(credentials.store("first".toCharArray()))
        val reading = checkNotNull(pool.serverOf(nas))
        library.reconnect(nas)
        assertSame(reading, pool.serverOf(nas.copy(displayName = "書庫")))
        assertEquals(listOf("first"), credentials.handedOut)
        assertTrue("the read password is wiped after logon", credentials.wiped())
    }

    @Test
    fun `an edit the library forgets closes the reader's server and the next book reads the new password`() = runBlocking {
        val ref = credentials.store("first".toCharArray())
        val nas = nas(ref)
        val reading = checkNotNull(pool.serverOf(nas))

        credentials.replace(ref, "second".toCharArray())
        library.forget(nas.id)
        assertEquals(SmbConnectionState.Lost, reading.state.value)
        assertThrows(ConnectionLostException::class.java) { reading.open("第1巻.zip") }

        assertNotSame(reading, pool.serverOf(nas))
        assertEquals(listOf("first", "second"), credentials.handedOut)
        assertTrue(credentials.wiped())
    }

    @Test
    fun `a removed place closes the reader's server and leaves no password to log on with`() = runBlocking {
        val ref = credentials.store("first".toCharArray())
        val nas = nas(ref)
        val reading = checkNotNull(pool.serverOf(nas))

        credentials.delete(ref)
        library.forget(nas.id)
        assertEquals(SmbConnectionState.Lost, reading.state.value)
        assertNull(pool.serverOf(nas))
    }

    @Test
    fun `an unreadable password is a missing permission to the reader and to the library alike`() = runBlocking {
        val nas = nas(Passwords.UNREADABLE)
        assertNull(pool.serverOf(nas))
        assertEquals(Listing.Failed(ListFailure.PasswordUnreadable), library.list(nas, ""))
    }

    private class Passwords : CredentialStore {
        private val secrets = mutableMapOf<CredentialRef, CharArray>()
        private val handed = mutableListOf<CharArray>()
        private var next = 0

        val handedOut = mutableListOf<String>()

        fun wiped() = handed.all { secret -> secret.all { it == '\u0000' } }

        override suspend fun store(secret: CharArray) = CredentialRef("ref-${next++}").also { replace(it, secret) }

        override suspend fun replace(ref: CredentialRef, secret: CharArray) {
            secrets[ref] = secret.copyOf()
        }

        override suspend fun read(ref: CredentialRef): StoredSecret {
            if (ref == UNREADABLE) return StoredSecret.Unreadable
            val secret = secrets[ref]?.copyOf() ?: return StoredSecret.Missing
            handedOut += String(secret)
            handed += secret
            return StoredSecret.Found(secret)
        }

        override suspend fun delete(ref: CredentialRef) {
            secrets.remove(ref)
        }

        companion object {
            val UNREADABLE = CredentialRef("unreadable")
        }
    }
}
