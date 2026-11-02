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
    private val shares = SmbShares(scope)
    private val place = LibraryLocation.SmbShare(LocationId(1), "NAS", "nas.local", 445, "books", "", null, null, null)
    private var logins = 0

    private suspend fun server(of: LibraryLocation.SmbShare = place) = shares.server(of) { logins++.let { SmbLogin.Guest } }

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
}
