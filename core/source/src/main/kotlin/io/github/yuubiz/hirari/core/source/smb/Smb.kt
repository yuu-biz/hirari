package io.github.yuubiz.hirari.core.source.smb

import java.io.IOException
import java.time.Instant

/** Where an SMB share is (docs/spec.md section 4.5). Holds no credentials, so it is safe to print. */
data class SmbAddress(
    val host: String,
    val share: String,
    val port: Int = 445,
    val domain: String? = null,
)

/** How to log on to the server. */
sealed interface SmbLogin {
    /** No user name and password: the server's guest account (docs/spec.md section 4.5). */
    data object Guest : SmbLogin

    /**
     * A user and password. Keeps its own copy of [password], which [SmbServer.close] overwrites, so the caller
     * may wipe its array right away. Prints neither the user nor the password (docs/spec.md section 6.6).
     */
    class Password(val user: String, password: CharArray) : SmbLogin {
        internal val password: CharArray = password.copyOf()

        internal fun wipe() = password.fill('\u0000')

        override fun toString(): String = "Password(***)"
    }
}

/** The connection to one server, as the reader overlay shows it (docs/spec.md sections 4.8 and 6.5). */
sealed interface SmbConnectionState {
    /** Not connected yet. The next request connects. */
    data object Idle : SmbConnectionState

    data object Connected : SmbConnectionState

    /** The connection broke and attempt [attempt] of [RECONNECT_DELAYS] is waiting or running. */
    data class Reconnecting(val attempt: Int) : SmbConnectionState

    /**
     * Every reconnect attempt failed, and the server waits for a network change or [SmbServer.retry]. A closed
     * server, as after its place was edited or removed, is lost for good.
     */
    data object Lost : SmbConnectionState
}

/** Why connecting failed, in the terms of "接続を試す" (docs/spec.md section 4.5). */
enum class SmbConnectFailure { NameNotResolved, Unreachable, AuthenticationFailed, ShareNotFound, ProtocolError }

sealed interface SmbConnectResult {
    data object Connected : SmbConnectResult

    data class Failed(val failure: SmbConnectFailure) : SmbConnectResult
}

/** A direct child of a folder in a share. [path] is relative to the share root and separated by `/`. */
data class SmbEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
    val modified: Instant,
)

/**
 * The connection to the server broke, timed out, or is being re-established (docs/spec.md section 6.5). The page
 * that was being read becomes `Failed(ConnectionLost, retryable = true)`. The message names no host, path or user.
 */
class ConnectionLostException(message: String) : IOException(message)

/** Connecting or logging on failed for [failure]. */
class SmbConnectException(val failure: SmbConnectFailure, detail: String) : IOException("SMB connect failed: $failure ($detail)")

/** The server refused the request on an open connection, for example because the file is gone. */
class SmbRequestException(val status: String) : IOException("SMB request failed: $status")
