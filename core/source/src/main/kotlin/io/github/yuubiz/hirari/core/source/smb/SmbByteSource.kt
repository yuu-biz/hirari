package io.github.yuubiz.hirari.core.source.smb

import io.github.yuubiz.hirari.core.source.ByteSource
import java.nio.ByteBuffer
import java.time.Instant

/** The least one fetch takes: a small read, such as one header, gets this much for the reads near it (3.1). */
const val SMB_MIN_FETCH = 16 * 1024

/** What an [SmbByteSource] asked of the server: one request per SMB READ, and the bytes they returned. */
data class SmbTraffic(val requests: Int, val bytes: Long)

/**
 * A file on an SMB share as a [ByteSource] (docs/spec.md section 3.1). Each fetch from the server is as large as the
 * read that needs it: a small read fetches [SMB_MIN_FETCH], a run of small reads one after another fetches twice as
 * much each time, and a larger read fetches exactly what it asks for. The last few fetched ranges stay in memory,
 * so reads that go back and forth between the head, the tail and the place being read do not fetch again. After the
 * server reconnects, the next fetch opens the file again on the new connection.
 *
 * [read] throws [ConnectionLostException] when the connection broke, timed out, or is being re-established.
 */
class SmbByteSource internal constructor(
    private val server: SmbServer,
    private val path: String,
    file: RemoteFile,
    generation: Long,
) : ByteSource {
    override val size: Long = file.size

    /** The file's last write time when it was opened. */
    val modified: Instant = file.modified

    private var file: RemoteFile? = file
    private var fileGeneration = generation

    /** Most recently used first. */
    private val windows = ArrayDeque<Window>()
    private var requests = 0
    private var bytes = 0L
    private var closed = false

    /** The traffic so far, which the cover target of docs/spec.md section 5.2 counts. */
    val traffic: SmbTraffic
        @Synchronized get() = SmbTraffic(requests, bytes)

    private class Window(val start: Long, val bytes: ByteArray, val length: Int) {
        val end: Long get() = start + length

        operator fun contains(offset: Long) = offset >= start && offset < end
    }

    @Synchronized
    override fun read(offset: Long, dst: ByteBuffer): Int {
        check(!closed) { "the source is closed" }
        require(offset >= 0) { "negative offset $offset" }
        if (offset >= size) return -1
        if (!dst.hasRemaining()) return 0
        val window = windows.firstOrNull { offset in it } ?: fetch(offset, dst.remaining()) ?: return -1
        windows.remove(window)
        windows.addFirst(window)
        val from = (offset - window.start).toInt()
        val count = minOf(dst.remaining(), window.length - from)
        dst.put(window.bytes, from, count)
        return count
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        windows.clear()
        file?.let { server.closeLater(it, fileGeneration) }
        file = null
    }

    /** Fetches the range a read of [wanted] bytes at [offset] needs, or null when the server has nothing there. */
    private fun fetch(offset: Long, wanted: Int): Window? {
        val previous = windows.firstOrNull { it.end == offset }
        val length = when {
            wanted > PIECE -> minOf(wanted, MAX_FETCH)
            previous != null -> maxOf(wanted, minOf(previous.length * 2, MAX_READ_AHEAD))
            else -> maxOf(wanted, SMB_MIN_FETCH)
        }.toLong().coerceAtMost(size - offset).toInt()
        previous?.let(windows::remove)
        val buffer = previous?.bytes?.takeIf { it.size >= length } ?: ByteArray(length)
        var got = 0
        server.request { link ->
            val remote = fileOn(link)
            while (got < length) {
                val n = remote.read(offset + got, buffer, got, length - got)
                requests++
                if (n <= 0) break
                got += n
                bytes += n
            }
        }
        if (got == 0) return null
        val window = Window(offset, buffer, got)
        windows.addFirst(window)
        while (windows.size > MAX_WINDOWS || (windows.size > 1 && windows.sumOf { it.bytes.size.toLong() } > WINDOW_BYTES)) windows.removeLast()
        return window
    }

    private fun fileOn(link: SmbServer.Link): RemoteFile {
        val current = file
        if (current != null && fileGeneration == link.generation) return current
        return link.connection.open(path).also {
            file = it
            fileGeneration = link.generation
        }
    }

    private companion object {
        /** Reads of up to this much are pieces of a longer read, as libarchive's 4 KB and 64 KB reads are (3.3). */
        const val PIECE = 64 * 1024

        /** The most a run of pieces fetches at once. */
        const val MAX_READ_AHEAD = 1 shl 20

        /** The most one fetch takes for a larger read, which then reads the rest with the next fetch. */
        const val MAX_FETCH = 4 shl 20

        /** Enough for the head, the tail, the place being read and one more. */
        const val MAX_WINDOWS = 4

        /** What the windows may hold together. The newest window stays whatever its size. */
        const val WINDOW_BYTES = 2L shl 20
    }
}
