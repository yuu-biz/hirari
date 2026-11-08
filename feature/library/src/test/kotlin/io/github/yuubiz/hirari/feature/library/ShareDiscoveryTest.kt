package io.github.yuubiz.hirari.feature.library

import io.github.yuubiz.hirari.core.data.CredentialStore
import io.github.yuubiz.hirari.core.data.StoredSecret
import io.github.yuubiz.hirari.core.model.CredentialRef
import io.github.yuubiz.hirari.core.source.smb.DiscoveredShare
import io.github.yuubiz.hirari.core.source.smb.ServerFinder
import io.github.yuubiz.hirari.core.source.smb.ServerSearch
import io.github.yuubiz.hirari.core.source.smb.ShareDiscoveryFailure
import io.github.yuubiz.hirari.core.source.smb.ShareDiscoveryResult
import io.github.yuubiz.hirari.core.source.smb.SmbLogin
import io.github.yuubiz.hirari.core.source.smb.SmbShareLister
import io.github.yuubiz.hirari.core.source.smb.SmbShares
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import io.github.yuubiz.hirari.core.data.SmbServer as ServerForm

class ShareDiscoveryTest {
    private val credentials = object : CredentialStore {
        override suspend fun store(secret: CharArray): CredentialRef = error("discovery must not save")
        override suspend fun replace(ref: CredentialRef, secret: CharArray) = error("discovery must not save")
        override suspend fun delete(ref: CredentialRef) = error("discovery must not save")
        override suspend fun read(ref: CredentialRef): StoredSecret = StoredSecret.Unreadable
    }
    private val form = ServerForm("NAS", "nas", 14455, "unchanged", "folder", "domain", "reader")

    @Test
    fun `permission and unreadable stored password fail before calling the lister`() = runTest {
        var allowed = false
        val places = SmbjPlaces(SmbShares({ error("must not use place pool") }, backgroundScope), credentials, { allowed }, backgroundScope,
            serverFinder = NoServers,
            shareLister = SmbShareLister { _, _ -> error("must not connect") })
        val password = "secret".toCharArray()
        assertEquals(ShareDiscoveryResult.Failed(ShareDiscoveryFailure.NoLocalNetwork), places.discover(form, password, null))
        assertTrue(password.all { it == '\u0000' })
        allowed = true
        assertEquals(ShareDiscoveryResult.Failed(ShareDiscoveryFailure.PasswordUnreadable), places.discover(form, null, CredentialRef("missing")))
    }

    @Test
    fun `server search needs the local network permission before it looks at any network`() = runTest {
        var allowed = false
        var looked = 0
        val finder = ServerFinder({ looked++; emptyList() }, { emptyFlow() }, { emptyFlow() }, io = StandardTestDispatcher(testScheduler))
        val places = SmbjPlaces(SmbShares({ error("must not use place pool") }, backgroundScope), credentials, { allowed }, backgroundScope, serverFinder = finder)
        assertEquals(listOf<ServerSearch>(ServerSearch.NoLocalNetwork), places.servers().toList())
        assertEquals(0, looked)
        allowed = true
        assertEquals(listOf<ServerSearch>(ServerSearch.NoNetwork), places.servers().toList())
        assertEquals(1, looked)
    }

    @Test
    fun `fake lister failures reach the form unchanged and guest ignores a typed password`() = runTest {
        var calls = 0
        var expected: ShareDiscoveryResult = ShareDiscoveryResult.Listed(listOf(DiscoveredShare("books", 0)))
        val places = SmbjPlaces(SmbShares({ error("must not use place pool") }, backgroundScope), credentials, { true }, backgroundScope,
            serverFinder = NoServers,
            shareLister = SmbShareLister { address, login ->
                calls++
                assertEquals("nas", address.host)
                assertEquals(14455, address.port)
                assertEquals("domain", address.domain)
                assertEquals("", address.share)
                assertIs<SmbLogin.Guest>(login)
                expected
            })
        for (failure in ShareDiscoveryFailure.entries) {
            expected = ShareDiscoveryResult.Failed(failure)
            val password = "secret".toCharArray()
            assertEquals(expected, places.discover(form.copy(user = null), password, null))
            assertTrue(password.all { it == '\u0000' })
        }
        assertEquals(ShareDiscoveryFailure.entries.size, calls)
    }

    @Test
    fun `each discovery failure has its own explanation`() {
        assertEquals(R.string.smb_discovery_unreachable, ShareDiscoveryFailure.Unreachable.discoveryMessage())
        assertEquals(R.string.smb_discovery_auth, ShareDiscoveryFailure.AuthenticationFailed.discoveryMessage())
        assertEquals(R.string.smb_discovery_permission, ShareDiscoveryFailure.NoLocalNetwork.discoveryMessage())
        assertEquals(R.string.smb_discovery_unsupported, ShareDiscoveryFailure.Unsupported.discoveryMessage())
        assertEquals(R.string.smb_discovery_empty, ShareDiscoveryFailure.Empty.discoveryMessage())
    }
}
