package io.github.yuubiz.hirari.feature.reader

import io.github.yuubiz.hirari.core.model.LibraryLocation
import io.github.yuubiz.hirari.core.source.ByteSource
import io.github.yuubiz.hirari.core.source.smb.SmbConnectionState
import io.github.yuubiz.hirari.core.source.smb.SmbServer
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong

/**
 * The servers of the SMB places (docs/spec.md sections 3.1 and 6.5). The app keeps one per server and share for the
 * whole process, so the reader shares the connection with the library's listings.
 */
fun interface SmbServers {
    /** The server of [location], or `null` when its password cannot be read, as after a restore to another device. */
    suspend fun serverOf(location: LibraryLocation.SmbShare): SmbServer?

    companion object {
        val None = SmbServers { null }
    }
}

/** Whether the connection came back after breaking, which retries the pages it lost once (docs/spec.md section 6.5). */
fun reconnected(before: SmbConnectionState?, after: SmbConnectionState?): Boolean =
    after == SmbConnectionState.Connected && (before is SmbConnectionState.Reconnecting || before == SmbConnectionState.Lost)

/** Whether 「再接続しています」 shows (docs/spec.md section 4.8, 重ねて表示する情報). */
val SmbConnectionState?.reconnecting: Boolean
    get() = this is SmbConnectionState.Reconnecting || this == SmbConnectionState.Lost

/**
 * Counts the bytes a book reads from its server, for 取得の状況 (docs/spec.md section 4.8). The archive session
 * reads on its own thread and the screen samples [bytes] on the main thread.
 */
class FetchMeter {
    private val total = AtomicLong()

    val bytes: Long get() = total.get()

    /** [source] with every byte it reads counted here. */
    fun counted(source: ByteSource): ByteSource = object : ByteSource {
        override val size: Long get() = source.size

        override fun read(offset: Long, dst: ByteBuffer): Int = source.read(offset, dst).also { if (it > 0) total.addAndGet(it.toLong()) }

        override fun close() = source.close()
    }
}

/** The meter's count at [atMs]. */
data class FetchSample(val atMs: Long, val bytes: Long)

/**
 * Where the page being waited for comes from (docs/spec.md section 4.8, 取得の状況): the raw data in memory, the SMB
 * prefetch layer on disk (`cacheDir/smb/`), or the server.
 */
enum class FetchSource { Memory, Cache, Server }

/**
 * What the pages of a view still need: [fromServer] bytes, from the sizes the book declares for the pages neither in
 * memory nor on disk, and whether [fromDisk] some of them are read from the SMB prefetch layer.
 */
data class FetchNeed(val fromServer: Long, val fromDisk: Boolean)

/** `45% 820 KB/s`: [percent] of what the view still needs, and the rate since the last sample. */
data class FetchProgress(val source: FetchSource, val percent: Int, val bytesPerSecond: Long)

/**
 * The progress of the view being waited for, from the samples taken since it started waiting ([start]) and the
 * previous one. An entry's compressed size is not known, so the share of [need] stops at 99% until the view is ready.
 * A view that needs nothing from the server is read from disk, or, with its data all in memory, only decoding.
 */
fun fetchProgress(start: FetchSample, previous: FetchSample, now: FetchSample, need: FetchNeed): FetchProgress {
    val expected = need.fromServer
    if (expected <= 0) return FetchProgress(if (need.fromDisk) FetchSource.Cache else FetchSource.Memory, 99, 0)
    val percent = ((now.bytes - start.bytes) * 100 / expected).coerceIn(0, 99).toInt()
    val elapsed = now.atMs - previous.atMs
    val rate = if (elapsed > 0) (now.bytes - previous.bytes) * 1000 / elapsed else 0
    return FetchProgress(FetchSource.Server, percent, rate.coerceAtLeast(0))
}
