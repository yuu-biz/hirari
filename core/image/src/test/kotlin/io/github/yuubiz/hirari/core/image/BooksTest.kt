package io.github.yuubiz.hirari.core.image

import io.github.yuubiz.hirari.core.source.ByteSource
import io.github.yuubiz.hirari.core.source.NoRoomForCopyException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
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

    @Test
    fun aBookCancelledWhileItsFormatIsToldClosesItsSourceOnce() = runBlocking {
        val reading = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val closes = AtomicInteger()
        val source = object : ByteSource {
            override val size = 4096L

            override fun read(offset: Long, dst: ByteBuffer): Int {
                reading.countDown()
                finish.await()
                dst.put(ByteArray(dst.remaining()))
                return dst.position()
            }

            override fun close() {
                closes.incrementAndGet()
            }
        }
        val job = launch(Dispatchers.Default) { Books.openBook(source, ImageDecoding(1 shl 20, 1)) }
        reading.await()
        job.cancel()
        finish.countDown()
        job.join()
        assertEquals(1, closes.get())
    }
}
