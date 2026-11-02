package io.github.yuubiz.hirari.core.image

import io.github.yuubiz.hirari.core.model.PageResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

class OpenBookTest {
    private val decoding = ImageDecoding(budgetBytes = 64L shl 20, parallelism = 1)

    @Test
    fun runningOutOfMemoryReadingAPageIsARetryableReadError() = runTest {
        val files = listOf(FolderFile("001.jpg", size = null) { throw OutOfMemoryError() })
        val book = (Books.openFolder(files, decoding) as BookResult.Opened).book
        assertEquals(readError, book.page(0, PixelSize(1080, 1920)))
        assertEquals(readError, book.shape(0))
        book.close()
    }

    @Test
    fun aDecodeStillQueuedAtTheTimeoutNeverStarts() = runTest {
        val scope = CoroutineScope(SupervisorJob())
        val busy = CountDownLatch(1)
        val release = CountDownLatch(1)
        scope.launch(decoding.dispatcher) {
            busy.countDown()
            release.await()
        }
        busy.await()
        val started = AtomicBoolean(false)
        val result = decodeWithTimeout(scope, decoding.dispatcher) {
            started.set(true)
            PageResult.Ready(Unit)
        }
        release.countDown()
        scope.coroutineContext.job.children.toList().joinAll()
        assertEquals(timedOut, result)
        assertFalse("the queued decode started after its caller gave up", started.get())
    }

    @Test
    fun aDecodeRunningAtTheTimeoutIsToldItsResultIsNotWanted() = runTest {
        val scope = CoroutineScope(SupervisorJob())
        val running = CountDownLatch(1)
        val release = CountDownLatch(1)
        val wanted = AtomicBoolean(true)
        val callerGoesOn = Job()
        val result = async {
            decodeWithTimeout(scope, decoding.dispatcher) { isActive ->
                running.countDown()
                release.await()
                wanted.set(isActive())
                PageResult.Ready(Unit)
            }.also { callerGoesOn.join() }
        }
        runCurrent()
        running.await()
        advanceTimeBy(ImageDecoding.DECODE_TIMEOUT_MILLIS + 1)
        release.countDown()
        scope.coroutineContext.job.children.toList().joinAll()
        assertFalse(wanted.get())
        callerGoesOn.complete()
        assertEquals(timedOut, result.await())
    }
}
