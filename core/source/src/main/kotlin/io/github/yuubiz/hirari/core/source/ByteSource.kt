package io.github.yuubiz.hirari.core.source

import android.os.ParcelFileDescriptor
import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * Random-access bytes of one file, whatever it is read from (docs/spec.md section 3.1). Reads block the calling
 * thread, because libarchive calls [read] from inside a native call.
 */
interface ByteSource : Closeable {
    val size: Long

    /**
     * Reads into [dst] from its position up to its limit, starting at [offset] in the file, and advances the
     * position of [dst]. Returns the number of bytes read, which is 0 only when [dst] has no room, or -1 when
     * [offset] is at or past the end.
     *
     * @throws IOException when the underlying read fails.
     */
    fun read(offset: Long, dst: ByteBuffer): Int
}

/** A [ByteSource] over [channel], which it closes. Positional reads leave the channel's position alone. */
class FileChannelSource(private val channel: FileChannel) : ByteSource {
    override val size: Long = channel.size()

    override fun read(offset: Long, dst: ByteBuffer): Int {
        require(offset >= 0) { "negative offset $offset" }
        if (offset >= size) return -1
        return channel.read(dst, offset)
    }

    override fun close() = channel.close()
}

/** A [ByteSource] that owns [this] and closes it when the source closes. */
fun ParcelFileDescriptor.asByteSource(): ByteSource =
    FileChannelSource(ParcelFileDescriptor.AutoCloseInputStream(this).channel)
