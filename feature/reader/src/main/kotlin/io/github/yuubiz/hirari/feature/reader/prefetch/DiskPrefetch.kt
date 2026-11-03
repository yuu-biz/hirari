package io.github.yuubiz.hirari.feature.reader.prefetch

import io.github.yuubiz.hirari.core.image.Fill
import io.github.yuubiz.hirari.core.image.PageStash
import io.github.yuubiz.hirari.feature.reader.Turn
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
 * from there. A page that runs out of memory stops the layer until the view moves. Main thread only.
 */
class DiskPrefetch(private val scope: CoroutineScope, private val stash: PageStash, private val pageCount: Int) {
    private var around: Pair<IntRange, Turn>? = null
    private var running: Job? = null

    fun update(shown: IntRange, travel: Turn) {
        if (around == shown to travel) return
        around = shown to travel
        running?.cancel()
        running = scope.launch {
            for (page in diskOrder(shown, pageCount, travel)) {
                if (stash.fill(page) == Fill.OutOfMemory) break
            }
        }
    }

    /** Stops reading ahead. What is on disk stays, and the next [update] starts over. */
    fun stop() {
        around = null
        running?.cancel()
    }
}
