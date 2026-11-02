package io.github.yuubiz.hirari.core.source.smb

import io.github.yuubiz.hirari.core.source.ByteSource
import java.nio.ByteBuffer

/** The bytes [SmbByteSource] fetches per request and keeps for the reads that follow (docs/spec.md section 3.1). */
const val SMB_READ_BLOCK = 128 * 1024

/**
 * A file on an SMB share as a [ByteSource] (docs/spec.md section 3.1). Fetches aligned blocks of [SMB_READ_BLOCK]
 * bytes and serves the small reads of libarchive from the last one. After the server reconnects, the next read
 * opens the file again on the new connection.
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

    private var file: RemoteFile? = file
    private var fileGeneration = generation
    private val block = ByteArray(SMB_READ_BLOCK)
    private var blockStart = 0L
    private var blockLength = 0
    private var closed = false

    @Synchronized
    override fun read(offset: Long, dst: ByteBuffer): Int {
        check(!closed) { "the source is closed" }
        require(offset >= 0) { "negative offset $offset" }
        if (offset >= size) return -1
        if (!dst.hasRemaining()) return 0
        if (offset !in blockStart..<blockStart + blockLength) fill(offset - offset % SMB_READ_BLOCK)
        if (offset !in blockStart..<blockStart + blockLength) return -1
        val from = (offset - blockStart).toInt()
        val count = minOf(dst.remaining(), blockLength - from)
        dst.put(block, from, count)
        return count
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        file?.let { server.closeLater(it, fileGeneration) }
        file = null
    }

    private fun fill(start: Long) {
        blockLength = 0
        val wanted = minOf(SMB_READ_BLOCK.toLong(), size - start).toInt()
        var got = 0
        server.request { link ->
            val remote = fileOn(link)
            while (got < wanted) {
                val n = remote.read(start + got, block, got, wanted - got)
                if (n <= 0) break
                got += n
            }
        }
        blockStart = start
        blockLength = got
    }

    private fun fileOn(link: SmbServer.Link): RemoteFile {
        val current = file
        if (current != null && fileGeneration == link.generation) return current
        return link.connection.open(path).also {
            file = it
            fileGeneration = link.generation
        }
    }
}
