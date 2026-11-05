package io.github.yuubiz.hirari.core.image

import android.system.ErrnoException
import android.system.OsConstants
import io.github.yuubiz.hirari.core.model.FailureReason
import io.github.yuubiz.hirari.core.model.PageResult
import io.github.yuubiz.hirari.core.source.ByteSource
import io.github.yuubiz.hirari.core.source.smb.ConnectionLostException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.nio.ByteBuffer

/** Tests the proxy callbacks and the mapping used for open, shape and render, without a native PdfRenderer. */
@RunWith(RobolectricTestRunner::class)
class PdfInputFailureTest {
    @Test
    fun aConnectionLostAfterSomeBytesIsARetryablePageFailure() {
        val lost = ConnectionLostException("connection dropped")
        val source = Input(lost)
        val input = ProxyReads(source)
        val data = ByteArray(16)

        val errno = assertThrows(ErrnoException::class.java) { input.onRead(0, data.size, data) }

        assertEquals(OsConstants.EIO, errno.errno)
        assertSame(lost, errno.cause)
        assertEquals(8, source.bytesRead)
        assertEquals(
            PageResult.Failed(FailureReason.ConnectionLost, retryable = true),
            input.pageFailure(IllegalStateException("cannot open page")),
        )
        assertEquals(connectionLost, input.pageFailure(IOException("cannot render page")))
        assertEquals(BookFailure.ReadError, input.openFailure(IOException("cannot open PDF")))
    }

    @Test
    fun anOrdinaryInputFailureIsARetryableReadError() {
        val input = ProxyReads(Input(IOException("read failed")))
        failRead(input)

        assertEquals(PageResult.Failed(FailureReason.ReadError, retryable = true), input.pageFailure(IllegalStateException()))
        assertEquals(BookFailure.ReadError, input.openFailure(IOException()))
        assertEquals(BookFailure.ReadError, input.openFailure(SecurityException()))
    }

    @Test
    fun garbageReadWithoutAnInputFailureStaysCorrupt() {
        val input = ProxyReads(Input())
        assertEquals(16, input.onRead(0, 16, ByteArray(16)))

        assertEquals(PageResult.Failed(FailureReason.Corrupt, retryable = false), input.pageFailure(IllegalStateException()))
        assertEquals(corrupt, input.pageFailure(IOException()))
        assertEquals(BookFailure.Corrupt, input.openFailure(IOException()))
        assertEquals(BookFailure.Encrypted, input.openFailure(SecurityException()))
    }

    @Test
    fun theFirstFailureIsKeptUntilAReadSucceeds() {
        val source = Input(ConnectionLostException("connection dropped"))
        val input = ProxyReads(source)
        failRead(input)
        source.error = IOException("later failure")
        failRead(input)

        assertEquals(connectionLost, input.pageFailure(IllegalStateException()))

        source.error = null
        assertEquals(16, input.onRead(0, 16, ByteArray(16)))
        assertEquals(corrupt, input.pageFailure(IllegalStateException()))
        assertEquals(BookFailure.Corrupt, input.openFailure(IOException()))

        source.error = IOException("new read failure")
        failRead(input)
        assertEquals(readError, input.pageFailure(IllegalStateException()))
    }

    @Test
    fun aLaterConnectionFailureDoesNotReplaceTheFirstReadError() {
        val source = Input(IOException("first failure"))
        val input = ProxyReads(source)
        failRead(input)
        source.error = ConnectionLostException("later failure")
        failRead(input)

        assertEquals(readError, input.pageFailure(IllegalStateException()))
    }

    @Test
    fun anotherDocumentDoesNotInheritAnInputFailure() {
        val failed = ProxyReads(Input(ConnectionLostException("connection dropped")))
        failRead(failed)
        val healthy = ProxyReads(Input())

        assertEquals(corrupt, healthy.pageFailure(IllegalStateException()))
        assertEquals(connectionLost, failed.pageFailure(IllegalStateException()))
    }

    @Test
    fun aSizeFailureIsAlsoAnInputFailureAndIsClearedByASuccessfulRead() {
        val source = object : ByteSource {
            override val size: Long get() = throw ConnectionLostException("size unavailable")
            override fun read(offset: Long, dst: ByteBuffer): Int = dst.remaining().also { dst.put(ByteArray(it)) }
            override fun close() = Unit
        }
        val input = ProxyReads(source)
        assertThrows(ErrnoException::class.java) { input.onGetSize() }
        assertEquals(BookFailure.ReadError, input.openFailure(IOException()))
        assertEquals(connectionLost, input.pageFailure(IllegalStateException()))

        input.onRead(0, 16, ByteArray(16))
        assertEquals(corrupt, input.pageFailure(IllegalStateException()))
    }

    private fun failRead(input: ProxyReads) {
        assertThrows(ErrnoException::class.java) { input.onRead(0, 16, ByteArray(16)) }
    }

    /** Returns garbage, then fails at byte eight when [error] is set. */
    private class Input(var error: IOException? = null) : ByteSource {
        override val size = 32L
        var bytesRead = 0

        override fun read(offset: Long, dst: ByteBuffer): Int {
            val failure = error
            if (failure != null && offset >= 8) throw failure
            if (offset >= size) return -1
            val end = if (failure == null) size else 8L
            val count = minOf(dst.remaining(), (end - offset).toInt())
            dst.put(ByteArray(count) { 'x'.code.toByte() })
            bytesRead += count
            return count
        }

        override fun close() = Unit
    }
}
