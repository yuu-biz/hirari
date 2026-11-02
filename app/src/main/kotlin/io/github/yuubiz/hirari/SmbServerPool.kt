package io.github.yuubiz.hirari

import io.github.yuubiz.hirari.core.data.CredentialStore
import io.github.yuubiz.hirari.core.model.LibraryLocation
import io.github.yuubiz.hirari.core.source.smb.PasswordUnreadableException
import io.github.yuubiz.hirari.core.source.smb.SmbServer
import io.github.yuubiz.hirari.core.source.smb.SmbShares
import io.github.yuubiz.hirari.feature.library.smbLogin
import io.github.yuubiz.hirari.feature.reader.SmbServers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow

/**
 * The one [SmbShares] of the process, which the library's listings and the reader's books share (docs/spec.md
 * sections 3.1 and 6.5). The library edits and removes places through [shares], so the reader never keeps a server
 * the library forgot. A place whose password cannot be read has no server for the reader.
 */
internal class SmbServerPool(credentials: CredentialStore, scope: CoroutineScope, networkAvailable: Flow<Unit>) : SmbServers {
    val shares = SmbShares({ credentials.smbLogin(it.user, it.credential) }, scope, networkAvailable)

    override suspend fun serverOf(location: LibraryLocation.SmbShare): SmbServer? = try {
        shares.server(location)
    } catch (_: PasswordUnreadableException) {
        null
    }
}
