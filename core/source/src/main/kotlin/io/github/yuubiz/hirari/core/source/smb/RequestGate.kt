package io.github.yuubiz.hirari.core.source.smb

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration

/**
 * Who a request to an SMB server is for (docs/spec.md section 8.3). [Foreground] is what the person is waiting for:
 * the listing of the folder on screen, the pages of the book being read, the text being read, the connection test.
 * [Background] is work that can wait: covers and the lookups behind them, and 端末に保存.
 */
enum class SmbPurpose { Foreground, Background }

/**
 * The permits of one server (docs/spec.md section 8.3): [permits] requests at once, of which at most
 * [backgroundPermits] are [SmbPurpose.Background]. The rest are kept for [SmbPurpose.Foreground], so a listing or a
 * page never waits for background work to finish. A freed permit goes to the oldest foreground request first, then
 * to the oldest background one if background may hold another.
 */
internal class RequestGate(permits: Int, private val backgroundPermits: Int) {
    init {
        require(backgroundPermits in 1 until permits) { "foreground needs a permit of its own" }
    }

    private class Waiter(val purpose: SmbPurpose) {
        val granted = CompletableDeferred<Unit>()
    }

    private val lock = Any()

    // Guarded by lock.
    private var free = permits
    private var backgroundHeld = 0
    private val foregroundQueue = ArrayDeque<Waiter>()
    private val backgroundQueue = ArrayDeque<Waiter>()

    /** Takes a permit for [purpose], waiting at most [timeout] for its turn. Returns false when none came. */
    suspend fun acquire(purpose: SmbPurpose, timeout: Duration): Boolean {
        val waiter = synchronized(lock) {
            val queue = queueOf(purpose)
            if (queue.isEmpty() && mayGrant(purpose)) {
                take(purpose)
                return true
            }
            Waiter(purpose).also(queue::addLast)
        }
        try {
            withTimeoutOrNull(timeout) { waiter.granted.await() }
        } catch (e: CancellationException) {
            if (!leaveQueue(waiter)) release(purpose)
            throw e
        }
        // A waiter a release took out of the queue holds its permit, even if the timeout fired at the same moment.
        return !leaveQueue(waiter)
    }

    fun release(purpose: SmbPurpose) {
        val granted = ArrayList<Waiter>()
        synchronized(lock) {
            free++
            if (purpose == SmbPurpose.Background) backgroundHeld--
            while (true) {
                val next = foregroundQueue.firstOrNull()?.takeIf { mayGrant(it.purpose) }
                    ?: backgroundQueue.firstOrNull()?.takeIf { mayGrant(it.purpose) }
                    ?: break
                queueOf(next.purpose).removeFirst()
                take(next.purpose)
                granted += next
            }
        }
        granted.forEach { it.granted.complete(Unit) }
    }

    private fun queueOf(purpose: SmbPurpose) = if (purpose == SmbPurpose.Foreground) foregroundQueue else backgroundQueue

    private fun mayGrant(purpose: SmbPurpose) = free > 0 && (purpose == SmbPurpose.Foreground || backgroundHeld < backgroundPermits)

    private fun take(purpose: SmbPurpose) {
        free--
        if (purpose == SmbPurpose.Background) backgroundHeld++
    }

    private fun leaveQueue(waiter: Waiter): Boolean = synchronized(lock) { queueOf(waiter.purpose).remove(waiter) }
}
