package io.github.yuubiz.hirari.feature.reader

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals

class CoverStoreTest {
    @Test
    fun theThumbnailsAreMadeAgainOnceTheCoverIsStored() = runTest {
        val process = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        var remade = 0
        val stored = process.storeCover(store = { true }, remake = { remade++ })
        advanceUntilIdle()
        assertEquals(true, stored.await())
        assertEquals(1, remade)
    }

    @Test
    fun theResultComesOnceTheCoverIsStoredWithoutWaitingForTheThumbnails() = runTest {
        val process = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val remaking = CompletableDeferred<Unit>()
        var remade = 0
        val stored = process.storeCover(store = { true }, remake = { remaking.await().also { remade++ } })
        runCurrent()
        assertEquals(true, stored.isCompleted)
        assertEquals(0, remade)
        remaking.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, remade)
    }

    @Test
    fun aScreenThatClosedWhileTheCoverWasBeingStoredStillGetsTheThumbnailsMadeAgain() = runTest {
        val process = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val write = CompletableDeferred<Unit>()
        var remade = 0
        val screen: Job = launch { process.storeCover(store = { write.await().let { true } }, remake = { remade++ }).await() }
        runCurrent()
        screen.cancel()
        runCurrent()
        write.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, remade)
    }

    @Test
    fun aCoverThatWasNotStoredMakesNoThumbnails() = runTest {
        val process = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        var remade = 0
        val refused = process.storeCover(store = { false }, remake = { remade++ })
        val failed = process.storeCover(store = { throw IOException("disk full") }, remake = { remade++ })
        advanceUntilIdle()
        assertEquals(false, refused.await())
        assertEquals(false, failed.await())
        assertEquals(0, remade)
    }
}
