package io.github.yuubiz.hirari.feature.reader.prefetch

import io.github.yuubiz.hirari.core.image.Fill
import io.github.yuubiz.hirari.core.image.PageStash
import io.github.yuubiz.hirari.core.model.Turn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** The pages the SMB prefetch layer reads ahead each way (docs/spec.md section 5.3). */
const val DISK_PREFETCH_PAGES = 50

/**
 * The pages to read onto disk around the [shown] pages of a book of [pageCount] pages, nearest first: the
 * [depth] pages toward [travel], then the [depth] pages the other way. The shown pages are read by the view.
 */
fun diskOrder(shown: IntRange, pageCount: Int, travel: Turn, depth: Int = DISK_PREFETCH_PAGES): List<Int> {
    val ahead = (shown.last + 1..minOf(pageCount - 1, shown.last + depth)).toList()
    val behind = (shown.first - 1 downTo maxOf(0, shown.first - depth)).toList()
    return if (travel == Turn.Forward) ahead + behind else behind + ahead
}

/**
 * The SMB prefetch layer of one book (docs/spec.md section 5.3): reads the pages of [diskOrder] onto disk through
 * [stash], one at a time, so a page the view wants waits behind one entry at most. Moving to other pages starts over
 * from there. A page that runs out of memory or loses the connection stops the layer until the view moves, or until
 * [restart] once the connection is back. Main thread only.
 */
class DiskPrefetch(private val scope: CoroutineScope, private val stash: PageStash, private val pageCount: Int) {
    private var around: Pair<IntRange, Turn>? = null
    private var running: Job? = null

    /** Reads around the [shown] pages toward [travel]. Null [shown] stops the reads, as when the current view is not prepared. */
    fun update(shown: IntRange?, travel: Turn) {
        if (shown == null) return stop()
        if (around == shown to travel) return
        around = shown to travel
        read(shown, travel)
    }

    /** Starts over from the place of the last [update], as after the connection came back (docs/spec.md section 6.5). */
    fun restart() {
        val (shown, travel) = around ?: return
        read(shown, travel)
    }

    /** Stops reading ahead. What is on disk stays, and the next [update] starts over. */
    fun stop() {
        around = null
        running?.cancel()
    }

    private fun read(shown: IntRange, travel: Turn) {
        running?.cancel()
        running = scope.launch {
            for (page in diskOrder(shown, pageCount, travel)) {
                when (stash.fill(page)) {
                    Fill.OnDisk, Fill.NotKept -> Unit
                    Fill.OutOfMemory, Fill.ConnectionLost -> break
                }
            }
        }
    }
}
