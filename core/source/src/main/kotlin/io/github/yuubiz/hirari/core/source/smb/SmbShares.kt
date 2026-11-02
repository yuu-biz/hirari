package io.github.yuubiz.hirari.core.source.smb

import io.github.yuubiz.hirari.core.model.LibraryLocation
import io.github.yuubiz.hirari.core.model.LocationId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Where the share of [this] place is. */
fun LibraryLocation.SmbShare.address(): SmbAddress = SmbAddress(host, share, port, domain)

/**
 * One [SmbServer] for each registered share, which every listing and book of the place shares, in the library and
 * the reader alike (docs/spec.md sections 3.1 and 6.5). A place whose definition changed gets a new server. A
 * password changed under the same reference does not change the definition, so the caller [forget]s the place after
 * such an edit.
 *
 * [login] reads how to log on to a place. It is called only to make a new server, which keeps its own copy of the
 * password, so [login] may wipe what it read once it returns. It throws [PasswordUnreadableException] when the stored
 * password cannot be opened.
 */
class SmbShares(
    private val login: suspend (LibraryLocation.SmbShare) -> SmbLogin,
    private val scope: CoroutineScope,
    private val networkAvailable: Flow<Unit> = emptyFlow(),
    private val timeouts: SmbTimeouts = SmbTimeouts(),
) {
    private class Pooled(val definition: LibraryLocation.SmbShare, val server: SmbServer)

    private val lock = Mutex()
    private val servers = HashMap<LocationId, Pooled>()

    /**
     * The server of [place].
     *
     * @throws PasswordUnreadableException
     */
    suspend fun server(place: LibraryLocation.SmbShare): SmbServer = lock.withLock {
        val definition = place.copy(displayName = "")
        servers[place.id]?.takeIf { it.definition == definition }?.let { return@withLock it.server }
        servers.remove(place.id)?.server?.close()
        SmbServer(place.address(), login(place), scope, networkAvailable, timeouts).also { servers[place.id] = Pooled(definition, it) }
    }

    /** Closes the server of the place [id], if one is open. The next request logs on with what is stored now. */
    suspend fun forget(id: LocationId) {
        lock.withLock { servers.remove(id) }?.server?.close()
    }
}
