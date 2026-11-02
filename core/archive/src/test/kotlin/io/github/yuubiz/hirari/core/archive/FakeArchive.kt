package io.github.yuubiz.hirari.core.archive

import io.github.yuubiz.hirari.core.source.ByteSource
import kotlinx.coroutines.Job
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger

/**
 * The bytes of [entries] laid end to end, read through [source] the way libarchive reads an archive: forward
 * from [open], one header after another, and the data of the current entry in blocks. Callbacks go through
 * [SourceReads] as libarchive's do, so a failing [source] fails the same way.
 */
internal class FakeArchive(
    private val source: ByteSource,
    private val entries: List<Pair<String, ByteArray>>,
    private val format: ArchiveFormat = ArchiveFormat.SevenZip,
) : ArchiveHandle {
    private val reads = SourceReads(source)
    private val block = ByteBuffer.allocate(BLOCK)
    private var next = 0

    /** How many times the archive was opened, listing included. */
    val opens = AtomicInteger()

    override var header: Header? = null
        private set

    override fun begin(job: Job) = reads.begin(job)

    override fun open(): Int {
        opens.incrementAndGet()
        next = 0
        return Status.OK
    }

    override fun nextHeader(): Int {
        header = null
        if (next >= entries.size) return Status.EOF
        val (name, data) = entries[next++]
        header = Header(name.toByteArray(), data.size.toLong(), false, false, "fake", format.code, 0)
        return Status.OK
    }

    override fun readData(sink: DataSink): Int {
        val index = next - 1
        val start = entries.take(index).sumOf { it.second.size.toLong() }
        val size = entries[index].second.size
        var done = 0
        while (done < size) {
            val n = reads.read(start + done, block, minOf(BLOCK, size - done))
            if (n <= 0) return Status.FAILED
            block.flip()
            if (reads.stopIfCancelled() || !sink.write(block, done.toLong())) return Status.STOPPED
            done += n
        }
        return Status.OK
    }

    override fun failure(): ArchiveFailure = reads.failure()

    override fun close() = source.close()

    private companion object {
        const val BLOCK = 1024
    }
}

/** [bytes] as a [ByteSource] that runs [beforeRead] before every read, which may throw or wait. */
internal class MemorySource(private val bytes: ByteArray, private val beforeRead: (offset: Long) -> Unit = {}) : ByteSource {
    val closes = AtomicInteger()

    override val size: Long = bytes.size.toLong()

    override fun read(offset: Long, dst: ByteBuffer): Int {
        beforeRead(offset)
        if (offset >= size) return -1
        val count = minOf(dst.remaining().toLong(), size - offset).toInt()
        dst.put(bytes, offset.toInt(), count)
        return count
    }

    override fun close() {
        closes.incrementAndGet()
    }
}

/** [size] lowercase letters that differ with [seed]. */
internal fun letters(size: Int, seed: Int) = ByteArray(size) { ('a'.code + (it * 7 + seed * 11) % 26).toByte() }
