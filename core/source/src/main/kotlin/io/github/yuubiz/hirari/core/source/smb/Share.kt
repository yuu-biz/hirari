package io.github.yuubiz.hirari.core.source.smb

import java.time.Instant

/**
 * The transport under [SmbServer]: one authenticated connection to one share. smbj implements it in
 * [SmbjConnector]; the JVM tests use a fake. Every method blocks.
 *
 * Failures are thrown as the public types: [ConnectionLostException] when the connection is no longer usable,
 * [java.io.FileNotFoundException] when the path is missing, [SmbRequestException] for the other refusals, and
 * [SmbConnectException] from [ShareConnector.connect].
 */
internal fun interface ShareConnector {
    fun connect(): ShareConnection
}

internal interface ShareConnection {
    /** The children of the folder at [path], without `.` and `..`. */
    fun list(path: String): List<SmbEntry>

    fun open(path: String): RemoteFile

    /** Drops the connection without waiting on the server, which may have stopped answering. */
    fun closeNow()
}

internal interface RemoteFile {
    val size: Long

    /** The last write time, to the millisecond, as a listing of its folder reports it. */
    val modified: Instant

    /** Reads up to [length] bytes at [offset] into [dst] from [dstOffset]. Returns the count, or -1 at the end. */
    fun read(offset: Long, dst: ByteArray, dstOffset: Int, length: Int): Int

    fun closeNow()
}
