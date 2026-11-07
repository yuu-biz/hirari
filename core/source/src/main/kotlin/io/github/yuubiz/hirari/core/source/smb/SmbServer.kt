package io.github.yuubiz.hirari.core.source.smb

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.Closeable
import java.io.IOException
import kotlin.concurrent.thread
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** The waits before each reconnect attempt (docs/spec.md section 6.5). */
val RECONNECT_DELAYS: List<Duration> = listOf(500.milliseconds, 1.seconds, 2.seconds, 4.seconds)

/** Requests in flight to one server at most (docs/spec.md section 8.3). */
const val MAX_REQUESTS_PER_SERVER = 4

/** Of those, the background ones at most: the last permit is kept for the listing or the page the person waits for. */
const val MAX_BACKGROUND_REQUESTS_PER_SERVER = MAX_REQUESTS_PER_SERVER - 1

/**
 * One SMB server and share (docs/spec.md sections 3.1 and 6.5). Connects on the first request, shares the
 * connection among every listing and [SmbByteSource] of the share, and lets at most [MAX_REQUESTS_PER_SERVER]
 * requests run at once, of which at most [MAX_BACKGROUND_REQUESTS_PER_SERVER] are [SmbPurpose.Background].
 *
 * When a request finds the connection broken, the request fails with [ConnectionLostException] and the server
 * reconnects after each of [RECONNECT_DELAYS]. Requests made meanwhile fail at once with the same exception
 * instead of waiting. When every attempt fails, the state becomes [SmbConnectionState.Lost] until
 * [networkAvailable] emits, [retry] is called, or a new request starts the attempts over. Requests that start or
 * join this request-driven reconnect wait up to [queueTimeout] for it, then continue on the new connection.
 * A connection the transport saw end while no request used it is not broken in this sense: the next request
 * connects again in its place, and only a
 * failed connect starts the attempts. Open sources reopen their file
 * on the new connection by themselves. A closed server stays [SmbConnectionState.Lost] for good, and its requests
 * fail with [ConnectionLostException].
 *
 * Requests block, so call them off the main thread. [scope] runs the reconnect attempts; [close] cancels them.
 */
class SmbServer internal constructor(
    private val connector: ShareConnector,
    private val login: SmbLogin,
    scope: CoroutineScope,
    networkAvailable: Flow<Unit>,
    private val io: CoroutineContext,
    private val queueTimeout: Duration,
) : Closeable {
    constructor(
        address: SmbAddress,
        login: SmbLogin,
        scope: CoroutineScope,
        networkAvailable: Flow<Unit> = emptyFlow(),
        timeouts: SmbTimeouts = SmbTimeouts(),
    ) : this(SmbjConnector(address, login, timeouts), login, scope, networkAvailable, Dispatchers.IO, timeouts.request)

    internal class Link(val generation: Long, val connection: ShareConnection)

    private val job = SupervisorJob(scope.coroutineContext[Job])
    private val scope = CoroutineScope(scope.coroutineContext + job)
    private val gate = RequestGate(MAX_REQUESTS_PER_SERVER, MAX_BACKGROUND_REQUESTS_PER_SERVER)
    private val lock = Any()
    private val connecting = Any()

    // Guarded by lock.
    private var link: Link? = null
    private var redialing = false
    private var generation = 0L
    private var reconnecting: Job? = null
    private var requestDrivenReconnect = false
    private var closed = false

    private val mutableState = MutableStateFlow<SmbConnectionState>(SmbConnectionState.Idle)
    val state: StateFlow<SmbConnectionState> = mutableState.asStateFlow()

    init {
        this.scope.launch { networkAvailable.collect { retry() } }
    }

    /** Connects if needed and reports how it went, for "接続を試す" (docs/spec.md section 4.5). */
    suspend fun check(): SmbConnectResult = withContext(io) {
        try {
            request { }
            SmbConnectResult.Connected
        } catch (e: SmbConnectException) {
            SmbConnectResult.Failed(e.failure)
        } catch (e: ConnectionLostException) {
            SmbConnectResult.Failed(SmbConnectFailure.Unreachable)
        }
    }

    /**
     * The children of the folder at [path], relative to the share root and separated by `/`.
     *
     * @throws ConnectionLostException, SmbConnectException, java.io.FileNotFoundException, SmbRequestException
     */
    suspend fun list(path: String, purpose: SmbPurpose = SmbPurpose.Foreground): List<SmbEntry> =
        withContext(io) { request(purpose) { it.connection.list(path) } }

    suspend fun rename(path: String, destination: String) = withContext(io) {
        require(path.isNotBlank() && destination.isNotBlank())
        request(SmbPurpose.Foreground) { it.connection.rename(path, destination) }
    }

    class PartialDeleteException(val deletedPaths: Set<String>, cause: Exception) : IOException("partial delete", cause)

    /** Postorder deletion through the shared connection and foreground request gate. */
    suspend fun delete(path: String, directory: Boolean) = withContext(io) {
        require(path.isNotBlank())
        val pending = ArrayDeque<Triple<String, Boolean, Boolean>>()
        pending.add(Triple(path, directory, false))
        val deleted = linkedSetOf<String>()
        try {
            while (pending.isNotEmpty()) {
                currentCoroutineContext().ensureActive()
                val (next, folder, visited) = pending.removeLast()
                if (folder && !visited) {
                    val children = list(next)
                    pending.add(Triple(next, true, true))
                    children.forEach { pending.add(Triple(it.path, it.isDirectory, false)) }
                } else {
                    request(SmbPurpose.Foreground) { it.connection.delete(next, folder) }
                    deleted += next
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (deleted.isEmpty()) throw e
            throw PartialDeleteException(deleted, e)
        }
    }

    /**
     * Opens the file at [path] for random access. Blocks. Every read of the file is a request for [purpose].
     *
     * @throws ConnectionLostException, SmbConnectException, java.io.FileNotFoundException, SmbRequestException
     */
    fun open(path: String, purpose: SmbPurpose = SmbPurpose.Foreground): SmbByteSource =
        request(purpose) { SmbByteSource(this, path, it.connection.open(path), it.generation, purpose) }

    /** Starts the reconnect attempts over when they all failed. Does nothing otherwise. */
    fun retry() {
        synchronized(lock) {
            if (closed || mutableState.value != SmbConnectionState.Lost) return
            startReconnecting()
        }
    }

    /** Forgets the password and drops the connection on a background thread, so it never blocks the caller. */
    override fun close() {
        val last = synchronized(lock) {
            if (closed) return
            closed = true
            mutableState.value = SmbConnectionState.Lost
            link.also { link = null }
        }
        job.cancel()
        if (last != null) thread(isDaemon = true, name = "smb-close") { last.connection.closeNow() }
        (login as? SmbLogin.Password)?.wipe()
    }

    /**
     * Runs [block] on the current connection, holding one of the request permits for [purpose]. A
     * [ConnectionLostException] from [block] marks that connection broken.
     */
    internal fun <T> request(purpose: SmbPurpose = SmbPurpose.Foreground, block: (Link) -> T): T {
        val pending = synchronized(lock) {
            if (closed) throw ConnectionLostException("the server is closed")
            if (mutableState.value == SmbConnectionState.Lost) {
                startReconnecting()
                requestDrivenReconnect = true
            }
            reconnecting?.takeIf { requestDrivenReconnect && mutableState.value is SmbConnectionState.Reconnecting }
        }
        if (pending != null && !runBlocking { withTimeoutOrNull(queueTimeout) { pending.join(); true } ?: false }) {
            throw ConnectionLostException("SMB reconnect waited too long")
        }
        if (!runBlocking { gate.acquire(purpose, queueTimeout) }) {
            throw ConnectionLostException("SMB requests waited too long for their turn")
        }
        try {
            val current = currentLink()
            try {
                return block(current)
            } catch (e: ConnectionLostException) {
                broke(current)
                throw e
            }
        } finally {
            gate.release(purpose)
        }
    }

    /** Closes [file] in the background if its connection is still the current one. */
    internal fun closeLater(file: RemoteFile, generation: Long) {
        synchronized(lock) {
            if (link?.generation != generation) return
            scope.launch(io) { file.closeNow() }
        }
    }

    // A link the transport saw end, as when the system resets the sockets of an app in the background, is replaced
    // here (docs/spec.md section 6.5). The connect holds connecting, not lock, so close and the other requests'
    // bookkeeping never wait on it.
    private fun currentLink(): Link {
        fun existing(): Link? = synchronized(lock) {
            if (closed) throw ConnectionLostException("the server is closed")
            link?.takeUnless { it.connection.isOpen }?.let { ended ->
                link = null
                redialing = true
                scope.launch(io) { ended.connection.closeNow() }
            }
            if (link == null && !redialing && mutableState.value != SmbConnectionState.Idle) {
                throw ConnectionLostException("SMB is reconnecting")
            }
            link
        }
        existing()?.let { return it }
        synchronized(connecting) {
            existing()?.let { return it }
            val connection = try {
                connector.connect()
            } catch (e: IOException) {
                synchronized(lock) {
                    if (!redialing) throw e
                    redialing = false
                    if (!closed) startReconnecting()
                }
                throw ConnectionLostException("SMB could not connect again: ${e.javaClass.simpleName}")
            }
            synchronized(lock) {
                if (closed) {
                    connection.closeNow()
                    throw ConnectionLostException("the server is closed")
                }
                redialing = false
                return Link(++generation, connection).also {
                    link = it
                    mutableState.value = SmbConnectionState.Connected
                }
            }
        }
    }

    private fun broke(broken: Link) {
        synchronized(lock) {
            if (closed || link !== broken) return
            link = null
            scope.launch(io) { broken.connection.closeNow() }
            startReconnecting()
        }
    }

    // Called with lock held.
    private fun startReconnecting() {
        requestDrivenReconnect = false
        mutableState.value = SmbConnectionState.Reconnecting(1)
        reconnecting?.cancel()
        reconnecting = scope.launch {
            for ((index, wait) in RECONNECT_DELAYS.withIndex()) {
                synchronized(lock) { if (!closed) mutableState.value = SmbConnectionState.Reconnecting(index + 1) }
                delay(wait)
                var made: ShareConnection? = null
                val connection = try {
                    withContext(io) { connector.connect().also { made = it } }
                } catch (e: CancellationException) {
                    // withContext drops a connection made just before the cancel; it is ours to close.
                    made?.closeNow()
                    throw e
                } catch (e: IOException) {
                    continue
                }
                synchronized(lock) {
                    if (closed) {
                        connection.closeNow()
                    } else {
                        link = Link(++generation, connection)
                        mutableState.value = SmbConnectionState.Connected
                    }
                }
                return@launch
            }
            synchronized(lock) { if (!closed) mutableState.value = SmbConnectionState.Lost }
        }
    }
}
