package io.github.yuubiz.hirari.core.source.smb

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Keeps the Wi-Fi radio passing multicast packets between [acquire] and [release]; either may be called twice. */
interface MulticastHold {
    fun acquire()

    fun release()
}

/** Runs this flow with [hold] acquired on collection and released when it completes, fails or is cancelled. */
internal fun <T> Flow<T>.whileHolding(hold: MulticastHold): Flow<T> {
    val source = this
    return flow {
        hold.acquire()
        try {
            emitAll(source)
        } finally {
            hold.release()
        }
    }
}

/** How one resolve ended. [Busy] is the system's refusal of a resolve while another is under way. */
internal sealed interface Resolution {
    data class Found(val service: DnsSdService) : Resolution

    data object Failed : Resolution

    data object Busy : Resolution
}

/**
 * Resolves one at a time. The slot is taken before a resolve starts and given back when the system answers, not when
 * the caller stops waiting, so a resolve left running by a cancelled search finishes before the next one starts. A
 * resolve the system never answers frees the slot after [limit]. A [Resolution.Busy] answer is tried again up to
 * [BUSY_RETRIES] times, [BUSY_RETRY_DELAY] apart, and then counts as a failure.
 */
internal class SerialResolver<K>(
    private val limit: Duration = RESOLVE_LIMIT,
    private val watchdog: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val start: (K, (Resolution) -> Unit) -> Unit,
) {
    private val slot = Mutex()

    suspend fun resolve(key: K): DnsSdService? {
        repeat(BUSY_RETRIES + 1) { attempt ->
            if (attempt > 0) delay(BUSY_RETRY_DELAY)
            when (val resolution = attemptOnce(key)) {
                is Resolution.Found -> return resolution.service
                Resolution.Failed -> return null
                Resolution.Busy -> Unit
            }
        }
        return null
    }

    private suspend fun attemptOnce(key: K): Resolution {
        slot.lock()
        val answer = CompletableDeferred<Resolution>()
        val finished = AtomicBoolean(false)
        var timer: Job? = null
        fun finish(resolution: Resolution) {
            if (!finished.compareAndSet(false, true)) return
            answer.complete(resolution)
            timer?.cancel()
            slot.unlock()
        }
        timer = watchdog.launch {
            delay(limit)
            finish(Resolution.Failed)
        }
        try {
            start(key, ::finish)
        } catch (_: Exception) {
            finish(Resolution.Failed)
        }
        return answer.await()
    }
}

internal val RESOLVE_LIMIT = 10.seconds
internal const val BUSY_RETRIES = 3
internal val BUSY_RETRY_DELAY = 500.milliseconds
