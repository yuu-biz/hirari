package io.github.yuubiz.hirari.feature.library

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import io.github.yuubiz.hirari.core.data.CredentialStore
import io.github.yuubiz.hirari.core.data.StoredSecret
import io.github.yuubiz.hirari.core.model.CredentialRef
import io.github.yuubiz.hirari.core.model.LibraryLocation
import io.github.yuubiz.hirari.core.model.LocationId
import io.github.yuubiz.hirari.core.model.normalizePath
import io.github.yuubiz.hirari.core.source.smb.ConnectionLostException
import io.github.yuubiz.hirari.core.source.smb.PasswordUnreadableException
import io.github.yuubiz.hirari.core.source.smb.SmbAddress
import io.github.yuubiz.hirari.core.source.smb.SmbConnectException
import io.github.yuubiz.hirari.core.source.smb.SmbConnectFailure
import io.github.yuubiz.hirari.core.source.smb.SmbConnectResult
import io.github.yuubiz.hirari.core.source.smb.SmbConnectionState
import io.github.yuubiz.hirari.core.source.smb.SmbLogin
import io.github.yuubiz.hirari.core.source.smb.SmbRequestException
import io.github.yuubiz.hirari.core.source.smb.SmbServer
import io.github.yuubiz.hirari.core.source.smb.SmbShares
import io.github.yuubiz.hirari.core.source.smb.SmbTimeouts
import io.github.yuubiz.hirari.core.source.ByteSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import java.io.FileNotFoundException
import kotlin.coroutines.cancellation.CancellationException
import io.github.yuubiz.hirari.core.data.SmbServer as ServerForm

/** Why 接続を試す failed (docs/spec.md section 4.5), in the order the attempt meets them. */
enum class SmbTestFailure {
    /** `ACCESS_LOCAL_NETWORK` is not granted, so the system blocks the connection (section 7). */
    NoLocalNetwork,

    /** The stored password cannot be opened, as after a move to another device (section 7). */
    PasswordUnreadable,
    NameNotResolved,
    Unreachable,
    AuthenticationFailed,
    ShareNotFound,

    /** The share is there but the folder of the form is not. */
    FolderNotFound,
    ProtocolError,
}

sealed interface SmbTestResult {
    data object Connected : SmbTestResult

    data class Failed(val reason: SmbTestFailure) : SmbTestResult
}

/** The SMB places of the library (docs/spec.md section 4.5). Every call does its I/O off the main thread. */
interface SmbPlaces {
    /** The children of the folder at [path], counted from the place's own folder. */
    suspend fun list(place: LibraryLocation.SmbShare, path: String): Listing

    /**
     * The user's retry of docs/spec.md section 6.5: when every reconnect of the place's server failed, starts them
     * over from the first and waits until one connects or all fail again. Does nothing to a server that is not lost.
     */
    suspend fun reconnect(place: LibraryLocation.SmbShare)

    /**
     * Connects to the share of [server] and opens its folder. [password] is what the form holds, which this call
     * wipes; without one, [stored] is the password kept for the server, if any.
     */
    suspend fun test(server: ServerForm, password: CharArray?, stored: CredentialRef?): SmbTestResult

    /**
     * Opens the file at [path], counted from the place's folder, for random access.
     *
     * @throws java.io.IOException as [SmbServer.open] does, and when the system blocks the local network.
     */
    suspend fun open(place: LibraryLocation.SmbShare, path: String): ByteSource

    /** Drops the connection of the place [id], so the next request logs on with what is stored now. */
    suspend fun forget(id: LocationId)
}

/** Whether the system lets the app open connections in the local network (docs/spec.md section 7, Q14). */
fun interface LocalNetwork {
    fun granted(): Boolean
}

/** `ACCESS_LOCAL_NETWORK` exists from API 37; before it, a LAN connection needs no runtime permission. */
const val LOCAL_NETWORK_SDK = 37

class AndroidLocalNetwork(private val context: Context) : LocalNetwork {
    override fun granted(): Boolean = Build.VERSION.SDK_INT < LOCAL_NETWORK_SDK ||
        context.checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED
}

/**
 * How to log on as [user] with the password stored under [credential], the one way the library and the reader read
 * it (docs/spec.md sections 4.5 and 6.5). Guest without a user name. A user without a stored password logs on with
 * an empty one. The secret is wiped here, since [SmbLogin.Password] keeps its own copy.
 *
 * @throws PasswordUnreadableException when the password cannot be opened or its row is gone, as after a restore to
 *   another device, which leaves the passwords behind (section 7)
 */
suspend fun CredentialStore.smbLogin(user: String?, credential: CredentialRef?): SmbLogin {
    user ?: return SmbLogin.Guest
    credential ?: return SmbLogin.Password(user, CharArray(0))
    return when (val stored = read(credential)) {
        is StoredSecret.Found -> try {
            SmbLogin.Password(user, stored.secret)
        } finally {
            stored.secret.fill('\u0000')
        }
        StoredSecret.Missing, StoredSecret.Unreadable -> throw PasswordUnreadableException()
    }
}

/**
 * [SmbPlaces] over the process's [shares], which log on with [smbLogin]. [credentials] gives the stored password to
 * a connection test.
 */
class SmbjPlaces(
    private val shares: SmbShares,
    private val credentials: CredentialStore,
    private val localNetwork: LocalNetwork,
    private val scope: CoroutineScope,
    private val timeouts: SmbTimeouts = SmbTimeouts(),
) : SmbPlaces {
    override suspend fun list(place: LibraryLocation.SmbShare, path: String): Listing {
        if (!localNetwork.granted()) return Listing.Failed(ListFailure.NoLocalNetwork)
        return try {
            val server = shares.server(place)
            val entries = server.list(normalizePath("${place.basePath}/$path")).map {
                FolderEntry(it.name, documentId = null, directory = it.isDirectory, size = it.size, modified = it.modified)
            }
            Listing.Listed(folderDocumentId = null, entries)
        } catch (e: CancellationException) {
            throw e
        } catch (_: PasswordUnreadableException) {
            Listing.Failed(ListFailure.PasswordUnreadable)
        } catch (e: SmbConnectException) {
            Listing.Failed(
                when (e.failure) {
                    SmbConnectFailure.NameNotResolved, SmbConnectFailure.Unreachable -> ListFailure.Unreachable
                    SmbConnectFailure.AuthenticationFailed -> ListFailure.AuthenticationFailed
                    SmbConnectFailure.ShareNotFound -> ListFailure.NotFound
                    SmbConnectFailure.ProtocolError -> ListFailure.ReadError
                },
            )
        } catch (_: ConnectionLostException) {
            Listing.Failed(ListFailure.Unreachable)
        } catch (_: FileNotFoundException) {
            Listing.Failed(ListFailure.NotFound)
        } catch (_: Exception) {
            Listing.Failed(ListFailure.ReadError)
        }
    }

    override suspend fun reconnect(place: LibraryLocation.SmbShare) {
        if (!localNetwork.granted()) return
        val server = try {
            shares.server(place)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return
        }
        server.retry()
        server.state.first { it !is SmbConnectionState.Reconnecting }
    }

    override suspend fun test(server: ServerForm, password: CharArray?, stored: CredentialRef?): SmbTestResult {
        try {
            if (!localNetwork.granted()) return SmbTestResult.Failed(SmbTestFailure.NoLocalNetwork)
            val user = server.user
            val login = try {
                if (password != null && user != null) SmbLogin.Password(user, password) else credentials.smbLogin(user, stored)
            } catch (_: PasswordUnreadableException) {
                return SmbTestResult.Failed(SmbTestFailure.PasswordUnreadable)
            }
            val address = SmbAddress(server.host, server.share, server.port, server.domain)
            return SmbServer(address, login, scope, timeouts = timeouts).use { probe -> probe.connectTo(normalizePath(server.basePath)) }
        } finally {
            password?.fill('\u0000')
        }
    }

    override suspend fun open(place: LibraryLocation.SmbShare, path: String): ByteSource {
        if (!localNetwork.granted()) throw SmbConnectException(SmbConnectFailure.Unreachable, "local network not granted")
        val server = shares.server(place)
        return openOwned(Dispatchers.IO) { server.open(normalizePath("${place.basePath}/$path")) }
    }

    override suspend fun forget(id: LocationId) = shares.forget(id)

    private suspend fun SmbServer.connectTo(folder: String): SmbTestResult = when (val connected = check()) {
        is SmbConnectResult.Failed -> SmbTestResult.Failed(
            when (connected.failure) {
                SmbConnectFailure.NameNotResolved -> SmbTestFailure.NameNotResolved
                SmbConnectFailure.Unreachable -> SmbTestFailure.Unreachable
                SmbConnectFailure.AuthenticationFailed -> SmbTestFailure.AuthenticationFailed
                SmbConnectFailure.ShareNotFound -> SmbTestFailure.ShareNotFound
                SmbConnectFailure.ProtocolError -> SmbTestFailure.ProtocolError
            },
        )
        SmbConnectResult.Connected -> if (folder.isEmpty()) {
            SmbTestResult.Connected
        } else {
            try {
                list(folder)
                SmbTestResult.Connected
            } catch (e: CancellationException) {
                throw e
            } catch (_: FileNotFoundException) {
                SmbTestResult.Failed(SmbTestFailure.FolderNotFound)
            } catch (_: SmbRequestException) {
                SmbTestResult.Failed(SmbTestFailure.FolderNotFound)
            } catch (_: ConnectionLostException) {
                SmbTestResult.Failed(SmbTestFailure.Unreachable)
            }
        }
    }
}
