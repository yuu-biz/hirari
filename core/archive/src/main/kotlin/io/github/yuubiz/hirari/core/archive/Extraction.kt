package io.github.yuubiz.hirari.core.archive

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs
import kotlin.math.min

/**
 * The extraction cache of a solid archive (docs/spec.md section 3.3). A pass on the session thread opens the
 * archive and extracts the entries in order into files in [dir], one file per entry named by its index. A read
 * waits until the pass has reached its entry. When the files pass the size limit, the ones farthest in archive
 * order from the entry read last are deleted, and reading one of those again starts another pass from the top.
 *
 * Files are written under a temporary name and renamed when complete, so a file named by an index is always a
 * whole entry, and two sessions of one book (the reader's and the thumbnail's) can share [dir].
 */
internal class Extraction(
    private val archive: NativeArchive,
    private val dispatcher: CoroutineDispatcher,
    private val entries: List<ArchiveEntry>,
    private val dir: File,
    private val clock: () -> Long = System::currentTimeMillis,
) : ArchiveSession.EntryReader {
    private sealed interface Slot {
        /** The pass has not reached the entry yet. */
        data object Pending : Slot

        /** The entry was extracted and its file deleted to stay within the limit. */
        data object Evicted : Slot

        class Cached(val file: File, val size: Long) : Slot

        /** Reading the entry failed or was refused, which another pass would repeat. */
        class Settled(val result: EntryResult) : Slot
    }

    private val lock = Any()
    private val slots = Array<Slot>(entries.size) { Slot.Pending }
    private var anchor = 0
    private var cachedBytes = 0L
    private var limit = MAX_CACHE_BYTES

    /** Counts the changes to [slots], so that a read can wait for the next one. */
    private val changes = MutableStateFlow(0L)

    private val passes = Channel<Unit>(Channel.CONFLATED)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    init {
        scope.launch {
            archive.begin(coroutineContext.job)
            prepare()
            if (synchronized(lock) { slots.any { it !is Slot.Cached } }) passes.trySend(Unit)
            for (request in passes) {
                archive.begin(coroutineContext.job)
                try {
                    pass()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    settleFrom(0, ArchiveFailure.ReadError)
                } catch (_: OutOfMemoryError) {
                    settleFrom(0, ArchiveFailure.ReadError)
                }
                touch()
            }
        }
    }

    override suspend fun read(entry: ArchiveEntry, maxBytes: Int): EntryResult {
        val index = entry.entryIndex
        synchronized(lock) { anchor = index }
        while (true) {
            val seen = changes.value
            if (!scope.isActive) throw CancellationException("the session is closed")
            when (val slot = synchronized(lock) { slots[index] }) {
                is Slot.Settled -> return slot.result
                is Slot.Cached -> readFile(slot, maxBytes)?.let { return it } ?: evict(index, slot)
                Slot.Evicted -> passes.trySend(Unit)
                Slot.Pending -> Unit
            }
            changes.first { it != seen }
        }
    }

    override fun close() {
        scope.cancel()
        changes.update { it + 1 }
        dispatcher.dispatch(EmptyCoroutineContext) { touch() }
    }

    private suspend fun readFile(slot: Slot.Cached, maxBytes: Int): EntryResult? = withContext(Dispatchers.IO) {
        if (slot.size > maxBytes) return@withContext EntryResult.TooLarge(slot.size)
        try {
            EntryResult.Data(slot.file.readBytes())
        } catch (_: IOException) {
            null
        }
    }

    /** Takes in the files earlier sessions left, and deletes the caches of books closed long ago. */
    private fun prepare() {
        dir.mkdirs()
        touch()
        val now = clock()
        dir.parentFile?.listFiles()?.forEach { other ->
            if (other != dir && other.isDirectory && now - other.lastModified() > IDLE_MILLIS) other.deleteRecursively()
        }
        dir.listFiles()?.forEach { if (it.name.endsWith(PART) && now - it.lastModified() > PART_MILLIS) it.delete() }
        limit = min(dir.usableSpace / 10, MAX_CACHE_BYTES)
        change {
            for (i in slots.indices) {
                val file = File(dir, i.toString())
                if (slots[i] == Slot.Pending && file.isFile) cache(i, file)
            }
        }
    }

    private fun pass() {
        var next = 0
        var open = false
        while (next < entries.size && needsPass(next)) {
            if (!open) {
                if (!Status.succeeded(archive.open())) return settleFrom(next, archive.failure())
                repeat(next) {
                    val status = archive.nextHeader()
                    if (!Status.succeeded(status)) return settleFrom(next, headerFailure(status))
                }
                open = true
            }
            val status = archive.nextHeader()
            if (!Status.succeeded(status)) return settleFrom(next, headerFailure(status))
            val slot = synchronized(lock) { slots[next] }
            val dataStatus = if (slot == Slot.Pending || slot == Slot.Evicted) extract(next) else Status.OK
            if (dataStatus < Status.FAILED || dataStatus == Status.PPMD) open = false
            next++
        }
    }

    private fun headerFailure(status: Int): ArchiveFailure = if (status == Status.EOF) ArchiveFailure.Corrupt else archive.failure()

    private fun needsPass(from: Int): Boolean = synchronized(lock) {
        (from until slots.size).any { slots[it] == Slot.Pending || slots[it] == Slot.Evicted }
    }

    /** Extracts the entry at [index] into its file and returns libarchive's status. */
    private fun extract(index: Int): Int {
        ArchiveSession.refusal(entries[index], ArchiveSession.MAX_ENTRY_BYTES)?.let {
            change { slots[index] = Slot.Settled(it) }
            return Status.OK
        }
        val part = File.createTempFile("entry-$index-", PART, dir)
        val sink = FileSink(part, ArchiveSession.MAX_ENTRY_BYTES.toLong())
        val status = try {
            archive.readData(sink)
        } finally {
            sink.close()
        }
        val file = File(dir, index.toString())
        val exceeded = sink.exceeded
        val slot = when {
            status == Status.OK && sink.failure == null && part.renameTo(file) -> null
            status == Status.PPMD -> Slot.Settled(EntryResult.UnsupportedCompression(PPMD))
            exceeded != null -> Slot.Settled(EntryResult.TooLarge(exceeded))
            status == Status.OK || sink.failure != null -> Slot.Settled(EntryResult.Failed(ArchiveFailure.ReadError))
            else -> Slot.Settled(EntryResult.Failed(archive.failure()))
        }
        if (slot != null) part.delete()
        change {
            if (slot == null) {
                cache(index, file)
                evictOverLimit()
            } else {
                slots[index] = slot
            }
        }
        return status
    }

    private fun settleFrom(from: Int, failure: ArchiveFailure) = change {
        for (i in from until slots.size) {
            if (slots[i] == Slot.Pending || slots[i] == Slot.Evicted) slots[i] = Slot.Settled(EntryResult.Failed(failure))
        }
    }

    private fun evict(index: Int, slot: Slot.Cached) {
        change {
            if (slots[index] === slot) {
                slots[index] = Slot.Evicted
                cachedBytes -= slot.size
            }
        }
        passes.trySend(Unit)
    }

    // The two below run under [lock].

    private fun cache(index: Int, file: File) {
        val size = file.length()
        slots[index] = Slot.Cached(file, size)
        cachedBytes += size
    }

    private fun evictOverLimit() {
        while (cachedBytes > limit) {
            val farthest = slots.indices.filter { slots[it] is Slot.Cached }.maxByOrNull { abs(it - anchor) } ?: return
            val slot = slots[farthest] as Slot.Cached
            slot.file.delete()
            slots[farthest] = Slot.Evicted
            cachedBytes -= slot.size
        }
    }

    private inline fun change(block: () -> Unit) {
        synchronized(lock) { block() }
        changes.update { it + 1 }
    }

    private fun touch() {
        dir.setLastModified(clock())
    }

    /** Writes the data of one entry into [file], stopping once it would pass [limit] bytes. */
    private class FileSink(file: File, private val limit: Long) : DataSink, AutoCloseable {
        private val channel = RandomAccessFile(file, "rw").channel

        var exceeded: Long? = null
            private set

        var failure: IOException? = null
            private set

        override fun write(block: ByteBuffer, offset: Long): Boolean {
            val end = offset + block.remaining()
            if (end > limit) {
                exceeded = end
                return false
            }
            return try {
                var at = offset
                while (block.hasRemaining()) at += channel.write(block, at)
                true
            } catch (e: IOException) {
                failure = e
                false
            }
        }

        override fun close() = channel.close()
    }

    private companion object {
        /** The size limit is the smaller of this and a tenth of the free space (docs/spec.md section 3.3). */
        const val MAX_CACHE_BYTES = 2L shl 30
        const val IDLE_MILLIS = 24L * 60 * 60 * 1000
        const val PART_MILLIS = 60L * 60 * 1000
        const val PART = ".part"
    }
}
