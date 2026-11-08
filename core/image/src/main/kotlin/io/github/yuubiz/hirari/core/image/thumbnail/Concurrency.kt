package io.github.yuubiz.hirari.core.image.thumbnail

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.CopyOnWriteArrayList

/**
 * How soon a piece of work is wanted: the best of its own and of the urgencies it follows now, read anew at each
 * turn of a [PriorityGate]. Its own is its priority, and within a priority its distance from the middle of the
 * screen, which moves as the list scrolls. A flight follows each request for as long as the request waits for it,
 * and a folder lends the urgency of its flight to the book its search tries, so they rise with the folder and fall
 * back when the request that raised them leaves. A lower rank is sooner.
 */
internal class Urgency private constructor(private val own: () -> Long) {
    constructor(priority: ThumbPriority, distance: () -> Int = { 0 }) :
        this({ (priority.ordinal.toLong() shl Int.SIZE_BITS) + distance().coerceAtLeast(0) })

    private val followed = CopyOnWriteArrayList<Urgency>()

    val rank: Long get() = followed.fold(own()) { best, other -> minOf(best, other.rank) }

    fun follow(other: Urgency) {
        followed += other
    }

    fun unfollow(other: Urgency) {
        followed.remove(other)
    }

    companion object {
        /** An urgency with no priority of its own: as high as what it follows, and the lowest when it follows nothing. */
        fun borrowed() = Urgency { Long.MAX_VALUE }
    }
}

/**
 * One piece of work per key, shared by everyone who asks for it while it runs. It runs in [scope], so a caller
 * that gives up does not stop it while another waits; it is cancelled when the last caller gives up.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class Flights<K : Any, V>(private val scope: CoroutineScope) {
    /** Everyone waiting for one key, and the [work] that answers them, which [replace] can put other work in place of. */
    private class Flight<V>(val urgency: Urgency) {
        val result = CompletableDeferred<V>()
        lateinit var work: Deferred<V>
        var waiters = 0
    }

    private val lock = Any()
    private val running = HashMap<K, Flight<V>>()

    suspend fun join(key: K, urgency: Urgency, work: suspend (Urgency) -> V): V = board(key, urgency, replace = false, work)

    /**
     * [join], except that the work running for [key] is cancelled and [work] starts in its place once it has ended, so
     * nothing the replaced work does comes after. Everyone waiting for the replaced work gets what [work] comes to.
     */
    suspend fun replace(key: K, urgency: Urgency, work: suspend (Urgency) -> V): V = board(key, urgency, replace = true, work)

    private suspend fun board(key: K, urgency: Urgency, replace: Boolean, work: suspend (Urgency) -> V): V {
        val flight = synchronized(lock) {
            val current = running[key]
            val flight = current ?: Flight<V>(Urgency.borrowed()).also { running[key] = it }
            if (current == null) fly(key, flight, replaced = null, work) else if (replace) fly(key, flight, current.work, work)
            flight.urgency.follow(urgency)
            flight.waiters++
            flight
        }
        try {
            return flight.result.await()
        } finally {
            synchronized(lock) {
                flight.urgency.unfollow(urgency)
                if (--flight.waiters == 0) {
                    if (running[key] === flight) running.remove(key)
                    flight.work.cancel()
                }
            }
        }
    }

    /** Called with [lock] held. Only the last work of [flight] answers it. */
    private fun fly(key: K, flight: Flight<V>, replaced: Job?, work: suspend (Urgency) -> V) {
        val job = scope.async(start = CoroutineStart.LAZY) {
            replaced?.join()
            work(flight.urgency)
        }
        flight.work = job
        job.invokeOnCompletion { cause ->
            synchronized(lock) {
                if (flight.work !== job) return@invokeOnCompletion
                if (running[key] === flight) running.remove(key)
            }
            if (cause == null) flight.result.complete(job.getCompleted()) else flight.result.completeExceptionally(cause)
        }
        // Cancelled once replaced: work that ends inside cancel(), as on an immediate dispatcher, answers nobody.
        replaced?.cancel()
        job.start()
    }
}

/**
 * At most [permits] blocks run at once. A free permit goes to the waiter with the best [Urgency] at that moment, and
 * to the one that came first among equals.
 */
internal class PriorityGate(permits: Int) {
    private class Waiter(val urgency: Urgency, val order: Long, val continuation: CancellableContinuation<Unit>)

    private val lock = Any()
    private var limit = permits.also { require(it > 0) }
    private var running = 0
    private var arrivals = 0L
    private val waiting = ArrayList<Waiter>()

    fun setPermits(n: Int) {
        require(n > 0)
        val ready = synchronized(lock) {
            limit = n
            grantWaiting()
        }
        ready.forEach { it.continuation.resume(Unit) { release() } }
    }

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
            if (running < limit) {
                running++
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
        val ready = synchronized(lock) {
            running--
            grantWaiting()
        }
        ready.forEach { it.continuation.resume(Unit) { release() } }
    }

    private fun grantWaiting(): List<Waiter> = buildList {
        while (running < limit) {
            val next = waiting.minWithOrNull(compareBy<Waiter>({ it.urgency.rank }, { it.order }))
                ?: break
            waiting.remove(next)
            running++
            add(next)
        }
    }
}
