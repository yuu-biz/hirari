package io.github.yuubiz.hirari.core.archive

import io.github.yuubiz.hirari.core.source.ByteSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.max
import kotlin.math.min

/**
 * One open archive (docs/spec.md section 3.3). The session owns its libarchive handle and its [ByteSource], and
 * makes every libarchive call on one thread of its own, so calls from any coroutine are serialized. Cancelling a
 * call stops the native read at the next block. Errors come back as [EntryResult] values, not exceptions.
 *
 * libarchive reads forward only. Reading an entry after the current one reads the local headers in between and
 * skips their data. Reading an earlier one opens the archive again, which rereads the central directory.
 */
class ArchiveSession private constructor(
    private val archive: NativeArchive,
    private val executor: ExecutorService,
    private val dispatcher: CoroutineDispatcher,
    listing: Listing.Listed,
) : Closeable {
    /** The entries in archive order, as far as the archive could be listed. */
    val entries: List<ArchiveEntry> = listing.entries

    /** Why listing stopped before the end of the archive, or null when it reached the end. */
    val indexError: ArchiveFailure? = listing.error

    // Touched only on the session thread.
    private var cursor: Cursor = if (listing.error == null) Cursor.Before(entries.size) else Cursor.Closed

    @Volatile
    private var closed = false

    /**
     * The bytes of the entry at [entryIndex], or why they cannot be read. Stops with [EntryResult.TooLarge] once
     * the entry declares or reaches more than [maxBytes].
     */
    suspend fun read(entryIndex: Int, maxBytes: Int = MAX_ENTRY_BYTES): EntryResult {
        check(!closed) { "the session is closed" }
        val entry = entries[entryIndex]
        refusal(entry, maxBytes)?.let { return it }
        return withContext(dispatcher) {
            archive.begin(coroutineContext.job)
            when (val failure = moveTo(entryIndex)) {
                null -> readData(entry, maxBytes)
                else -> EntryResult.Failed(failure)
            }
        }
    }

    /** Frees the handle and closes the source after the call in progress, if any, returns. */
    override fun close() {
        if (closed) return
        closed = true
        release(archive, executor)
    }

    private fun moveTo(index: Int): ArchiveFailure? {
        var next = when (val at = cursor) {
            is Cursor.At -> if (at.index == index) return null else at.index + 1
            is Cursor.Before -> at.next
            Cursor.Closed -> Int.MAX_VALUE
        }
        if (next > index) {
            cursor = Cursor.Closed
            if (!Status.succeeded(archive.open())) return archive.failure()
            next = 0
        }
        while (next <= index) {
            cursor = Cursor.Closed
            val status = archive.nextHeader()
            if (status == Status.EOF) return ArchiveFailure.Corrupt
            if (!Status.succeeded(status)) return archive.failure()
            next++
            cursor = Cursor.Before(next)
        }
        cursor = Cursor.At(index)
        return null
    }

    private fun readData(entry: ArchiveEntry, maxBytes: Int): EntryResult {
        val bytes = EntryBytes(maxBytes)
        cursor = Cursor.Closed
        val status = archive.readData(bytes)
        if (status == Status.OK || status == Status.STOPPED || status == Status.FAILED) {
            cursor = Cursor.Before(entry.entryIndex + 1)
        }
        val reached = bytes.exceeded
        return when {
            status == Status.OK -> EntryResult.Data(bytes.toByteArray())
            reached != null -> EntryResult.TooLarge(reached)
            else -> EntryResult.Failed(archive.failure())
        }
    }

    /** Where the handle stands between calls. */
    private sealed interface Cursor {
        /** The handle failed or reached the end. The next read opens the archive again. */
        data object Closed : Cursor

        /** The next header read returns the entry at [next]. */
        data class Before(val next: Int) : Cursor

        /** The header of the entry at [index] was read and its data was not. */
        data class At(val index: Int) : Cursor
    }

    internal sealed interface Listing {
        class Unopened(val reason: ArchiveFailure) : Listing

        class Listed(val entries: List<ArchiveEntry>, val error: ArchiveFailure?) : Listing
    }

    companion object {
        /** The size limit of one entry (docs/spec.md section 3.7). */
        const val MAX_ENTRY_BYTES: Int = 256 shl 20

        private val READABLE_COMPRESSION = setOf("uncompressed", "deflation")

        /**
         * Opens a ZIP in [source] and lists its entries. The session owns [source] from here on, and closes it
         * with itself, or right away when opening fails.
         */
        suspend fun open(source: ByteSource): OpenResult {
            val archive = NativeArchive(source)
            val executor = Executors.newSingleThreadExecutor { Thread(it, "archive-session").apply { isDaemon = true } }
            val dispatcher = executor.asCoroutineDispatcher()
            var result: OpenResult? = null
            try {
                val listing = withContext(dispatcher) {
                    archive.begin(coroutineContext.job)
                    list(archive)
                }
                result = when (listing) {
                    is Listing.Unopened -> OpenResult.Failed(listing.reason)
                    is Listing.Listed -> OpenResult.Opened(ArchiveSession(archive, executor, dispatcher, listing))
                }
                return result
            } finally {
                if (result !is OpenResult.Opened) release(archive, executor)
            }
        }

        private fun list(archive: NativeArchive): Listing {
            if (!Status.succeeded(archive.open())) return Listing.Unopened(archive.failure())
            val entries = ArrayList<ArchiveEntry>()
            while (true) {
                val status = archive.nextHeader()
                if (status == Status.EOF) return Listing.Listed(entries, null)
                if (!Status.succeeded(status)) return Listing.Listed(entries, archive.failure())
                val header = archive.header!!
                entries += ArchiveEntry(
                    entryIndex = entries.size,
                    rawName = header.name ?: ByteArray(0),
                    declaredSize = header.size,
                    isDirectory = header.isDirectory,
                    encrypted = header.encrypted,
                    compression = compressionOf(header.formatName),
                )
            }
        }

        private fun release(archive: NativeArchive, executor: ExecutorService) {
            executor.execute(archive::close)
            executor.shutdown()
        }

        /** What a failed operation of [this] amounts to, from what its callbacks recorded. */
        private fun NativeArchive.failure(): ArchiveFailure = when {
            cancelled -> throw CancellationException("archive read cancelled")
            readFailure != null -> ArchiveFailure.ReadError
            endReached -> ArchiveFailure.Truncated
            else -> ArchiveFailure.Corrupt
        }

        /** The result for an entry that is not worth reading, from what the index says about it. */
        internal fun refusal(entry: ArchiveEntry, maxBytes: Int): EntryResult? {
            val compression = entry.compression
            val declared = entry.declaredSize
            return when {
                entry.encrypted -> EntryResult.Encrypted
                compression != null && compression !in READABLE_COMPRESSION -> EntryResult.UnsupportedCompression(compression)
                declared != null && declared > maxBytes -> EntryResult.TooLarge(declared)
                else -> null
            }
        }

        /** The method in libarchive's ZIP format name, such as `deflation` in `ZIP 2.0 (deflation)`. */
        internal fun compressionOf(formatName: String): String? {
            val open = formatName.lastIndexOf('(')
            val close = formatName.lastIndexOf(')')
            return if (open in 0 until close) formatName.substring(open + 1, close) else null
        }
    }
}

/** Collects the data of one entry, stopping once it would pass [limit] bytes. */
internal class EntryBytes(private val limit: Int) : DataSink {
    private var bytes = ByteArray(min(INITIAL_CAPACITY, limit))
    private var size = 0

    /** The end of the block that would have passed the limit, or null while within it. */
    var exceeded: Long? = null
        private set

    override fun write(block: ByteBuffer, offset: Long): Boolean {
        val end = offset + block.remaining()
        if (end > limit) {
            exceeded = end
            return false
        }
        if (end > bytes.size) bytes = bytes.copyOf(max(end, min(bytes.size * 2L, limit.toLong())).toInt())
        block.get(bytes, offset.toInt(), block.remaining())
        size = max(size, end.toInt())
        return true
    }

    fun toByteArray(): ByteArray = if (size == bytes.size) bytes else bytes.copyOf(size)

    private companion object {
        const val INITIAL_CAPACITY = 64 * 1024
    }
}
