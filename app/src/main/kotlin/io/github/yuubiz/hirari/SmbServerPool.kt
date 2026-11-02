package io.github.yuubiz.hirari

import io.github.yuubiz.hirari.core.data.CredentialStore
import io.github.yuubiz.hirari.core.data.StoredSecret
import io.github.yuubiz.hirari.core.model.LibraryLocation
import io.github.yuubiz.hirari.core.model.LocationId
import io.github.yuubiz.hirari.core.source.smb.SmbAddress
import io.github.yuubiz.hirari.core.source.smb.SmbLogin
import io.github.yuubiz.hirari.core.source.smb.SmbServer
import io.github.yuubiz.hirari.feature.reader.SmbServers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One [SmbServer] per SMB place for the whole process (docs/spec.md sections 3.1 and 6.5). A place edited since its
 * server was made gets a new one, and the old one closes. The password is read from [credentials] only to log on.
 */
internal class SmbServerPool(
    private val credentials: CredentialStore,
    private val scope: CoroutineScope,
    private val networkAvailable: Flow<Unit>,
) : SmbServers {
    private val lock = Mutex()
    private val servers = HashMap<LocationId, Pair<LibraryLocation.SmbShare, SmbServer>>()

    override suspend fun serverOf(location: LibraryLocation.SmbShare): SmbServer? = lock.withLock {
        servers[location.id]?.let { (made, server) ->
            if (made == location) return@withLock server
            server.close()
            servers.remove(location.id)
        }
        val login = login(location) ?: return@withLock null
        val address = SmbAddress(location.host, location.share, location.port, location.domain)
        SmbServer(address, login, scope, networkAvailable).also { servers[location.id] = location to it }
    }

    private suspend fun login(location: LibraryLocation.SmbShare): SmbLogin? {
        val user = location.user ?: return SmbLogin.Guest
        val ref = location.credential ?: return SmbLogin.Password(user, CharArray(0))
        return when (val stored = credentials.read(ref)) {
            is StoredSecret.Found -> try {
                SmbLogin.Password(user, stored.secret)
            } finally {
                stored.secret.fill('\u0000')
            }
            StoredSecret.Missing, StoredSecret.Unreadable -> null
        }
    }
}
