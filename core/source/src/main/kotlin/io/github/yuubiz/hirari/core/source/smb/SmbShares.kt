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
 * One [SmbServer] for each registered share, which every listing and book of the place shares (docs/spec.md
 * section 3.1). A place whose definition changed gets a new server. A password changed under the same reference
 * does not change the definition, so the caller [forget]s the place after such an edit.
 */
class SmbShares(
    private val scope: CoroutineScope,
    private val networkAvailable: Flow<Unit> = emptyFlow(),
    private val timeouts: SmbTimeouts = SmbTimeouts(),
) {
    private class Pooled(val definition: LibraryLocation.SmbShare, val server: SmbServer)

    private val lock = Mutex()
    private val servers = HashMap<LocationId, Pooled>()

    /**
     * The server of [place]. [login] is called only to make a new server, which keeps its own copy of the
     * password, so the caller may wipe its array once [login] returns.
     */
    suspend fun server(place: LibraryLocation.SmbShare, login: suspend () -> SmbLogin): SmbServer = lock.withLock {
        val definition = place.copy(displayName = "")
        servers[place.id]?.takeIf { it.definition == definition }?.let { return@withLock it.server }
        servers.remove(place.id)?.server?.close()
        SmbServer(place.address(), login(), scope, networkAvailable, timeouts).also { servers[place.id] = Pooled(definition, it) }
    }

    /** Closes the server of the place [id], if one is open. The next request connects anew. */
    suspend fun forget(id: LocationId) {
        lock.withLock { servers.remove(id) }?.server?.close()
    }
}
