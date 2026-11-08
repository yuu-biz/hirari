package io.github.yuubiz.hirari.core.image.thumbnail

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException

/**
 * The thumbnails made and not in the store yet (docs/spec.md section 4.4). They are saved one at a time, all lanes
 * together, in the order they were [add]ed, by one coroutine of [scope]. Until its save ends, [pending] gives a
 * thumbnail to whoever asks, so nobody makes it again meanwhile. A save that fails records nothing, and so does one that
 * finds [MAX_QUEUED] saves waiting: the next request after it makes the thumbnail again. That bound keeps the bytes held
 * here to a few megabytes of small WebPs. A thumbnail whose make started before its key was last [forget]ten is not
 * added at all ([epoch]).
 */
internal class Saves(private val store: ThumbnailStore, scope: CoroutineScope) {
    private class Save(val key: ThumbnailKey, val webp: ByteArray)

    private val pending = ConcurrentHashMap<ThumbnailKey, Save>()
    private val queue = Channel<Save>(MAX_QUEUED)

    /** Held from the look at [pending] to the end of the write, so [forget] never falls in between. */
    private val writing = Mutex()

    /** How many times each key was forgotten. Guarded by itself. */
    private val epochs = HashMap<ThumbnailKey, Int>()

    init {
        scope.launch {
            try {
                for (save in queue) save(save)
            } finally {
                queue.close()
                pending.clear()
            }
        }
    }

    fun pending(key: ThumbnailKey): ByteArray? = pending[key]?.webp

    /** What a make of [key] reads before it starts and hands to [add]. */
    fun epoch(key: ThumbnailKey): Int = synchronized(epochs) { epochs[key] ?: 0 }

    /**
     * Queues [webp] to be stored under [key], and gives it to whoever asks for [key] until then, unless [key] was
     * forgotten since the make of [webp] read [epoch]: that make may have read what the book was before.
     */
    fun add(key: ThumbnailKey, webp: ByteArray, epoch: Int) {
        val save = Save(key, webp)
        synchronized(epochs) {
            if ((epochs[key] ?: 0) != epoch) return
            pending[key] = save
        }
        if (queue.trySend(save).isFailure) pending.remove(key, save)
    }

    /**
     * Forgets the thumbnail of [key], pending or stored, and its failure: a save queued before stores nothing, and a
     * make running now adds nothing.
     */
    suspend fun forget(key: ThumbnailKey) {
        synchronized(epochs) {
            epochs[key] = (epochs[key] ?: 0) + 1
            pending.remove(key)
        }
        writing.withLock { store.remove(key) }
    }

    private suspend fun save(save: Save) = writing.withLock {
        try {
            if (pending[save.key] === save) store.put(save.key, save.webp)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Nothing is recorded: the thumbnail stays on screen, and the next request makes it again.
        } finally {
            pending.remove(save.key, save)
        }
    }

    companion object {
        /** The most saves that wait at once. */
        const val MAX_QUEUED = 64
    }
}
