package io.github.yuubiz.hirari.core.image

import android.graphics.Bitmap
import android.system.ErrnoException
import io.github.yuubiz.hirari.core.model.PageResult
import io.github.yuubiz.hirari.core.model.PageShape
import io.github.yuubiz.hirari.core.source.ByteSource
import io.github.yuubiz.hirari.core.source.smb.ConnectionLostException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.nio.ByteBuffer

/** The failures of a PDF tile keep their kind on the way to the reader (docs/spec.md sections 3.9 and 6.1). */
@RunWith(RobolectricTestRunner::class)
class PdfTilesTest {
    private val decoding = ImageDecoding(budgetBytes = 96L shl 20, parallelism = 1)
    private val key = TileKey(8, 0, 0)

    private fun tiles(render: (PixelRect, Int) -> PageResult<Bitmap>) =
        PdfTiles(PageShape.Known(595, 842), decoding, Dispatchers.Unconfined, render)

    @Test
    fun aTileThatRunsOutOfMemoryIsDrawnOnceMoreAtHalfTheSize() = runBlocking {
        val samples = mutableListOf<Int>()
        val drawn = tiles { _, sample ->
            samples += sample
            if (samples.size == 1) throw OutOfMemoryError()
            PageResult.Ready(Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888))
        }.tile(key)

        assertTrue(drawn is PageResult.Ready)
        assertEquals(listOf(8, 16), samples)
    }

    @Test
    fun aTileThatRunsOutOfMemoryTwiceIsARetryableReadError() = runBlocking {
        val result = tiles { _, _ -> throw OutOfMemoryError() }.tile(key)

        assertEquals(readError, result)
    }

    @Test
    fun aLostConnectionIsARetryableFailureAndNotACorruptPage() = runBlocking {
        assertEquals(connectionLost, tiles { _, _ -> connectionLost }.tile(key))
    }

    @Test
    fun aPageOperationThrowsOutOfMemoryWhenAskedTo() {
        val input = ProxyReads(Input())

        assertThrows(OutOfMemoryError::class.java) { input.pageOperation<Nothing>(rethrowOutOfMemory = true) { throw OutOfMemoryError() } }
        assertEquals(readError, input.pageOperation<Nothing> { throw OutOfMemoryError() })
    }

    @Test
    fun anInputFailureDuringTheRunOutOfMemoryStillWins() {
        val input = ProxyReads(Input(ConnectionLostException("connection dropped")))

        val result = input.pageOperation<Nothing>(rethrowOutOfMemory = true) {
            assertThrows(ErrnoException::class.java) { input.onRead(0, 16, ByteArray(16)) }
            throw OutOfMemoryError()
        }

        assertEquals(connectionLost, result)
    }

    private class Input(private val error: IOException? = null) : ByteSource {
        override val size = 32L

        override fun read(offset: Long, dst: ByteBuffer): Int {
            if (error != null) throw error
            return dst.remaining().also { dst.put(ByteArray(it)) }
        }

        override fun close() = Unit
    }
}
