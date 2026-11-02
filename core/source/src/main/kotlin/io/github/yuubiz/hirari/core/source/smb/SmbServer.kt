package io.github.yuubiz.hirari.core.source.smb

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
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** The waits before each reconnect attempt (docs/spec.md section 6.5). */
val RECONNECT_DELAYS: List<Duration> = listOf(500.milliseconds, 1.seconds, 2.seconds, 4.seconds)

/** Requests in flight to one server at most (docs/spec.md section 8.3). */
const val MAX_REQUESTS_PER_SERVER = 4

/**
 * One SMB server and share (docs/spec.md sections 3.1 and 6.5). Connects on the first request, shares the
 * connection among every listing and [SmbByteSource] of the share, and lets at most [MAX_REQUESTS_PER_SERVER]
 * requests run at once.
 *
 * When a request finds the connection broken, the request fails with [ConnectionLostException] and the server
 * reconnects after each of [RECONNECT_DELAYS]. Requests made meanwhile fail at once with the same exception
 * instead of waiting. When every attempt fails, the state becomes [SmbConnectionState.Lost] until
 * [networkAvailable] emits or [retry] is called, which starts the attempts over. Open sources reopen their file
 * on the new connection by themselves.
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
    private val permits = Semaphore(MAX_REQUESTS_PER_SERVER, true)
    private val lock = Any()
    private val connecting = Any()

    // Guarded by lock.
    private var link: Link? = null
    private var generation = 0L
    private var reconnecting: Job? = null
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
    suspend fun list(path: String): List<SmbEntry> = withContext(io) { request { it.connection.list(path) } }

    /**
     * Opens the file at [path] for random access. Blocks.
     *
     * @throws ConnectionLostException, SmbConnectException, java.io.FileNotFoundException, SmbRequestException
     */
    fun open(path: String): SmbByteSource = request { SmbByteSource(this, path, it.connection.open(path), it.generation) }

    /** Starts the reconnect attempts over when they all failed. Does nothing otherwise. */
    fun retry() {
        synchronized(lock) {
            if (closed || mutableState.value != SmbConnectionState.Lost) return
            startReconnecting()
        }
    }

    /** Closes the connection without waiting on the server and forgets the password. */
    override fun close() {
        val last = synchronized(lock) {
            if (closed) return
            closed = true
            mutableState.value = SmbConnectionState.Idle
            link.also { link = null }
        }
        job.cancel()
        last?.connection?.closeNow()
        (login as? SmbLogin.Password)?.wipe()
    }

    /**
     * Runs [block] on the current connection, holding one of the request permits. A [ConnectionLostException] from
     * [block] marks that connection broken.
     */
    internal fun <T> request(block: (Link) -> T): T {
        if (!permits.tryAcquire(queueTimeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)) {
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
            permits.release()
        }
    }

    /** Closes [file] in the background if its connection is still the current one. */
    internal fun closeLater(file: RemoteFile, generation: Long) {
        synchronized(lock) {
            if (link?.generation != generation) return
            scope.launch(io) { file.closeNow() }
        }
    }

    // The first connect holds connecting, not lock, so close and the other requests' bookkeeping never wait on it.
    private fun currentLink(): Link {
        fun existing(): Link? = synchronized(lock) {
            check(!closed) { "the server is closed" }
            if (link == null && mutableState.value != SmbConnectionState.Idle) throw ConnectionLostException("SMB is reconnecting")
            link
        }
        existing()?.let { return it }
        synchronized(connecting) {
            existing()?.let { return it }
            val connection = connector.connect()
            synchronized(lock) {
                if (closed) {
                    connection.closeNow()
                    throw ConnectionLostException("the server is closed")
                }
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
        mutableState.value = SmbConnectionState.Reconnecting(1)
        reconnecting?.cancel()
        reconnecting = scope.launch {
            for ((index, wait) in RECONNECT_DELAYS.withIndex()) {
                mutableState.value = SmbConnectionState.Reconnecting(index + 1)
                delay(wait)
                val connection = try {
                    withContext(io) { connector.connect() }
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
            mutableState.value = SmbConnectionState.Lost
        }
    }
}
