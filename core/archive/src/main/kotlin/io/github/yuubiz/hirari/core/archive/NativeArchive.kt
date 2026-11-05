package io.github.yuubiz.hirari.core.archive

import io.github.yuubiz.hirari.core.source.ByteSource
import io.github.yuubiz.hirari.core.source.smb.ConnectionLostException
import kotlinx.coroutines.Job
import java.nio.ByteBuffer
import kotlin.coroutines.cancellation.CancellationException

/** libarchive's return codes, and the one libarchive_jni.c adds. */
internal object Status {
    const val OK = 0
    const val EOF = 1
    const val STOPPED = 2
    const val ENCRYPTED_HEADERS = 3
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
    /** Where the header starts in the source, or null when libarchive does not say. */
    val position: Long? = null,
)

/**
 * A handle opened with a jump reads the source up to [at], and from there the source from [to] on, so that the
 * header at [to] follows the data that ends at [at] (docs/spec.md section 3.3).
 */
internal data class Jump(val at: Long, val to: Long)

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

    /** Opens the source again from the start, with [jump] when it is given. */
    fun open(jump: Jump? = null): Int

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

    fun failure(): ArchiveFailure {
        if (cancelled) throw CancellationException("archive read cancelled")
        readFailure?.let { return failureOf(it) }
        return if (endReached) ArchiveFailure.Truncated else ArchiveFailure.Corrupt
    }
}

/** What an exception of a [ByteSource] read amounts to (docs/spec.md section 3.3). */
internal fun failureOf(e: Exception): ArchiveFailure =
    if (e is ConnectionLostException) ArchiveFailure.ConnectionLost else ArchiveFailure.ReadError

/** One libarchive read handle over [source], which the session owns and closes. Not thread-safe. */
internal class NativeArchive(source: ByteSource) : ArchiveHandle {
    private val buffer = ByteBuffer.allocateDirect(BLOCK_SIZE)
    private var handle = nativeCreate(source.size, buffer)
    private val reads = SourceReads(source)

    private var sink: DataSink? = null

    override var header: Header? = null
        private set

    init {
        if (handle == 0L) throw OutOfMemoryError("libarchive session")
    }

    override fun begin(job: Job) {
        check(handle != 0L) { "the archive is closed" }
        reads.begin(job)
    }

    override fun open(jump: Jump?): Int = nativeOpen(handle, jump?.at ?: 0, jump?.to ?: 0)

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
        position: Long,
    ) {
        header = Header(name, size.takeIf { it >= 0 }, isDirectory, encrypted, formatName, format, filter, position.takeIf { it >= 0 })
    }

    @Suppress("unused")
    private fun data(block: ByteBuffer, offset: Long): Boolean {
        if (reads.stopIfCancelled()) return false
        return sink!!.write(block, offset)
    }

    private external fun nativeCreate(size: Long, buffer: ByteBuffer): Long

    private external fun nativeOpen(handle: Long, jumpAt: Long, jumpTo: Long): Int

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
