package io.github.yuubiz.hirari.core.source

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

class FileChannelSourceTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val bytes = ByteArray(1000) { it.toByte() }

    private fun source(): FileChannelSource {
        val file = temp.newFile().apply { writeBytes(bytes) }
        return FileChannelSource(FileChannel.open(file.toPath(), StandardOpenOption.READ))
    }

    @Test
    fun `reads the bytes at any offset, in any order`() = source().use { source ->
        val buffer = ByteBuffer.allocate(10)
        assertEquals(10, source.read(990, buffer))
        assertEquals(bytes.copyOfRange(990, 1000).toList(), buffer.array().toList())

        buffer.clear()
        assertEquals(10, source.read(5, buffer))
        assertEquals(bytes.copyOfRange(5, 15).toList(), buffer.array().toList())
    }

    @Test
    fun `reports the size and stops at the end`() = source().use { source ->
        assertEquals(1000L, source.size)
        val buffer = ByteBuffer.allocateDirect(64)
        assertEquals(4, source.read(996, buffer))
        buffer.clear()
        assertEquals(-1, source.read(1000, buffer))
        assertEquals(-1, source.read(5000, buffer))
    }

    @Test
    fun `fills only up to the limit of the buffer`() = source().use { source ->
        val buffer = ByteBuffer.allocate(64)
        buffer.limit(3)
        assertEquals(3, source.read(0, buffer))
        assertEquals(3, buffer.position())
    }
}
