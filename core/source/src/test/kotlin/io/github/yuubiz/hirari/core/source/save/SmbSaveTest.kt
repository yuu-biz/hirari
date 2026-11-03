package io.github.yuubiz.hirari.core.source.save

import io.github.yuubiz.hirari.core.source.smb.ConnectionLostException
import io.github.yuubiz.hirari.core.source.smb.RemoteFile
import io.github.yuubiz.hirari.core.source.smb.SMB_READ_BLOCK
import io.github.yuubiz.hirari.core.source.smb.ShareConnection
import io.github.yuubiz.hirari.core.source.smb.ShareConnector
import io.github.yuubiz.hirari.core.source.smb.SmbConnectException
import io.github.yuubiz.hirari.core.source.smb.SmbConnectFailure
import io.github.yuubiz.hirari.core.source.smb.SmbEntry
import io.github.yuubiz.hirari.core.source.smb.SmbLogin
import io.github.yuubiz.hirari.core.source.smb.SmbServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.FileNotFoundException
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

/** An in-memory share with folders. [onRead] runs before each read and may throw as a dropped connection does. */
private class TreeShare(private val files: Map<String, ByteArray>, private val onRead: () -> Unit = {}) : ShareConnection {
    @Volatile
    var broken = false

    @Volatile
    override var isOpen = true

    override fun list(path: String): List<SmbEntry> {
        if (broken) throw ConnectionLostException("fake connection dropped")
        val prefix = if (path.isEmpty()) "" else "$path/"
        val below = files.keys.filter { it.startsWith(prefix) }
        if (path.isNotEmpty() && below.isEmpty()) throw FileNotFoundException("STATUS_OBJECT_NAME_NOT_FOUND")
        val folders = below.map { it.removePrefix(prefix) }.filter { '/' in it }.map { it.substringBefore('/') }.distinct()
        val direct = below.filter { '/' !in it.removePrefix(prefix) }
        return folders.map { SmbEntry(it, prefix + it, true, 0, Instant.EPOCH) } +
            direct.map { SmbEntry(it.substringAfterLast('/'), it, false, files.getValue(it).size.toLong(), Instant.EPOCH) }
    }

    override fun open(path: String): RemoteFile {
        if (broken) throw ConnectionLostException("fake connection dropped")
        val bytes = files[path] ?: throw FileNotFoundException("STATUS_OBJECT_NAME_NOT_FOUND")
        return object : RemoteFile {
            override val size = bytes.size.toLong()
            override val modified: Instant = Instant.EPOCH

            override fun read(offset: Long, dst: ByteArray, dstOffset: Int, length: Int): Int {
                if (broken) throw ConnectionLostException("fake connection dropped")
                onRead()
                if (broken) throw ConnectionLostException("fake connection dropped")
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

/** A folder in memory, safe to share between saves. [log] gets every file created, so a test sees the `.part` names. */
private class MemoryFolder(
    val files: MutableMap<String, ByteArray> = linkedMapOf(),
    val folders: MutableMap<String, MemoryFolder> = linkedMapOf(),
    val log: MutableList<String> = mutableListOf(),
) : SaveFolder {
    var free: Long? = null
    var fullAfter: Long = Long.MAX_VALUE
    var onWrite: () -> Unit = {}
    var failCreate: Throwable? = null
    var failCommit: Throwable? = null

    override fun names(): Set<String> = synchronized(log) { files.keys + folders.keys }

    override fun createFolder(name: String): SaveFolder = synchronized(log) {
        MemoryFolder(log = log)
    }.also {
        it.free = free
        it.fullAfter = fullAfter
        it.onWrite = onWrite
        it.failCreate = failCreate
        it.failCommit = failCommit
        synchronized(log) { folders[name] = it }
    }

    override fun create(name: String): SaveFile = synchronized(log) {
        failCreate?.let { throw it }
        log += name
        files[name] = ByteArray(0)
        val part = name
        return object : SaveFile {
            override val freeBytes: Long? = this@MemoryFolder.free
            private var written = 0L
            private var done = false

            override fun write(buffer: ByteArray, length: Int) {
                onWrite()
                if (written + length > fullAfter) throw NoRoomException()
                synchronized(log) { files[part] = files.getValue(part) + buffer.copyOf(length) }
                written += length
            }

            override fun commit(name: String) = synchronized(log) {
                failCommit?.let { throw it }
                done = true
                files[name] = files.remove(part) ?: throw SaveDestinationException("the part is gone")
            }

            override fun discard() {
                synchronized(log) { if (!done) files.remove(part) }
            }
        }
    }

    override fun delete(name: String) {
        synchronized(log) {
            files.remove(name)
            folders.remove(name)
        }
    }

    fun leftovers(): List<String> = files.keys.filter { it.endsWith(SAVE_PART_SUFFIX) } + folders.values.flatMap { it.leftovers() }
}

class SmbSaveTest {
    private val scope = CoroutineScope(SupervisorJob())
    private val pattern = ByteArray(2 * SMB_READ_BLOCK + 777) { (it * 31 + it / 97).toByte() }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun server(files: Map<String, ByteArray>, onRead: () -> Unit = {}, connector: ShareConnector = ShareConnector { TreeShare(files, onRead) }): SmbServer =
        SmbServer(connector, SmbLogin.Guest, scope, emptyFlow(), Dispatchers.IO, 15.seconds)

    private fun save(server: SmbServer, path: String, directory: Boolean, into: SaveFolder, progress: MutableList<SaveProgress> = mutableListOf()) =
        runBlocking { server.saveTo(path, directory, into) { progress += it } }

    @Test
    fun `a file is written as a part and renamed, with its progress reaching the whole`() {
        val into = MemoryFolder()
        val progress = mutableListOf<SaveProgress>()
        val outcome = save(server(mapOf("books/vol1.cbz" to pattern)), "books/vol1.cbz", false, into, progress)
        assertEquals(SaveOutcome.Saved("vol1.cbz", 1, pattern.size.toLong()), outcome)
        assertEquals(listOf("vol1.cbz.part"), into.log)
        assertArrayEquals(pattern, into.files.getValue("vol1.cbz"))
        assertEquals(listOf("vol1.cbz"), into.files.keys.toList())
        assertEquals(listOf(SMB_READ_BLOCK, 2 * SMB_READ_BLOCK, pattern.size).map { it.toLong() }, progress.drop(1).map { it.bytesDone }.distinct())
        assertEquals(100, progress.last().percent)
        assertEquals(pattern.size.toLong(), progress.last().bytesTotal)
        assertEquals(1, progress.last().filesDone)
        assertTrue(progress.map { it.percent }.zipWithNext().all { (a, b) -> a <= b })
    }

    @Test
    fun `a folder is copied with everything in it and its total is the sum of the listing`() {
        val files = mapOf(
            "shelf/series/vol1.zip" to pattern,
            "shelf/series/vol2.zip" to pattern.copyOf(1000),
            "shelf/series/extras/cover.jpg" to byteArrayOf(1, 2, 3),
            "shelf/series/extras/notes/readme.txt" to ByteArray(0),
            "shelf/other.zip" to byteArrayOf(9),
        )
        val into = MemoryFolder()
        val progress = mutableListOf<SaveProgress>()
        val outcome = save(server(files), "shelf/series", true, into, progress)
        assertEquals(SaveOutcome.Saved("series", 4, pattern.size + 1000L + 3), outcome)
        val series = into.folders.getValue("series")
        assertArrayEquals(pattern, series.files.getValue("vol1.zip"))
        assertArrayEquals(pattern.copyOf(1000), series.files.getValue("vol2.zip"))
        assertArrayEquals(byteArrayOf(1, 2, 3), series.folders.getValue("extras").files.getValue("cover.jpg"))
        assertArrayEquals(ByteArray(0), series.folders.getValue("extras").folders.getValue("notes").files.getValue("readme.txt"))
        assertTrue(into.leftovers().isEmpty())
        assertEquals(setOf("series"), into.names())
        assertEquals(pattern.size + 1000L + 3, progress.first().bytesTotal)
        assertEquals(4, progress.first().filesTotal)
        assertEquals(100, progress.last().percent)
        assertEquals(4, progress.last().filesDone)
    }

    @Test
    fun `an existing name makes the copy take the next free number before the extension`() {
        val into = MemoryFolder(files = linkedMapOf("vol1.cbz" to byteArrayOf(0), "vol1 (1).cbz" to byteArrayOf(0)))
        val outcome = save(server(mapOf("vol1.cbz" to pattern)), "vol1.cbz", false, into)
        assertEquals("vol1 (2).cbz", (outcome as SaveOutcome.Saved).name)
        assertEquals(listOf("vol1 (2).cbz.part"), into.log)
        assertArrayEquals(byteArrayOf(0), into.files.getValue("vol1.cbz"))
        assertArrayEquals(pattern, into.files.getValue("vol1 (2).cbz"))
    }

    @Test
    fun `a folder whose name is taken is copied into the next free name`() {
        val into = MemoryFolder(folders = linkedMapOf("series" to MemoryFolder()))
        val outcome = save(server(mapOf("series/a.zip" to byteArrayOf(5))), "series", true, into)
        assertEquals("series (1)", (outcome as SaveOutcome.Saved).name)
        assertTrue(into.folders.getValue("series").files.isEmpty())
        assertArrayEquals(byteArrayOf(5), into.folders.getValue("series (1)").files.getValue("a.zip"))
    }

    @Test
    fun `all ten names taken fails and writes nothing`() {
        val taken = linkedMapOf("vol1.cbz" to byteArrayOf(0)).also { map -> (1..9).forEach { map["vol1 ($it).cbz"] = byteArrayOf(0) } }
        val into = MemoryFolder(files = taken)
        val outcome = save(server(mapOf("vol1.cbz" to pattern)), "vol1.cbz", false, into)
        assertEquals(SaveOutcome.Failed(SaveFailure.NamesTaken), outcome)
        assertTrue(into.log.isEmpty())
        assertEquals(10, into.files.size)
    }

    @Test
    fun `names compare in NFC and without case`() {
        assertEquals("が (1).zip", freeName("が.zip", setOf("が.ZIP")))
        assertEquals("a (1)", freeName("a", setOf("A")))
        assertEquals(".hidden (1)", freeName(".hidden", setOf(".hidden")))
        assertEquals("archive.tar (1).gz", freeName("archive.tar.gz", setOf("archive.tar.gz")))
    }

    @Test
    fun `cancelling a copy deletes the part and leaves nothing`() {
        val into = MemoryFolder()
        val reading = CountDownLatch(2)
        val release = CountDownLatch(1)
        val server = server(mapOf("vol1.cbz" to pattern), onRead = {
            reading.countDown()
            if (reading.count == 0L) release.await(10, TimeUnit.SECONDS)
        })
        runBlocking {
            val job = async(Dispatchers.Default) { server.saveTo("vol1.cbz", false, into) }
            assertTrue(reading.await(10, TimeUnit.SECONDS))
            job.cancel()
            release.countDown()
            assertThrows(CancellationException::class.java) { runBlocking { job.await() } }
        }
        assertEquals(listOf("vol1.cbz.part"), into.log)
        assertTrue("the part and the file are gone: ${into.files.keys}", into.files.isEmpty())
    }

    @Test
    fun `cancelling while the last block is read deletes the part and commits nothing`() {
        val into = MemoryFolder()
        val reading = CountDownLatch(3)
        val release = CountDownLatch(1)
        val server = server(mapOf("vol1.cbz" to pattern), onRead = {
            reading.countDown()
            if (reading.count == 0L) release.await(10, TimeUnit.SECONDS)
        })
        runBlocking {
            val job = async(Dispatchers.Default) { server.saveTo("vol1.cbz", false, into) }
            assertTrue(reading.await(10, TimeUnit.SECONDS))
            job.cancel()
            release.countDown()
            assertThrows(CancellationException::class.java) { runBlocking { job.await() } }
        }
        assertEquals(listOf("vol1.cbz.part"), into.log)
        assertTrue("the part and the file are gone: ${into.files.keys}", into.files.isEmpty())
    }

    @Test
    fun `cancelling a folder keeps the files that are whole and deletes the one being written`() {
        val files = mapOf("series/a.zip" to byteArrayOf(1, 2), "series/b.zip" to pattern)
        val into = MemoryFolder()
        val writes = AtomicInteger()
        val gate = CountDownLatch(1)
        into.onWrite = {
            if (writes.incrementAndGet() == 2) {
                gate.countDown()
                Thread.sleep(200)
            }
        }
        runBlocking {
            val job = async(Dispatchers.Default) { server(files).saveTo("series", true, into) }
            assertTrue(gate.await(10, TimeUnit.SECONDS))
            job.cancel()
            assertThrows(CancellationException::class.java) { runBlocking { job.await() } }
        }
        val series = into.folders.getValue("series")
        assertEquals(setOf("a.zip"), series.files.keys)
        assertArrayEquals(byteArrayOf(1, 2), series.files.getValue("a.zip"))
        assertTrue(into.leftovers().isEmpty())
    }

    @Test
    fun `a connection that comes back during the copy lets it finish whole`() {
        val into = MemoryFolder()
        val reads = AtomicInteger()
        var connection: TreeShare? = null
        val connector = ShareConnector {
            TreeShare(mapOf("vol1.cbz" to pattern), onRead = {
                if (reads.incrementAndGet() == 2) connection?.broken = true
            }).also { connection = it }
        }
        val outcome = save(server(emptyMap(), connector = connector), "vol1.cbz", false, into)
        assertEquals(SaveOutcome.Saved("vol1.cbz", 1, pattern.size.toLong()), outcome)
        assertArrayEquals(pattern, into.files.getValue("vol1.cbz"))
        assertTrue(into.leftovers().isEmpty())
    }

    @Test
    fun `a connection that ended before the save is made again and the save finishes whole`() {
        val files = mapOf("series/a.zip" to byteArrayOf(1, 2), "series/b.zip" to pattern)
        val connections = mutableListOf<TreeShare>()
        val server = server(emptyMap(), connector = ShareConnector { TreeShare(files).also { connections += it } })
        runBlocking { server.list("series") }
        connections.last().apply {
            broken = true
            isOpen = false
        }
        val into = MemoryFolder()
        assertEquals(SaveOutcome.Saved("b.zip", 1, pattern.size.toLong()), save(server, "series/b.zip", false, into))
        assertArrayEquals(pattern, into.files.getValue("b.zip"))
        connections.last().apply {
            broken = true
            isOpen = false
        }
        assertEquals(SaveOutcome.Saved("series", 2, pattern.size + 2L), save(server, "series", true, into))
        assertArrayEquals(pattern, into.folders.getValue("series").files.getValue("b.zip"))
        assertTrue(into.leftovers().isEmpty())
        assertEquals(3, connections.size)
    }

    @Test
    fun `a connection that does not come back fails with the part deleted`() {
        val into = MemoryFolder()
        val reads = AtomicInteger()
        var connection: TreeShare? = null
        var fail = false
        val connector = ShareConnector {
            if (fail) throw SmbConnectException(SmbConnectFailure.Unreachable, "fake")
            TreeShare(mapOf("vol1.cbz" to pattern), onRead = {
                if (reads.incrementAndGet() == 2) {
                    fail = true
                    connection?.broken = true
                }
            }).also { connection = it }
        }
        val outcome = save(server(emptyMap(), connector = connector), "vol1.cbz", false, into)
        assertEquals(SaveOutcome.Failed(SaveFailure.ConnectionLost), outcome)
        assertEquals(listOf("vol1.cbz.part"), into.log)
        assertTrue(into.files.isEmpty())
    }

    @Test
    fun `a file that is gone from the server fails as not found`() {
        val into = MemoryFolder()
        assertEquals(SaveOutcome.Failed(SaveFailure.NotFound), save(server(mapOf("a" to byteArrayOf(1))), "missing.zip", false, into))
        assertEquals(SaveOutcome.Failed(SaveFailure.NotFound), save(server(mapOf("a/b" to byteArrayOf(1))), "gone", true, into))
        assertTrue(into.log.isEmpty())
    }

    @Test
    fun `a destination with less free space than the file refuses before writing`() {
        val into = MemoryFolder().apply { free = pattern.size - 1L }
        assertEquals(SaveOutcome.Failed(SaveFailure.NoRoom), save(server(mapOf("vol1.cbz" to pattern)), "vol1.cbz", false, into))
        assertTrue(into.files.isEmpty())
    }

    @Test
    fun `a destination that fills up while writing fails with the part deleted`() {
        val into = MemoryFolder().apply { fullAfter = SMB_READ_BLOCK + 1L }
        assertEquals(SaveOutcome.Failed(SaveFailure.NoRoom), save(server(mapOf("vol1.cbz" to pattern)), "vol1.cbz", false, into))
        assertTrue(into.files.isEmpty())
    }

    @Test
    fun `destination failures become write and permission failures`() {
        val files = mapOf("vol1.cbz" to pattern)
        val unwritable = MemoryFolder().apply { failCreate = SaveDestinationException("fake") }
        assertEquals(SaveOutcome.Failed(SaveFailure.WriteFailed), save(server(files), "vol1.cbz", false, unwritable))
        val denied = MemoryFolder().apply { failCreate = SecurityException("grant revoked") }
        assertEquals(SaveOutcome.Failed(SaveFailure.NoPermission), save(server(files), "vol1.cbz", false, denied))
        val strange = MemoryFolder().apply { failCreate = FileNotFoundException("folder gone") }
        assertEquals(SaveOutcome.Failed(SaveFailure.WriteFailed), save(server(files), "vol1.cbz", false, strange))
        val unrenamable = MemoryFolder().apply { failCommit = SaveDestinationException("cannot rename") }
        assertEquals(SaveOutcome.Failed(SaveFailure.WriteFailed), save(server(files), "vol1.cbz", false, unrenamable))
        assertTrue(unrenamable.files.isEmpty())
    }

    @Test
    fun `two saves of one name into one folder take two names and keep each other's part`() {
        val into = MemoryFolder()
        val bothWriting = CountDownLatch(2)
        into.onWrite = {
            if (bothWriting.count > 0) {
                bothWriting.countDown()
                bothWriting.await(10, TimeUnit.SECONDS)
            }
        }
        val server = server(mapOf("vol1.cbz" to pattern))
        val outcomes = runBlocking {
            val first = async(Dispatchers.Default) { server.saveTo("vol1.cbz", false, into) }
            val second = async(Dispatchers.Default) { server.saveTo("vol1.cbz", false, into) }
            listOf(first.await(), second.await())
        }
        val size = pattern.size.toLong()
        assertEquals(setOf(SaveOutcome.Saved("vol1.cbz", 1, size), SaveOutcome.Saved("vol1 (1).cbz", 1, size)), outcomes.toSet())
        assertEquals(setOf("vol1.cbz.part", "vol1 (1).cbz.part"), into.log.toSet())
        assertArrayEquals(pattern, into.files.getValue("vol1.cbz"))
        assertArrayEquals(pattern, into.files.getValue("vol1 (1).cbz"))
        assertTrue(into.leftovers().isEmpty())
    }

    @Test
    fun `a stale part from an earlier run is replaced`() {
        val into = MemoryFolder(files = linkedMapOf("vol1.cbz.part" to byteArrayOf(7, 7, 7)))
        save(server(mapOf("vol1.cbz" to pattern)), "vol1.cbz", false, into)
        assertEquals(listOf("vol1.cbz"), into.files.keys.toList())
        assertFalse(into.files.keys.any { it.endsWith(SAVE_PART_SUFFIX) })
    }
}
