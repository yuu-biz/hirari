package io.github.yuubiz.hirari.core.image.thumbnail

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicInteger

/** The best priority any request that waits for a flight has. It only rises. */
internal class Urgency(initial: ThumbPriority) {
    private val best = AtomicInteger(initial.ordinal)

    val rank: Int get() = best.get()

    fun raise(priority: ThumbPriority) {
        best.accumulateAndGet(priority.ordinal, ::minOf)
    }
}

/**
 * One piece of work per key, shared by everyone who asks for it while it runs. It runs in [scope], so a caller
 * that gives up does not stop it while another waits; it is cancelled when the last caller gives up.
 */
internal class Flights<K : Any, V>(private val scope: CoroutineScope) {
    private class Flight<V>(val urgency: Urgency) {
        lateinit var result: Deferred<V>
        var waiters = 0
    }

    private val lock = Any()
    private val running = HashMap<K, Flight<V>>()

    suspend fun join(key: K, priority: ThumbPriority, work: suspend (Urgency) -> V): V {
        val flight = synchronized(lock) {
            val flight = running[key]?.also { it.urgency.raise(priority) } ?: Flight<V>(Urgency(priority)).also { created ->
                created.result = scope.async(start = CoroutineStart.LAZY) {
                    try {
                        work(created.urgency)
                    } finally {
                        synchronized(lock) { if (running[key] === created) running.remove(key) }
                    }
                }
                running[key] = created
                created.result.start()
            }
            flight.also { it.waiters++ }
        }
        try {
            return flight.result.await()
        } finally {
            synchronized(lock) {
                if (--flight.waiters == 0) {
                    if (running[key] === flight) running.remove(key)
                    flight.result.cancel()
                }
            }
        }
    }
}

/**
 * At most [permits] blocks run at once. A free permit goes to the waiter with the best [Urgency], and to the one
 * that came first among equals.
 */
internal class PriorityGate(permits: Int) {
    private class Waiter(val urgency: Urgency, val order: Long, val continuation: CancellableContinuation<Unit>)

    private val lock = Any()
    private var free = permits
    private var arrivals = 0L
    private val waiting = ArrayList<Waiter>()

    suspend fun <T> withPermit(urgency: Urgency, block: suspend () -> T): T {
        acquire(urgency)
        try {
            return block()
        } finally {
            release()
        }
    }

    private suspend fun acquire(urgency: Urgency) = suspendCancellableCoroutine { continuation ->
        val granted = synchronized(lock) {
            if (free > 0) {
                free--
                true
            } else {
                waiting += Waiter(urgency, arrivals++, continuation)
                false
            }
        }
        // A permit handed to a continuation that is cancelled before it runs goes on to the next waiter.
        if (granted) {
            continuation.resume(Unit) { release() }
        } else {
            continuation.invokeOnCancellation { synchronized(lock) { waiting.removeAll { it.continuation === continuation } } }
        }
    }

    private fun release() {
        val next = synchronized(lock) {
            val next = waiting.minWithOrNull(compareBy<Waiter>({ it.urgency.rank }, { it.order }))
            if (next == null) free++ else waiting.remove(next)
            next
        }
        next?.continuation?.resume(Unit) { release() }
    }
}
