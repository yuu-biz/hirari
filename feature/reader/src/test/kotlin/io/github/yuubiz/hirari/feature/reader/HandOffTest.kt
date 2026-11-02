package io.github.yuubiz.hirari.feature.reader

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals

class HandOffTest {
    private val other = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    @Test
    fun aValueThatArrivesReleasesNothing() = runBlocking {
        val released = mutableListOf<String>()
        val value = handOff(other, "decoded", { released += it }, { released += it }) { "$it hardware" }
        assertEquals("decoded hardware", value)
        assertEquals(emptyList(), released)
    }

    @Test
    fun aCallerCancelledBeforeTheHandOffReleasesTheInput() = runBlocking {
        val released = mutableListOf<String>()
        launch(Dispatchers.Default) {
            coroutineContext.job.cancel()
            handOff(other, "decoded", { released += "input $it" }, { released += "output $it" }) { "$it hardware" }
        }.join()
        assertEquals(listOf("input decoded"), released)
    }

    @Test
    fun aCallerCancelledDuringTheHandOffReleasesWhatItMade() = runBlocking {
        val released = CompletableDeferred<String>()
        val running = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val call = async(Dispatchers.Default) {
            handOff(other, "decoded", { released.complete("input $it") }, { released.complete("output $it") }) {
                running.countDown()
                proceed.await()
                "$it hardware"
            }
        }
        running.await()
        val cancelling = launch(Dispatchers.Default) { call.cancelAndJoin() }
        while (!call.isCancelled) Thread.yield()
        proceed.countDown()
        cancelling.join()
        assertEquals("output decoded hardware", released.await())
    }
}
