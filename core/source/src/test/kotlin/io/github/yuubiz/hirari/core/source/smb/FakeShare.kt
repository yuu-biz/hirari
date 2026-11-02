package io.github.yuubiz.hirari.core.source.smb

import java.io.FileNotFoundException
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

/** An in-memory share. [breakConnection] makes every later request on it fail as a dropped connection does. */
internal class FakeShare(private val files: Map<String, ByteArray>, private val onRead: () -> Unit = {}) : ShareConnection {
    @Volatile
    var broken = false
        private set
    val reads = AtomicInteger()
    val opens = AtomicInteger()

    fun breakConnection() {
        broken = true
    }

    private fun ensureOpen() {
        if (broken) throw ConnectionLostException("fake connection dropped")
    }

    override fun list(path: String): List<SmbEntry> {
        ensureOpen()
        return files.keys.filter { it.substringBeforeLast('/', "") == path }.map {
            SmbEntry(it.substringAfterLast('/'), it, false, files.getValue(it).size.toLong(), Instant.EPOCH)
        }
    }

    override fun open(path: String): RemoteFile {
        ensureOpen()
        val bytes = files[path] ?: throw FileNotFoundException("STATUS_OBJECT_NAME_NOT_FOUND")
        opens.incrementAndGet()
        return object : RemoteFile {
            override val size = bytes.size.toLong()

            override fun read(offset: Long, dst: ByteArray, dstOffset: Int, length: Int): Int {
                ensureOpen()
                reads.incrementAndGet()
                onRead()
                if (offset >= bytes.size) return -1
                val n = minOf(length.toLong(), bytes.size - offset).toInt()
                bytes.copyInto(dst, dstOffset, offset.toInt(), offset.toInt() + n)
                return n
            }

            override fun closeNow() {}
        }
    }

    override fun closeNow() {
        broken = true
    }
}

/** Hands out [FakeShare]s, failing the next [failures] attempts as an unreachable server does. */
internal class FakeConnector(private val files: Map<String, ByteArray>, private val onRead: () -> Unit = {}) : ShareConnector {
    val shares = mutableListOf<FakeShare>()
    var failures = 0
    val attempts = AtomicInteger()

    val current: FakeShare get() = shares.last()

    override fun connect(): ShareConnection {
        attempts.incrementAndGet()
        if (failures > 0) {
            failures--
            throw SmbConnectException(SmbConnectFailure.Unreachable, "fake")
        }
        return FakeShare(files, onRead).also { synchronized(shares) { shares += it } }
    }
}
