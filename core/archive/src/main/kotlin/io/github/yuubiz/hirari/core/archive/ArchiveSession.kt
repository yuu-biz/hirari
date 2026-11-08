package io.github.yuubiz.hirari.core.archive

import io.github.yuubiz.hirari.core.source.ByteSource
import io.github.yuubiz.hirari.core.model.BookPassword
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
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
 * One open archive (docs/spec.md section 3.3). The session owns its [ByteSource] and its libarchive handle, and
 * reads on one thread of its own, so calls from any coroutine are serialized. Cancelling a call stops the read at
 * the next block. Errors come back as [EntryResult] values, not exceptions.
 *
 * A ZIP is listed from its central directory ([CentralZip]) when that is whole. Otherwise libarchive lists the
 * archive, and it reads forward only: in an archive that is not [solid], reading an entry after the current one
 * reads the headers in between and skips their data, and reading an earlier one opens the archive again. A [solid]
 * archive opened with an extraction directory is extracted in order in the background instead, and reads take
 * the entries from there ([Extraction]).
 */
class ArchiveSession private constructor(
    private val source: ByteSource,
    /** Made on the session thread when first needed, which a ZIP listed from its central directory may never be. */
    private val libarchive: Lazy<ArchiveHandle>,
    private val executor: ExecutorService,
    private val dispatcher: CoroutineDispatcher,
    listing: Listing.Listed,
    /** The format, or null when the archive has no entries to tell it by. */
    val format: ArchiveFormat?,
    /** Reaching an entry means decoding every entry before it (docs/spec.md section 3.3). */
    val solid: Boolean,
    extractDir: File?,
    private val password: BookPassword?,
) : Closeable {
    /** The entries in archive order, as far as the archive could be listed. */
    val entries: List<ArchiveEntry> = listing.entries

    /** Why listing stopped before the end of the archive, or null when it reached the end or [partial] stopped it. */
    val indexError: ArchiveFailure? = listing.error

    /**
     * Listing stopped where the [ListingNeed] given to [open] was met, before the end of the archive, so [entries]
     * leaves out the ones after the last (docs/spec.md section 4.4). It is not an error.
     */
    val partial: Boolean = listing.partial

    /**
     * The [ListingNeed] the listing went as far as: the one given to [open] in a format that lists entry by entry from
     * headers of its own, and [ListingNeed.All] in one listed from an index, which lists every entry whatever was asked.
     */
    val need: ListingNeed = listing.need

    private val closed = AtomicBoolean(false)
    private val passwordLock = Mutex()
    private var passwordLoaded = false
    private var hasPassword = false
    private var passwordVerified = false

    private suspend fun unlock(entry: ArchiveEntry, read: suspend () -> EntryResult): EntryResult {
        if (!entry.traditionalEncryption) return read()
        return passwordLock.withLock {
            try {
                suspend fun use(secret: CharArray?) {
                    try {
                        withContext(dispatcher) {
                            if (closed.get()) throw CancellationException("the session is closed")
                            libarchive.value.setPassword(secret)
                            reader.passwordChanged()
                            passwordVerified = false
                        }
                        hasPassword = secret != null
                    } finally {
                        secret?.fill('\u0000')
                    }
                }
                if (!passwordLoaded) {
                    use(password?.initial())
                    passwordLoaded = true
                }
                suspend fun attempt(): EntryResult {
                    val result = read()
                    return if (!passwordVerified && result is EntryResult.Failed) {
                        EntryResult.Failed(ArchiveFailure.PasswordRequired)
                    } else result
                }
                var result = attempt()
                while (result == EntryResult.Failed(ArchiveFailure.PasswordRequired)) {
                    val next = password?.retry(hasPassword) ?: run {
                        password?.discard()
                        return@withLock result
                    }
                    use(next)
                    result = attempt()
                }
                if (result is EntryResult.Data) {
                    if (passwordVerified) password?.accepted()
                } else password?.discard()
                result
            } catch (e: Throwable) {
                password?.discard()
                throw e
            }
        }
    }

    private val reader: EntryReader = when {
        listing.records != null -> FromCentralDirectory(listing.records)
        // An extraction pass goes through the whole archive, which a partial listing does not know.
        solid && extractDir != null && !partial -> Extraction(libarchive.value, dispatcher, entries, extractDir)
        // A solid entry needs the data of the entries before it, which a jump would skip.
        else -> Direct(
            when {
                // Listing stopped after the header of the last entry it gives, before that entry's data.
                partial -> Cursor.At(entries.lastIndex)
                indexError == null -> Cursor.Before(entries.size)
                else -> Cursor.Closed
            },
            listing.positions.takeUnless { solid },
        )
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
    ): Charset = override ?: EntryNames.charsetOf(entries.map { it.rawName }, undetermined, slashMayBeBackslash)

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
        return unlock(entry) { reader.read(entry, maxBytes) }
    }

    /**
     * Up to [count] bytes for sniffing, without reading the rest of a non-solid entry (3.5). A solid archive has no
     * cheaper way to an entry's first bytes than to the whole entry, so it gives the whole entry.
     */
    suspend fun readPrefix(entryIndex: Int, count: Int): EntryResult {
        check(!closed.get()) { "the session is closed" }
        require(count > 0)
        if (solid) return read(entryIndex)
        val entry = entries[entryIndex]
        if (entry.isDirectory) return EntryResult.Data(ByteArray(0))
        refusal(entry, MAX_ENTRY_BYTES)?.let { return it }
        return unlock(entry) {
            when (val current = reader) {
                is FromCentralDirectory -> current.read(entry, count, prefix = true)
                is Direct -> current.read(entry, count, prefix = true)
                else -> error("only a solid archive is extracted")
            }
        }
    }

    /** Frees the handle and closes the source after the call in progress, if any, returns. */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        reader.close()
        release(source, libarchive, executor)
    }

    internal interface EntryReader {
        fun passwordChanged() = Unit
        suspend fun read(entry: ArchiveEntry, maxBytes: Int): EntryResult

        fun close()
    }

    /**
     * Reads a stored or Deflate entry of a ZIP listed from its central directory from its local header, without
     * libarchive, and leaves the other methods to libarchive (docs/spec.md section 3.3).
     */
    private inner class FromCentralDirectory(private val records: List<ZipRecord>) : EntryReader {
        private val others = Direct(Cursor.Closed, positions = null)

        override fun passwordChanged() = others.passwordChanged()

        override suspend fun read(entry: ArchiveEntry, maxBytes: Int): EntryResult = read(entry, maxBytes, prefix = false)

        suspend fun read(entry: ArchiveEntry, maxBytes: Int, prefix: Boolean): EntryResult {
            val record = records[entry.entryIndex]
            if (!CentralZip.reads(record)) return others.read(entry, maxBytes, prefix)
            return withContext(dispatcher) {
                if (closed.get()) throw CancellationException("the session is closed")
                CentralZip.read(source, record, maxBytes, coroutineContext.job, prefix)
            }
        }

        override fun close() = Unit
    }

    /**
     * Reads each entry with libarchive when asked, moving the handle forward or opening the archive again. [cursor]
     * is where the handle stands at first. With the [positions] of the headers, opening again jumps from the end of
     * the first entry to the header of the one wanted, instead of reading every header before it (docs/spec.md
     * section 3.3).
     */
    private inner class Direct(private var cursor: Cursor, private var positions: LongArray?) : EntryReader {
        // Touched only on the session thread.
        private val archive: ArchiveHandle get() = libarchive.value

        override fun passwordChanged() { cursor = Cursor.Closed }

        override suspend fun read(entry: ArchiveEntry, maxBytes: Int): EntryResult = read(entry, maxBytes, prefix = false)

        suspend fun read(entry: ArchiveEntry, maxBytes: Int, prefix: Boolean): EntryResult = withContext(dispatcher) {
            // A dispatch rejected by the shut-down executor resumes on another thread after the handle is freed.
            if (closed.get()) throw CancellationException("the session is closed")
            archive.begin(coroutineContext.job)
            when (val failure = moveTo(entry.entryIndex)) {
                null -> readData(entry, maxBytes, prefix)
                else -> EntryResult.Failed(failure)
            }
        }

        override fun close() = Unit

        private fun moveTo(index: Int): ArchiveFailure? {
            val next = when (val at = cursor) {
                is Cursor.At -> if (at.index == index) return null else at.index + 1
                is Cursor.Before -> at.next
                Cursor.Closed -> Int.MAX_VALUE
            }
            val walk = next <= index && (next until index).none { entries[it].encrypted }
            val jumps = positions
            if (walk && (jumps == null || index - next <= WALK_AHEAD)) return walkTo(next, index)
            if (jumps != null && index > 0) {
                cursor = Cursor.Closed
                if (!Status.succeeded(archive.open(Jump(jumps[1], jumps[index])))) return archive.failure()
                val failure = header(0) ?: header(index)
                if (failure != null || landed(index)) return failure
                // The header is not where the listing found it, so the archive is read as if none were known.
                positions = null
            }
            cursor = Cursor.Closed
            if (!Status.succeeded(archive.open())) return archive.failure()
            return walkTo(0, index)
        }

        /** Reads the headers from the entry at [next] to the one at [index]. */
        private fun walkTo(next: Int, index: Int): ArchiveFailure? {
            for (i in next..index) header(i)?.let { return it }
            cursor = Cursor.At(index)
            return null
        }

        /** Reads the next header, which is the one of the entry at [index]. */
        private fun header(index: Int): ArchiveFailure? {
            cursor = Cursor.Closed
            val status = archive.nextHeader()
            if (status == Status.EOF) return ArchiveFailure.Corrupt
            if (!Status.succeeded(status)) return archive.failure()
            cursor = Cursor.Before(index + 1)
            return null
        }

        /** Whether the header read after a jump is the one of the entry at [index], as the listing read it. */
        private fun landed(index: Int): Boolean {
            val header = archive.header ?: return false
            val entry = entries[index]
            val same = (header.name ?: ByteArray(0)).contentEquals(entry.rawName) && header.size == entry.declaredSize
            if (same) cursor = Cursor.At(index)
            return same
        }

        private fun readData(entry: ArchiveEntry, maxBytes: Int, prefix: Boolean): EntryResult {
            val bytes = EntryBytes(maxBytes, prefix)
            cursor = Cursor.Closed
            val status = archive.readData(bytes)
            if (entry.traditionalEncryption && status == Status.OK) passwordVerified = true
            if (status == Status.OK || status == Status.STOPPED || status == Status.FAILED) {
                cursor = Cursor.Before(entry.entryIndex + 1)
            }
            val reached = bytes.exceeded
            return when {
                status == Status.ENCRYPTED_ENTRY -> EntryResult.Encrypted
                status == Status.OK || (status == Status.STOPPED && bytes.prefixComplete) -> EntryResult.Data(bytes.toByteArray())
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

        /**
         * [records] are the central directory records of [entries], when the listing came from them. [positions]
         * are where the header of each entry starts in the source, when a reader can jump to them.
         */
        class Listed(
            val entries: List<ArchiveEntry>,
            val error: ArchiveFailure?,
            val format: ArchiveFormat?,
            val filter: Int,
            val records: List<ZipRecord>? = null,
            val positions: LongArray? = null,
            val partial: Boolean = false,
            val need: ListingNeed = ListingNeed.All,
        ) : Listing
    }

    companion object {
        /** The size limit of one entry (docs/spec.md section 3.7). */
        const val MAX_ENTRY_BYTES: Int = 256 shl 20

        private val READABLE_COMPRESSION = setOf("uncompressed", "deflation", "ppmd-1")
        private const val PREFIX_BYTES = 64

        /** How many headers a read walks forward before it jumps instead. A jump reads two. */
        private const val WALK_AHEAD = 2

        /**
         * The formats whose entries each start from a header of their own: a jump can land on one, and a listing
         * that stops early leaves the rest of the archive unread (docs/spec.md section 4.4).
         */
        private val OWN_HEADERS = setOf(ArchiveFormat.Rar, ArchiveFormat.Rar5, ArchiveFormat.Tar)

        /**
         * Opens the archive in [source] and lists its entries, as far as [need] asks in a format without an index in
         * one place. The session owns [source] from here on, and closes it with itself, or right away when opening
         * fails. A solid archive is extracted into [extractDir], the directory of this book under `cacheDir/extract/`
         * (docs/spec.md section 7), when it is given and the listing is not [partial].
         */
        suspend fun open(
            source: ByteSource,
            extractDir: File? = null,
            password: BookPassword? = null,
            need: ListingNeed = ListingNeed.All,
        ): OpenResult = open(source, extractDir, ::NativeArchive, password, need)

        /** [open] with [handle] in place of libarchive. [handle] is called on the session thread, if at all. */
        internal suspend fun open(source: ByteSource, extractDir: File?, handle: (ByteSource) -> ArchiveHandle): OpenResult =
            open(source, extractDir, handle, null)

        internal suspend fun open(
            source: ByteSource,
            extractDir: File?,
            handle: (ByteSource) -> ArchiveHandle,
            password: BookPassword?,
            need: ListingNeed = ListingNeed.All,
        ): OpenResult {
            val libarchive = lazy(LazyThreadSafetyMode.NONE) { handle(source) }
            val executor = Executors.newSingleThreadExecutor { Thread(it, "archive-session").apply { isDaemon = true } }
            val dispatcher = executor.asCoroutineDispatcher()
            var result: OpenResult? = null
            try {
                val opened = withContext(dispatcher) {
                    val job = coroutineContext.job
                    val prefix = prefix(source)
                    val listing = listCentralDirectory(source, prefix, job) ?: try {
                        libarchive.value.let {
                            it.begin(job)
                            list(it, need)
                        }
                    } catch (_: OutOfMemoryError) {
                        Listing.Unopened(ArchiveFailure.ReadError)
                    }
                    listing to prefix
                }
                result = when (val listing = opened.first) {
                    is Listing.Unopened -> OpenResult.Failed(listing.reason)
                    Listing.Encrypted -> OpenResult.Encrypted
                    is Listing.Listed -> {
                        val solid = isSolid(listing.format, listing.filter, opened.second)
                        OpenResult.Opened(ArchiveSession(source, libarchive, executor, dispatcher, listing, listing.format, solid, extractDir, password))
                    }
                }
                return result
            } finally {
                if (result !is OpenResult.Opened) release(source, libarchive, executor)
            }
        }

        /** The listing from the central directory, or null when libarchive has to list the archive. */
        private fun listCentralDirectory(source: ByteSource, prefix: ByteArray, job: Job): Listing? {
            if (!CentralZip.startsZip(prefix)) return null
            val records = try {
                CentralZip.list(source, job)
            } catch (e: SourceFailure) {
                return Listing.Unopened(failureOf(e.cause))
            } catch (_: OutOfMemoryError) {
                return Listing.Unopened(ArchiveFailure.ReadError)
            } ?: return null
            val entries = records.mapIndexed { i, record -> record.entry(i) }
            return Listing.Listed(entries, null, ArchiveFormat.Zip, filter = 0, records)
        }

        private fun prefix(source: ByteSource): ByteArray {
            val buffer = ByteBuffer.allocate(PREFIX_BYTES)
            try {
                while (buffer.hasRemaining() && source.read(buffer.position().toLong(), buffer) > 0) Unit
            } catch (_: Exception) {
            }
            return buffer.array().copyOf(buffer.position())
        }

        private fun list(archive: ArchiveHandle, need: ListingNeed): Listing {
            // Telling the format reads ahead past the end of a short file, which is no sign of truncation.
            if (!Status.succeeded(archive.open())) {
                val failure = archive.failure()
                return Listing.Unopened(if (failure == ArchiveFailure.Truncated) ArchiveFailure.Corrupt else failure)
            }
            val entries = ArrayList<ArchiveEntry>()
            val positions = ArrayList<Long?>()
            var format: ArchiveFormat? = null
            var filter = 0
            val met = metBy(need)
            fun listed(error: ArchiveFailure?, partial: Boolean = false) =
                Listing.Listed(entries, error, format, filter, positions = jumpable(format, filter, positions), partial = partial, need = if (format in OWN_HEADERS) need else ListingNeed.All)
            while (true) {
                val status = archive.nextHeader()
                if (status == Status.EOF) return listed(null)
                if (status == Status.ENCRYPTED_HEADERS) return Listing.Encrypted
                if (!Status.succeeded(status)) return listed(archive.failure())
                val header = archive.header!!
                format = format ?: ArchiveFormat.of(header.format)
                filter = header.filter
                positions += header.position
                val entry = ArchiveEntry(
                    entryIndex = entries.size,
                    rawName = header.name ?: ByteArray(0),
                    declaredSize = header.size,
                    isDirectory = header.isDirectory,
                    encrypted = header.encrypted,
                    compression = if (format == ArchiveFormat.Zip) compressionOf(header.formatName) else null,
                    traditionalEncryption = format == ArchiveFormat.Zip && header.encrypted && compressionOf(header.formatName) != "aes",
                )
                entries += entry
                if (format in OWN_HEADERS && met(entry)) return listed(null, partial = true)
            }
        }

        /** Whether [need] is met once the walk has listed the entry it is given, after the ones before it. */
        private fun metBy(need: ListingNeed): (ArchiveEntry) -> Boolean = when (need) {
            ListingNeed.All -> { _ -> false }
            is ListingNeed.UpTo -> { entry -> entry.entryIndex >= need.entryIndex }
            is ListingNeed.Images -> {
                var found = 0
                { entry -> need.isImage(entry) && ++found >= need.count }
            }
        }

        /**
         * The header positions a reader may jump to: those of a format whose entries start from their own header,
         * read with no filter around it, when every header gave one and each comes after the one before.
         */
        private fun jumpable(format: ArchiveFormat?, filter: Int, positions: List<Long?>): LongArray? {
            if (format !in OWN_HEADERS || filter != 0 || positions.size < 2) return null
            val known = positions.filterNotNull()
            if (known.size != positions.size || known.zipWithNext().any { (a, b) -> b <= a }) return null
            return known.toLongArray()
        }

        private fun release(source: ByteSource, libarchive: Lazy<ArchiveHandle>, executor: ExecutorService) {
            executor.execute {
                if (libarchive.isInitialized()) libarchive.value.close()
                source.close()
            }
            executor.shutdown()
        }

        /** The result for an entry that is not worth reading, from what the index says about it. */
        internal fun refusal(entry: ArchiveEntry, maxBytes: Int): EntryResult? {
            val compression = entry.compression
            val declared = entry.declaredSize
            return when {
                entry.encrypted && !entry.traditionalEncryption -> EntryResult.Encrypted
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

/** Collects one entry, or just its prefix; prefix mode stops successfully at [limit] bytes. */
internal class EntryBytes(private val limit: Int, private val prefix: Boolean = false) : DataSink {
    private var bytes = ByteArray(min(INITIAL_CAPACITY, limit))
    private var size = 0

    /** The end of the block that would have passed the limit, or null while within it. */
    var exceeded: Long? = null
        private set

    val prefixComplete: Boolean get() = prefix && size == limit

    override fun write(block: ByteBuffer, offset: Long): Boolean {
        if (prefix) {
            if (offset >= limit) return false
            block.limit(block.position() + minOf(block.remaining(), limit - offset.toInt()))
        }
        val end = offset + block.remaining()
        if (end > limit) {
            exceeded = end
            return false
        }
        if (end > bytes.size) bytes = bytes.copyOf(max(end, min(bytes.size * 2L, limit.toLong())).toInt())
        block.get(bytes, offset.toInt(), block.remaining())
        size = max(size, end.toInt())
        return !prefixComplete
    }

    fun toByteArray(): ByteArray = if (size == bytes.size) bytes else bytes.copyOf(size)

    private companion object {
        const val INITIAL_CAPACITY = 64 * 1024
    }
}
