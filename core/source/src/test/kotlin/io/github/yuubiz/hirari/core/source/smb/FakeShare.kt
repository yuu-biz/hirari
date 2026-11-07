package io.github.yuubiz.hirari.core.source.smb

import java.io.FileNotFoundException
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

/** An offset past what the first small read of a file fetches, so that reading there takes another request. */
internal const val FAR_OFFSET = 128 * 1024

/**
 * An in-memory share. [breakConnection] makes every later request on it fail as a dropped connection does; [end] also
 * makes it report itself closed, as a transport that saw its socket reset does.
 */
internal class FakeShare(files: Map<String, ByteArray>, private val onRead: () -> Unit = {}) : ShareConnection {
    private val files = files.toMutableMap()
    private val directories = files.keys.flatMap { path ->
        generateSequence(path.substringBeforeLast('/', "")) { it.substringBeforeLast('/', "").takeIf(String::isNotEmpty) }.filter(String::isNotEmpty).toList()
    }.toMutableSet()
    val deleted = mutableListOf<String>()
    var refuseDelete: String? = null
    @Volatile
    var broken = false
        private set

    @Volatile
    private var ended = false
    val reads = AtomicInteger()
    val opens = AtomicInteger()

    /** Where each read started and how much it asked for. */
    val requested: MutableList<Pair<Long, Int>> = java.util.Collections.synchronizedList(mutableListOf())

    override val isOpen: Boolean get() = !ended

    fun breakConnection() {
        broken = true
    }

    fun end() {
        ended = true
        broken = true
    }

    private fun ensureOpen() {
        if (broken) throw ConnectionLostException("fake connection dropped")
    }

    override fun list(path: String): List<SmbEntry> {
        ensureOpen()
        return files.keys.filter { it.substringBeforeLast('/', "") == path }.map {
            SmbEntry(it.substringAfterLast('/'), it, false, files.getValue(it).size.toLong(), Instant.EPOCH)
        } + directories.filter { it.substringBeforeLast('/', "") == path }.map { SmbEntry(it.substringAfterLast('/'), it, true, 0, Instant.EPOCH) }
    }

    override fun rename(path: String, destination: String) {
        ensureOpen()
        if (destination in files || destination in directories) throw SmbRequestException("STATUS_OBJECT_NAME_COLLISION")
        if (path !in files && path !in directories) throw FileNotFoundException()
        fun within(value: String) = value == path || value.startsWith("$path/")
        val movedFiles = files.filterKeys(::within)
        movedFiles.keys.forEach(files::remove)
        movedFiles.forEach { (key, value) -> files[destination + key.removePrefix(path)] = value }
        val movedDirectories = directories.filter(::within)
        directories.removeAll(movedDirectories.toSet())
        directories.addAll(movedDirectories.map { destination + it.removePrefix(path) })
    }

    override fun delete(path: String, directory: Boolean) {
        ensureOpen()
        if (path == refuseDelete) throw SmbRequestException("STATUS_ACCESS_DENIED")
        if (directory) {
            check(files.keys.none { it.startsWith("$path/") } && directories.none { it.startsWith("$path/") })
            if (!directories.remove(path)) throw FileNotFoundException()
        } else if (files.remove(path) == null) throw FileNotFoundException()
        deleted += path
    }

    override fun open(path: String): RemoteFile {
        ensureOpen()
        val bytes = files[path] ?: throw FileNotFoundException("STATUS_OBJECT_NAME_NOT_FOUND")
        opens.incrementAndGet()
        return object : RemoteFile {
            override val size = bytes.size.toLong()

            override val modified: Instant = Instant.EPOCH

            override fun read(offset: Long, dst: ByteArray, dstOffset: Int, length: Int): Int {
                ensureOpen()
                reads.incrementAndGet()
                requested += offset to length
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
