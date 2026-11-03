package io.github.yuubiz.hirari.feature.reader.prefetch

import io.github.yuubiz.hirari.core.image.Fill
import io.github.yuubiz.hirari.core.image.PageStash
import io.github.yuubiz.hirari.feature.reader.Turn
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DiskPrefetchTest {
    @Test
    fun theLayerReadsFiftyPagesTowardTheTravelThenFiftyTheOtherWayNearestFirst() {
        val order = diskOrder(100..101, pageCount = 300, Turn.Forward)
        assertEquals((102..151).toList() + (99 downTo 50).toList(), order)
        assertEquals((99 downTo 50).toList() + (102..151).toList(), diskOrder(100..101, pageCount = 300, Turn.Back))
    }

    @Test
    fun theLayerStopsAtTheEndsOfTheBook() {
        assertEquals(listOf(4, 5, 2, 1, 0), diskOrder(3..3, pageCount = 6, Turn.Forward))
        assertEquals(emptyList<Int>(), diskOrder(0..0, pageCount = 1, Turn.Forward))
    }

    /** Fills complete one at a time when the test lets the next through. A page in [outcomes] is not kept. */
    private class Stash : PageStash {
        val filled = mutableListOf<Int>()
        val outcomes = mutableMapOf<Int, Fill>()
        var next = CompletableDeferred<Unit>()

        override suspend fun holds(index: Int) = index in filled

        override suspend fun fill(index: Int): Fill {
            next.await()
            next = CompletableDeferred()
            filled += index
            return outcomes[index] ?: Fill.OnDisk
        }
    }

    private fun kotlinx.coroutines.test.TestScope.letThrough(stash: Stash, count: Int) = repeat(count) {
        stash.next.complete(Unit)
        runCurrent()
    }

    @Test
    fun pagesAreFilledOneAtATimeAndAMoveStartsOverFromTheNewPlace() = runTest {
        val stash = Stash()
        val prefetch = DiskPrefetch(backgroundScope, stash, pageCount = 200)
        prefetch.update(10..10, Turn.Forward)
        runCurrent()
        repeat(2) {
            stash.next.complete(Unit)
            runCurrent()
        }
        assertEquals(listOf(11, 12), stash.filled)

        prefetch.update(10..10, Turn.Forward)
        prefetch.update(100..101, Turn.Back)
        runCurrent()
        stash.next.complete(Unit)
        runCurrent()
        assertEquals(listOf(11, 12, 99), stash.filled)

        prefetch.stop()
        stash.next.complete(Unit)
        runCurrent()
        assertEquals(listOf(11, 12, 99), stash.filled)

        stash.next = CompletableDeferred()
        prefetch.update(100..101, Turn.Back)
        runCurrent()
        stash.next.complete(Unit)
        runCurrent()
        assertEquals(listOf(11, 12, 99, 99), stash.filled)
    }

    @Test
    fun aPageThatRunsOutOfMemoryStopsTheLayerUntilTheViewMoves() = runTest {
        val stash = Stash().apply { outcomes[12] = Fill.OutOfMemory; outcomes[11] = Fill.NotKept }
        val prefetch = DiskPrefetch(backgroundScope, stash, pageCount = 200)
        prefetch.update(10..10, Turn.Forward)
        runCurrent()
        letThrough(stash, 2)
        assertEquals(listOf(11, 12), stash.filled)

        prefetch.update(10..10, Turn.Forward)
        runCurrent()
        letThrough(stash, 1)
        assertEquals(listOf(11, 12), stash.filled)

        prefetch.update(11..11, Turn.Forward)
        runCurrent()
        assertEquals(listOf(11, 12, 12), stash.filled)
    }

    @Test
    fun aMoveToAViewNotPreparedYetStopsTheReadsAroundTheOldOneAtOnce() = runTest {
        val stash = Stash()
        val prefetch = DiskPrefetch(backgroundScope, stash, pageCount = 200)
        prefetch.update(10..10, Turn.Forward)
        runCurrent()
        prefetch.update(null, Turn.Forward)
        letThrough(stash, 1)
        assertEquals(emptyList<Int>(), stash.filled)

        stash.next = CompletableDeferred()
        prefetch.update(100..101, Turn.Back)
        runCurrent()
        letThrough(stash, 1)
        assertEquals(listOf(99), stash.filled)
    }

    @Test
    fun aBrokenConnectionStopsTheLayerAndTheReconnectStartsItOverFromTheSamePlace() = runTest {
        val stash = Stash().apply { outcomes[12] = Fill.ConnectionLost }
        val prefetch = DiskPrefetch(backgroundScope, stash, pageCount = 200)
        prefetch.restart()
        runCurrent()
        prefetch.update(10..10, Turn.Forward)
        runCurrent()
        letThrough(stash, 2)
        assertEquals(listOf(11, 12), stash.filled)

        prefetch.update(10..10, Turn.Forward)
        runCurrent()
        letThrough(stash, 1)
        assertEquals(listOf(11, 12), stash.filled)

        stash.outcomes.clear()
        stash.next = CompletableDeferred()
        prefetch.restart()
        runCurrent()
        letThrough(stash, 3)
        assertEquals(listOf(11, 12, 11, 12, 13), stash.filled)
    }
}
