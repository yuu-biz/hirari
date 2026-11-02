package io.github.yuubiz.hirari.core.archive

import io.github.yuubiz.hirari.core.source.ByteSource
import kotlinx.coroutines.Job
import java.nio.ByteBuffer
import kotlin.coroutines.cancellation.CancellationException

/** libarchive's return codes, and the one libarchive_jni.c adds. */
internal object Status {
    const val OK = 0
    const val EOF = 1
    const val STOPPED = 2
    const val ENCRYPTED_HEADERS = 3
    const val PPMD = 4
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
    /** libarchive's `archive_format` code without the variant bits. */
    val format: Int,
    /** libarchive's code of the outermost filter, such as gzip around a tar. */
    val filter: Int,
)

/** Takes the data of one entry, block by block. Returns false to stop reading. */
internal fun interface DataSink {
    fun write(block: ByteBuffer, offset: Long): Boolean
}

/**
 * A libarchive read handle as [ArchiveSession] drives it, from one thread. Each operation starts with [begin],
 * and the calls return libarchive's [Status] codes.
 */
internal interface ArchiveHandle : AutoCloseable {
    /** The header of the last [nextHeader] that succeeded. */
    val header: Header?

    fun begin(job: Job)

    fun open(): Int

    fun nextHeader(): Int

    fun readData(sink: DataSink): Int

    /**
     * What the operation since [begin], which failed, amounts to.
     *
     * @throws CancellationException when it stopped because its job was cancelled.
     */
    fun failure(): ArchiveFailure
}

/**
 * What libarchive's callbacks record during one operation over [source]: whether the source failed, whether
 * libarchive asked for bytes past its end, and whether the operation's job was cancelled.
 */
internal class SourceReads(private val source: ByteSource) {
    private var job: Job? = null
    private var readFailure: Exception? = null
    private var endReached = false
    private var cancelled = false

    fun begin(job: Job) {
        this.job = job
        readFailure = null
        endReached = false
        cancelled = false
    }

    fun stopIfCancelled(): Boolean {
        if (job?.isActive == false) cancelled = true
        return cancelled
    }

    /** libarchive's read callback: fills [buffer] from its start with up to [length] bytes. -1 is an error. */
    fun read(position: Long, buffer: ByteBuffer, length: Int): Int {
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

    fun failure(): ArchiveFailure = when {
        cancelled -> throw CancellationException("archive read cancelled")
        readFailure != null -> ArchiveFailure.ReadError
        endReached -> ArchiveFailure.Truncated
        else -> ArchiveFailure.Corrupt
    }
}

/** One libarchive read handle over [source], which it owns. Not thread-safe. */
internal class NativeArchive(private val source: ByteSource) : ArchiveHandle {
    private val buffer = ByteBuffer.allocateDirect(BLOCK_SIZE)
    private var handle = nativeCreate(source.size, buffer)
    private val reads = SourceReads(source)

    private var sink: DataSink? = null

    override var header: Header? = null
        private set

    init {
        if (handle == 0L) {
            source.close()
            throw OutOfMemoryError("libarchive session")
        }
    }

    override fun begin(job: Job) {
        check(handle != 0L) { "the archive is closed" }
        reads.begin(job)
    }

    override fun open(): Int = nativeOpen(handle)

    override fun nextHeader(): Int {
        header = null
        return nativeNextHeader(handle)
    }

    override fun readData(sink: DataSink): Int {
        this.sink = sink
        try {
            return nativeReadData(handle)
        } finally {
            this.sink = null
        }
    }

    override fun failure(): ArchiveFailure = reads.failure()

    override fun close() {
        if (handle == 0L) return
        nativeFree(handle)
        handle = 0L
        source.close()
    }

    @Suppress("unused")
    private fun read(position: Long, length: Int): Int = reads.read(position, buffer, length)

    @Suppress("unused")
    private fun header(
        name: ByteArray?,
        size: Long,
        isDirectory: Boolean,
        encrypted: Boolean,
        formatName: String,
        format: Int,
        filter: Int,
    ) {
        header = Header(name, size.takeIf { it >= 0 }, isDirectory, encrypted, formatName, format, filter)
    }

    @Suppress("unused")
    private fun data(block: ByteBuffer, offset: Long): Boolean {
        if (reads.stopIfCancelled()) return false
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
