package io.github.yuubiz.hirari.core.archive

import io.github.yuubiz.hirari.core.source.ByteSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.max
import kotlin.math.min

/**
 * One open archive (docs/spec.md section 3.3). The session owns its libarchive handle and its [ByteSource], and
 * makes every libarchive call on one thread of its own, so calls from any coroutine are serialized. Cancelling a
 * call stops the native read at the next block. Errors come back as [EntryResult] values, not exceptions.
 *
 * libarchive reads forward only. In an archive that is not [solid], reading an entry after the current one reads
 * the headers in between and skips their data, and reading an earlier one opens the archive again. A [solid]
 * archive opened with an extraction directory is extracted in order in the background instead, and reads take
 * the entries from there ([Extraction]).
 */
class ArchiveSession private constructor(
    private val archive: NativeArchive,
    private val executor: ExecutorService,
    private val dispatcher: CoroutineDispatcher,
    listing: Listing.Listed,
    /** The format, or null when the archive has no entries to tell it by. */
    val format: ArchiveFormat?,
    /** Reaching an entry means decoding every entry before it (docs/spec.md section 3.3). */
    val solid: Boolean,
    extractDir: File?,
) : Closeable {
    /** The entries in archive order, as far as the archive could be listed. */
    val entries: List<ArchiveEntry> = listing.entries

    /** Why listing stopped before the end of the archive, or null when it reached the end. */
    val indexError: ArchiveFailure? = listing.error

    private val closed = AtomicBoolean(false)

    private val reader: EntryReader = if (solid && extractDir != null) {
        Extraction(archive, dispatcher, entries, extractDir)
    } else {
        Direct()
    }

    // libarchive turns `\` into `/` in RAR 4 names that lack the Unicode flag (docs/spec.md section 3.4).
    private val slashMayBeBackslash = format == ArchiveFormat.Rar

    /**
     * The charset of the entry names (docs/spec.md section 3.4): [override], the reader's choice for the book, when
     * there is one, else the one the names point to, else [undetermined], the setting `fallbackCharset`.
     */
    fun nameCharset(
        override: Charset? = null,
        undetermined: Charset = EntryNames.CP932,
        detector: EntryNames.Detector = EntryNames.platformDetector,
    ): Charset = override ?: EntryNames.charsetOf(entries.map { it.rawName }, detector, undetermined, slashMayBeBackslash)

    /** The name of [entry] in [charset]. Choosing another charset decodes the same [ArchiveEntry.rawName] again. */
    fun name(entry: ArchiveEntry, charset: Charset): String = EntryNames.decode(entry.rawName, charset, slashMayBeBackslash)

    /**
     * The bytes of the entry at [entryIndex], or why they cannot be read. Stops with [EntryResult.TooLarge] once
     * the entry declares or reaches more than [maxBytes].
     */
    suspend fun read(entryIndex: Int, maxBytes: Int = MAX_ENTRY_BYTES): EntryResult {
        check(!closed.get()) { "the session is closed" }
        val entry = entries[entryIndex]
        // libarchive's RAR 5 reader fails to read the data of a directory instead of giving none.
        if (entry.isDirectory) return EntryResult.Data(ByteArray(0))
        refusal(entry, maxBytes)?.let { return it }
        return reader.read(entry, maxBytes)
    }

    /** Frees the handle and closes the source after the call in progress, if any, returns. */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        reader.close()
        release(archive, executor)
    }

    internal interface EntryReader {
        suspend fun read(entry: ArchiveEntry, maxBytes: Int): EntryResult

        fun close()
    }

    /** Reads each entry from the archive when asked, moving the handle forward or opening the archive again. */
    private inner class Direct : EntryReader {
        // Touched only on the session thread.
        private var cursor: Cursor = if (indexError == null) Cursor.Before(entries.size) else Cursor.Closed

        override suspend fun read(entry: ArchiveEntry, maxBytes: Int): EntryResult = withContext(dispatcher) {
            // A dispatch rejected by the shut-down executor resumes on another thread after the handle is freed.
            if (closed.get()) throw CancellationException("the session is closed")
            archive.begin(coroutineContext.job)
            when (val failure = moveTo(entry.entryIndex)) {
                null -> readData(entry, maxBytes)
                else -> EntryResult.Failed(failure)
            }
        }

        override fun close() = Unit

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
                status == Status.PPMD -> EntryResult.UnsupportedCompression(PPMD)
                reached != null -> EntryResult.TooLarge(reached)
                else -> EntryResult.Failed(archive.failure())
            }
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

        data object Encrypted : Listing

        class Listed(
            val entries: List<ArchiveEntry>,
            val error: ArchiveFailure?,
            val format: ArchiveFormat?,
            val filter: Int,
        ) : Listing
    }

    companion object {
        /** The size limit of one entry (docs/spec.md section 3.7). */
        const val MAX_ENTRY_BYTES: Int = 256 shl 20

        private val READABLE_COMPRESSION = setOf("uncompressed", "deflation")
        private const val PREFIX_BYTES = 64

        /**
         * Opens the archive in [source] and lists its entries. The session owns [source] from here on, and closes
         * it with itself, or right away when opening fails. A solid archive is extracted into [extractDir], the
         * directory of this book under `cacheDir/extract/` (docs/spec.md section 7), when it is given.
         */
        suspend fun open(source: ByteSource, extractDir: File? = null): OpenResult {
            val archive = try {
                NativeArchive(source)
            } catch (e: Throwable) {
                source.close()
                if (e is OutOfMemoryError) return OpenResult.Failed(ArchiveFailure.ReadError)
                throw e
            }
            val executor = Executors.newSingleThreadExecutor { Thread(it, "archive-session").apply { isDaemon = true } }
            val dispatcher = executor.asCoroutineDispatcher()
            var result: OpenResult? = null
            try {
                val opened = withContext(dispatcher) {
                    archive.begin(coroutineContext.job)
                    list(archive) to prefix(source)
                }
                result = when (val listing = opened.first) {
                    is Listing.Unopened -> OpenResult.Failed(listing.reason)
                    Listing.Encrypted -> OpenResult.Encrypted
                    is Listing.Listed -> {
                        val solid = isSolid(listing.format, listing.filter, opened.second)
                        OpenResult.Opened(ArchiveSession(archive, executor, dispatcher, listing, listing.format, solid, extractDir))
                    }
                }
                return result
            } finally {
                if (result !is OpenResult.Opened) release(archive, executor)
            }
        }

        private fun prefix(source: ByteSource): ByteArray {
            val buffer = ByteBuffer.allocate(PREFIX_BYTES)
            try {
                while (buffer.hasRemaining() && source.read(buffer.position().toLong(), buffer) > 0) Unit
            } catch (_: Exception) {
            }
            return buffer.array().copyOf(buffer.position())
        }

        private fun list(archive: NativeArchive): Listing {
            // Telling the format reads ahead past the end of a short file, which is no sign of truncation.
            if (!Status.succeeded(archive.open())) {
                val failure = archive.failure()
                return Listing.Unopened(if (failure == ArchiveFailure.Truncated) ArchiveFailure.Corrupt else failure)
            }
            val entries = ArrayList<ArchiveEntry>()
            var format: ArchiveFormat? = null
            var filter = 0
            while (true) {
                val status = archive.nextHeader()
                if (status == Status.EOF) return Listing.Listed(entries, null, format, filter)
                if (status == Status.ENCRYPTED_HEADERS) return Listing.Encrypted
                if (!Status.succeeded(status)) return Listing.Listed(entries, archive.failure(), format, filter)
                val header = archive.header!!
                format = format ?: ArchiveFormat.of(header.format)
                filter = header.filter
                entries += ArchiveEntry(
                    entryIndex = entries.size,
                    rawName = header.name ?: ByteArray(0),
                    declaredSize = header.size,
                    isDirectory = header.isDirectory,
                    encrypted = header.encrypted,
                    compression = if (format == ArchiveFormat.Zip) compressionOf(header.formatName) else null,
                )
            }
        }

        private fun release(archive: NativeArchive, executor: ExecutorService) {
            executor.execute(archive::close)
            executor.shutdown()
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

/** The method an entry gets when it needs the PPMd decoder, which is not built (Q19). */
internal const val PPMD = "ppmd"

/** What a failed operation of [this] amounts to, from what its callbacks recorded. */
internal fun NativeArchive.failure(): ArchiveFailure = when {
    cancelled -> throw CancellationException("archive read cancelled")
    readFailure != null -> ArchiveFailure.ReadError
    endReached -> ArchiveFailure.Truncated
    else -> ArchiveFailure.Corrupt
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
