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
 * here to a few megabytes of small WebPs.
 */
internal class Saves(private val store: ThumbnailStore, scope: CoroutineScope) {
    private class Save(val key: ThumbnailKey, val webp: ByteArray)

    private val pending = ConcurrentHashMap<ThumbnailKey, Save>()
    private val queue = Channel<Save>(MAX_QUEUED)

    /** Held from the look at [pending] to the end of the write, so [forget] never falls in between. */
    private val writing = Mutex()

    init {
        scope.launch {
            for (save in queue) save(save)
        }
    }

    fun pending(key: ThumbnailKey): ByteArray? = pending[key]?.webp

    /** Queues [webp] to be stored under [key], and gives it to whoever asks for [key] until then. */
    fun add(key: ThumbnailKey, webp: ByteArray) {
        val save = Save(key, webp)
        pending[key] = save
        if (queue.trySend(save).isFailure) pending.remove(key, save)
    }

    /** Forgets the thumbnail of [key], pending or stored, and its failure: a save queued before stores nothing. */
    suspend fun forget(key: ThumbnailKey) = writing.withLock {
        pending.remove(key)
        store.remove(key)
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
