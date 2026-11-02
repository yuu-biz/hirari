package io.github.yuubiz.hirari.core.archive

import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yuubiz.hirari.core.source.ByteSource
import io.github.yuubiz.hirari.core.source.asByteSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.util.Random
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.coroutines.cancellation.CancellationException

class ArchiveSessionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun cacheFile(name: String, bytes: ByteArray): File =
        File(instrumentation.targetContext.cacheDir, name).apply { writeBytes(bytes) }

    private fun asset(path: String): ByteArray = instrumentation.context.assets.open(path).use { it.readBytes() }

    private fun fileSource(file: File): ByteSource =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).asByteSource()

    private suspend fun open(source: ByteSource, extractDir: File? = null): ArchiveSession =
        when (val result = ArchiveSession.open(source, extractDir)) {
            is OpenResult.Opened -> result.session
            else -> throw AssertionError("open failed: $result")
        }

    private fun crc(bytes: ByteArray): String = "%08x".format(CRC32().apply { update(bytes) }.value)

    /** One `archive` line of the corpus manifest and its `entry` lines. */
    private class ExpectedArchive(val path: String, val open: List<String>, val entries: List<List<String>>)

    private fun manifest(): List<ExpectedArchive> {
        val lines = String(asset("expected.tsv"), Charsets.UTF_8).lines().filter { it.isNotEmpty() }.map { it.split('\t') }
        val archives = ArrayList<ExpectedArchive>()
        for (line in lines) {
            when (line[0]) {
                "archive" -> archives += ExpectedArchive(line[1], line.drop(2), ArrayList())
                "entry" -> (archives.last().entries as ArrayList) += line.drop(1)
            }
        }
        return archives
    }

    private fun describe(result: EntryResult, entry: ArchiveEntry): List<String> = when (result) {
        is EntryResult.Data -> if (entry.isDirectory && result.bytes.isEmpty()) {
            listOf("directory")
        } else {
            listOf("data", result.bytes.size.toString(), crc(result.bytes))
        }
        is EntryResult.Failed -> listOf("fails", result.reason.name.lowercase())
        EntryResult.Encrypted -> listOf("unsupported", "encrypted")
        is EntryResult.UnsupportedCompression -> listOf("unsupported", "compression:${result.method}")
        is EntryResult.TooLarge -> listOf("unsupported", "too-large")
    }

    private fun extractDir(name: String): File =
        File(instrumentation.targetContext.cacheDir, "extract/$name").apply { deleteRecursively() }

    /**
     * Opens [expected] and reads every entry forward, then backward, and lists each way it differs. With
     * [extractDir], a solid archive goes through the extraction cache.
     */
    private suspend fun mismatches(expected: ExpectedArchive, extractDir: File? = null): List<String> {
        val source = fileSource(cacheFile(expected.path.substringAfterLast('/'), asset(expected.path)))
        val result = ArchiveSession.open(source, extractDir)
        val problems = ArrayList<String>()
        val session = when (result) {
            is OpenResult.Failed, OpenResult.Encrypted -> {
                val reason = if (result is OpenResult.Failed) result.reason.name else "Encrypted"
                val actual = listOf("fails", reason.lowercase())
                if (actual != expected.open) problems += "open: expected ${expected.open}, got $actual"
                return problems
            }
            is OpenResult.Opened -> result.session
        }
        session.use {
            val open = listOf("opens", session.indexError?.name?.lowercase() ?: "-")
            if (open != expected.open) problems += "open: expected ${expected.open}, got $open"
            val charset = session.nameCharset()
            val names = session.entries.map { session.name(it, charset) }
            val expectedNames = expected.entries.map { it[0] }
            if (names != expectedNames) problems += "names: expected $expectedNames, got $names"
            val forward = session.entries.map { describe(session.read(it.entryIndex), it) }
            val backward = session.entries.reversed().map { describe(session.read(it.entryIndex), it) }.reversed()
            if (forward != backward) problems += "reading backward gave $backward, forward $forward"
            forward.zip(expected.entries).forEachIndexed { i, (actual, wanted) ->
                if (actual != wanted.drop(1)) problems += "entry $i ${wanted[0]}: expected ${wanted.drop(1)}, got $actual"
            }
        }
        return problems
    }

    private fun bigZip(): Pair<File, List<ByteArray>> = cacheFile("big.zip", bigZip.first) to bigZip.second

    @Test
    fun everyCorpusArchiveOpensAndReadsAsTheManifestExpects() = runBlocking {
        val archives = manifest()
        assertTrue(archives.map { it.path.substringBefore('/') }.toSet().containsAll(listOf("zip", "rar4", "rar5", "7z", "tar")))
        val problems = archives.flatMap { archive ->
            withTimeout(10_000) { mismatches(archive) }.map { "${archive.path}: $it" }
        }
        if (problems.isNotEmpty()) fail(problems.joinToString("\n"))
    }

    @Test
    fun everyCorpusArchiveReadsTheSameThroughTheExtractionCache() = runBlocking {
        val problems = manifest().flatMap { archive ->
            withTimeout(10_000) { mismatches(archive, extractDir(archive.path.replace('/', '-'))) }.map { "${archive.path}: $it" }
        }
        if (problems.isNotEmpty()) fail(problems.joinToString("\n"))
    }

    @Test
    fun solidArchivesAreSolidAndExtractIntoTheirDirectory() = runBlocking {
        val solid = listOf("7z/lzma2-solid.7z", "7z/copy.7z", "rar5/solid.rar", "tar/compressed.tgz", "tar/compressed.tzst")
        val notSolid = listOf("zip/deflate.zip", "rar5/stored.rar", "rar4/stored.rar", "tar/plain.tar")
        for (path in solid + notSolid) {
            val dir = extractDir("solid-test")
            open(fileSource(cacheFile("solid-test", asset(path))), dir).use { session ->
                assertEquals(path, path in solid, session.solid)
                val last = session.entries.last()
                assertTrue(path, session.read(last.entryIndex) is EntryResult.Data)
                assertEquals(path, path in solid, File(dir, last.entryIndex.toString()).isFile)
            }
        }
    }

    @Test
    fun aSecondSessionReadsTheFilesTheFirstExtracted() = runBlocking {
        val dir = extractDir("reuse")
        val path = "7z/lzma2-solid.7z"
        val first = open(fileSource(cacheFile("reuse.7z", asset(path))), dir).use { session ->
            session.entries.map { (session.read(it.entryIndex) as EntryResult.Data).bytes }
        }
        val source = WatchedSource(fileSource(cacheFile("reuse.7z", asset(path))))
        open(source, dir).use { session ->
            val readAtOpen = source.bytesRead.get()
            val second = session.entries.map { (session.read(it.entryIndex) as EntryResult.Data).bytes }
            assertEquals(first.map { it.toList() }, second.map { it.toList() })
            assertEquals("bytes read from the archive after opening", readAtOpen, source.bytesRead.get())
        }
    }

    /** [delegate] with a count of the bytes read, and a gate that can hold reads until it opens. */
    private class WatchedSource(private val delegate: ByteSource) : ByteSource by delegate {
        val bytesRead = AtomicLong()
        val reads = AtomicInteger()
        val closed = CountDownLatch(1)

        @Volatile
        var gate: CountDownLatch? = null
        val waiting = CountDownLatch(1)

        @Volatile
        var failure: IOException? = null

        override fun read(offset: Long, dst: ByteBuffer): Int {
            failure?.let { throw it }
            gate?.let {
                waiting.countDown()
                it.await()
            }
            reads.incrementAndGet()
            return delegate.read(offset, dst).also { if (it > 0) bytesRead.addAndGet(it.toLong()) }
        }

        override fun close() {
            delegate.close()
            closed.countDown()
        }
    }

    @Test
    fun readingTheLastEntryReadsLittleMoreThanItsOwnData() = runBlocking {
        val (file, data) = bigZip()
        val source = WatchedSource(fileSource(file))
        open(source).use { session ->
            assertEquals(bigEntries, session.entries.size)
            val beforeRead = source.bytesRead.get()

            val last = session.read(bigEntries - 1)

            val readForLast = source.bytesRead.get() - beforeRead
            assertArrayEquals(data.last(), (last as EntryResult.Data).bytes)
            assertTrue("read $readForLast bytes of ${file.length()} for the last entry", readForLast < file.length() / 4)

            assertArrayEquals(data.first(), (session.read(0) as EntryResult.Data).bytes)
            assertArrayEquals(data[bigEntries / 2], (session.read(bigEntries / 2) as EntryResult.Data).bytes)
        }
    }

    @Test
    fun cancellingAReadStopsTheNativeReadAndLeavesTheSessionUsable() = runBlocking {
        val (file, data) = bigZip()
        val source = WatchedSource(fileSource(file))
        open(source).use { session ->
            val gate = CountDownLatch(1)
            source.gate = gate
            val read = async(Dispatchers.Default) { session.read(bigEntries - 1) }
            assertTrue(source.waiting.await(5, TimeUnit.SECONDS))
            read.cancel()
            source.gate = null
            val readsBeforeRelease = source.reads.get()
            gate.countDown()

            try {
                read.await()
                fail("the cancelled read returned")
            } catch (_: CancellationException) {
            }

            assertEquals("reads after the held one", readsBeforeRelease + 1, source.reads.get())
            assertArrayEquals(data.last(), (session.read(bigEntries - 1) as EntryResult.Data).bytes)
        }
    }

    @Test
    fun aFailingSourceGivesReadErrorAndTheSessionRecoversWhenItReadsAgain() = runBlocking {
        val (file, data) = bigZip()
        val source = WatchedSource(fileSource(file))
        open(source).use { session ->
            source.failure = IOException("unplugged")
            assertEquals(EntryResult.Failed(ArchiveFailure.ReadError), session.read(3))

            source.failure = null
            assertArrayEquals(data[3], (session.read(3) as EntryResult.Data).bytes)
        }
    }

    @Test
    fun aReadPastTheLimitStopsWithTooLarge() = runBlocking {
        val (file, _) = bigZip()
        open(fileSource(file)).use { session ->
            val result = session.read(0, maxBytes = 100_000)
            assertTrue("$result", result is EntryResult.TooLarge && result.bytes > 100_000)
        }
    }

    @Test
    fun closingTheSessionClosesTheSource() = runBlocking {
        val source = WatchedSource(fileSource(cacheFile("names.zip", asset("zip/names.zip"))))
        open(source).close()
        assertTrue(source.closed.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun aFailedOpenClosesTheSource() = runBlocking {
        val source = WatchedSource(fileSource(cacheFile("not-a-zip.zip", asset("zip/not-a-zip.zip"))))
        assertEquals(OpenResult.Failed(ArchiveFailure.Corrupt), ArchiveSession.open(source))
        assertTrue(source.closed.await(5, TimeUnit.SECONDS))
    }

    private companion object {
        const val bigEntries = 32
        const val bigEntrySize = 512 * 1024

        /** A ZIP of [bigEntries] Deflate entries of [bigEntrySize] random bytes each, and the data of each entry. */
        val bigZip: Pair<ByteArray, List<ByteArray>> by lazy {
            val random = Random(7)
            val data = List(bigEntries) { ByteArray(bigEntrySize).also(random::nextBytes) }
            val out = ByteArrayOutputStream()
            ZipOutputStream(out).use { zip ->
                data.forEachIndexed { i, bytes ->
                    zip.putNextEntry(ZipEntry("%03d.jpg".format(i)))
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
            out.toByteArray() to data
        }
    }
}
