package io.github.yuubiz.hirari.core.image

import io.github.yuubiz.hirari.core.source.NoRoomForCopyException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException

class BooksTest {
    @Test
    fun whatAProviderThrowsMapsToWhyTheBookDidNotOpen() {
        val table = mapOf(
            FileNotFoundException() to BookFailure.NotFound,
            IllegalArgumentException() to BookFailure.NotFound,
            SecurityException() to BookFailure.NoPermission,
            NoRoomForCopyException(64L shl 20) to BookFailure.NoRoomForCopy,
            IOException() to BookFailure.ReadError,
            IllegalStateException() to BookFailure.ReadError,
            UnsupportedOperationException() to BookFailure.ReadError,
            NullPointerException() to BookFailure.ReadError,
        )
        table.forEach { (thrown, failure) ->
            val result = runBlocking { Books.safely { throw thrown } }
            assertEquals("$thrown", failure, (result as Books.Safely.Failed).reason)
        }
    }

    @Test(expected = CancellationException::class)
    fun aCancellationFromTheProviderGetsThrough() {
        runBlocking { Books.safely { throw CancellationException() } }
    }

    @Test
    fun whatOpenedAfterTheCallerWasCancelledIsReleased() = runBlocking {
        val opening = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val source = AtomicBoolean(false)
        val released = AtomicBoolean(false)
        val job = launch(Dispatchers.Default) {
            Books.safely(release = { released.set(it === source) }) {
                opening.countDown()
                finish.await()
                source
            }
        }
        opening.await()
        job.cancel()
        finish.countDown()
        job.join()
        assertTrue(released.get())
    }
}
