package io.github.yuubiz.hirari.core.source.smb

import io.github.yuubiz.hirari.core.model.LibraryLocation
import io.github.yuubiz.hirari.core.model.LocationId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class SmbSharesTest {
    private val scope = CoroutineScope(SupervisorJob())
    private var logins = 0
    private val shares = SmbShares({ logins++.let { SmbLogin.Guest } }, scope)
    private val place = LibraryLocation.SmbShare(LocationId(1), "NAS", "nas.local", 445, "books", "", null, null, null)

    private suspend fun server(of: LibraryLocation.SmbShare = place) = shares.server(of)

    @After
    fun stop() = scope.cancel()

    @Test
    fun `a place keeps one server, a renamed one too, and logs on once`() = runBlocking {
        val first = server()
        assertSame(first, server())
        assertSame(first, server(place.copy(displayName = "書庫")))
        assertEquals(1, logins)
    }

    @Test
    fun `a changed definition or a forgotten place gets a new server and closes the old one`() = runBlocking {
        val first = server()
        val moved = server(place.copy(host = "nas2.local"))
        assertNotSame(first, moved)
        assertFailsWith<ConnectionLostException> { first.list("") }
        assertEquals(SmbConnectionState.Lost, first.state.value)

        shares.forget(place.id)
        assertFailsWith<ConnectionLostException> { moved.list("") }
        assertNotSame(moved, server(place.copy(host = "nas2.local")))
        assertEquals(3, logins)
    }

    @Test
    fun `an unreadable password makes no server and the next request reads it again`() = runBlocking {
        var readable = false
        val shares = SmbShares({ if (readable) SmbLogin.Guest else throw PasswordUnreadableException() }, scope)
        assertFailsWith<PasswordUnreadableException> { shares.server(place) }
        readable = true
        assertEquals(SmbConnectionState.Idle, shares.server(place).state.value)
    }
}
