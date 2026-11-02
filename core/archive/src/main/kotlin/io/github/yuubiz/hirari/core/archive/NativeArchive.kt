package io.github.yuubiz.hirari.core.archive

import io.github.yuubiz.hirari.core.source.ByteSource
import kotlinx.coroutines.Job
import java.nio.ByteBuffer

/** libarchive's return codes, and the one libarchive_jni.c adds. */
internal object Status {
    const val OK = 0
    const val EOF = 1
    const val STOPPED = 2
    const val WARN = -20
    const val FAILED = -25

    fun succeeded(status: Int): Boolean = status == OK || status == WARN
}

/** What the last header read reported, before it becomes an [ArchiveEntry]. */
internal class Header(
    val name: ByteArray?,
    val size: Long?,
    val isDirectory: Boolean,
    val encrypted: Boolean,
    val formatName: String,
)

/** Takes the data of one entry, block by block. Returns false to stop reading. */
internal fun interface DataSink {
    fun write(block: ByteBuffer, offset: Long): Boolean
}

/**
 * One libarchive read handle over [source], which it owns. Not thread-safe: [ArchiveSession] calls it from its
 * one thread only. Each operation starts with [begin], which clears what the callbacks recorded.
 */
internal class NativeArchive(private val source: ByteSource) : AutoCloseable {
    private val buffer = ByteBuffer.allocateDirect(BLOCK_SIZE)
    private var handle = nativeCreate(source.size, buffer)

    private var job: Job? = null
    private var sink: DataSink? = null

    /** The header of the last [nextHeader] that succeeded. */
    var header: Header? = null
        private set

    /** The source failed to read during this operation. */
    var readFailure: Exception? = null
        private set

    /** libarchive asked for bytes past the end of the source during this operation. */
    var endReached = false
        private set

    /** The operation stopped because its job was cancelled. */
    var cancelled = false
        private set

    init {
        if (handle == 0L) {
            source.close()
            throw OutOfMemoryError("libarchive session")
        }
    }

    fun begin(job: Job) {
        check(handle != 0L) { "the archive is closed" }
        this.job = job
        readFailure = null
        endReached = false
        cancelled = false
    }

    fun open(): Int = nativeOpen(handle)

    fun nextHeader(): Int {
        header = null
        return nativeNextHeader(handle)
    }

    fun readData(sink: DataSink): Int {
        this.sink = sink
        try {
            return nativeReadData(handle)
        } finally {
            this.sink = null
        }
    }

    override fun close() {
        if (handle == 0L) return
        nativeFree(handle)
        handle = 0L
        source.close()
    }

    private fun stopIfCancelled(): Boolean {
        if (job?.isActive == false) cancelled = true
        return cancelled
    }

    // Called from libarchive's read callback. Fills [buffer] from its start.
    @Suppress("unused")
    private fun read(position: Long, length: Int): Int {
        if (stopIfCancelled()) return -1
        buffer.clear()
        buffer.limit(length)
        val n = try {
            source.read(position, buffer)
        } catch (e: Exception) {
            readFailure = e
            return -1
        }
        if (n <= 0) {
            endReached = true
            return 0
        }
        return n
    }

    @Suppress("unused")
    private fun header(name: ByteArray?, size: Long, isDirectory: Boolean, encrypted: Boolean, formatName: String) {
        header = Header(name, size.takeIf { it >= 0 }, isDirectory, encrypted, formatName)
    }

    @Suppress("unused")
    private fun data(block: ByteBuffer, offset: Long): Boolean {
        if (stopIfCancelled()) return false
        return sink!!.write(block, offset)
    }

    private external fun nativeCreate(size: Long, buffer: ByteBuffer): Long

    private external fun nativeOpen(handle: Long): Int

    private external fun nativeNextHeader(handle: Long): Int

    private external fun nativeReadData(handle: Long): Int

    private external fun nativeFree(handle: Long)

    private companion object {
        const val BLOCK_SIZE = 64 * 1024

        init {
            System.loadLibrary("hirari_archive")
        }
    }
}
