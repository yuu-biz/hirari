package io.github.yuubiz.hirari.feature.reader

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class ThumbnailSlotTest {
    private val decoded = mutableMapOf<Int, CompletableDeferred<String?>>()
    private val cache = mutableMapOf<Int, String>()

    private val slot = ThumbnailSlot<String>(
        cached = { cache[it] },
        load = { page -> decoded.getOrPut(page) { CompletableDeferred() }.await()?.also { cache[page] = it } },
    )

    @Test
    fun movingToAPageShowsThatPageAndNotTheOneBefore() = runTest {
        decoded[0] = CompletableDeferred("first")
        decoded[7] = CompletableDeferred()
        slot.show(0)
        assertEquals("first", slot.shown)

        val job = launch { slot.show(7) }
        runCurrent()
        assertNull(slot.shown)

        decoded.getValue(7).complete("seventh")
        job.join()
        assertEquals("seventh", slot.shown)
    }

    @Test
    fun aCachedPageShowsAtOnceWithoutLoading() = runTest {
        cache[3] = "cached"
        slot.show(3)
        assertEquals("cached", slot.shown)
        assertEquals(emptySet(), decoded.keys)
    }

    @Test
    fun aPageThatDoesNotDecodeShowsNothing() = runTest {
        cache[1] = "one"
        slot.show(1)
        decoded[2] = CompletableDeferred<String?>().also { it.complete(null) }
        slot.show(2)
        assertNull(slot.shown)
    }
}
