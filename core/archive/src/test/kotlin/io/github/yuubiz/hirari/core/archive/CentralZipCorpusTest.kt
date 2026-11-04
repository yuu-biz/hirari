package io.github.yuubiz.hirari.core.archive

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * The ZIPs of the corpus (docs/spec.md section 6.2) opened without libarchive, against the corpus manifest. The
 * device test `ArchiveSessionTest` checks the same manifest through libarchive for the ZIPs it still reads.
 */
class CentralZipCorpusTest {
    private val corpus = File(System.getProperty("hirari.corpus") ?: error("run through Gradle, which passes -Dhirari.corpus"))

    /** One `archive` line of `expected.tsv` and its `entry` lines. */
    private class Expected(val path: String, val open: List<String>, val entries: List<List<String>>)

    private val zips: List<Expected> by lazy {
        val archives = ArrayList<Expected>()
        File(corpus, "expected.tsv").readLines().filter { it.isNotEmpty() }.map { it.split('\t') }.forEach { line ->
            when (line[0]) {
                "archive" -> archives += Expected(line[1], line.drop(2), ArrayList())
                "entry" -> (archives.last().entries as ArrayList) += line.drop(1)
            }
        }
        archives.filter { it.path.startsWith("zip/") }
    }

    /** The ZIPs whose end record or central directory is missing or not where it says, which libarchive lists. */
    private val listedByLibarchive = setOf(
        "zip/not-a-zip.zip",
        "zip/prefixed.zip",
        "zip/truncated-first-header.zip",
        "zip/truncated-second-header.zip",
        "zip/truncated-second-data.zip",
        "zip/truncated-central-directory.zip",
    )

    private class LibarchiveCalled : RuntimeException()

    private fun open(path: String): OpenResult = runBlocking {
        ArchiveSession.open(MemorySource(File(corpus, path).readBytes()), null) { throw LibarchiveCalled() }
    }

    @Test
    fun `the corpus has the ZIPs this test is about`() {
        val paths = zips.map { it.path }.toSet()
        assertTrue(paths.containsAll(listedByLibarchive))
        assertTrue(paths.containsAll(listOf("zip/zip64.zip", "zip/long-comment.zip", "zip/data-descriptor.zip", "zip/empty.zip")))
    }

    @Test
    fun `a ZIP without a whole central directory goes to libarchive`() {
        for (path in listedByLibarchive) assertThrows(path, LibarchiveCalled::class.java) { open(path) }
    }

    @Test
    fun `every other corpus ZIP lists from its central directory as the manifest expects`() {
        val problems = zips.filter { it.path !in listedByLibarchive }.flatMap { expected ->
            val session = (open(expected.path) as? OpenResult.Opened)?.session ?: return@flatMap listOf("${expected.path}: did not open")
            session.use {
                val problems = ArrayList<String>()
                val open = listOf("opens", session.indexError?.name?.lowercase() ?: "-")
                if (open != expected.open) problems += "open: expected ${expected.open}, got $open"
                assertEquals(ArchiveFormat.Zip, session.format)
                val charset = session.nameCharset()
                val names = session.entries.map { session.name(it, charset) }
                val expectedNames = expected.entries.map { it[0] }
                if (names != expectedNames) problems += "names: expected $expectedNames, got $names"
                val directories = session.entries.map { it.isDirectory }
                val expectedDirectories = expected.entries.map { it[1] == "directory" }
                if (directories != expectedDirectories) problems += "directories: expected $expectedDirectories, got $directories"
                problems.map { "${expected.path}: $it" }
            }
        }
        if (problems.isNotEmpty()) fail(problems.joinToString("\n"))
    }
}
