package io.github.yuubiz.hirari.core.archive

import io.github.yuubiz.hirari.core.model.NaturalOrder
import io.github.yuubiz.hirari.core.source.smb.SmbAddress
import io.github.yuubiz.hirari.core.source.smb.SmbLogin
import io.github.yuubiz.hirari.core.source.smb.SmbPurpose
import io.github.yuubiz.hirari.core.source.smb.SmbServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * How many covers an SMB place should make at once (docs/spec.md section 4.4): one screenful of 15 covers, each made
 * as the thumbnail maker does it (open the book, read the first kilobyte, list the ZIP, read the cover page, then
 * decode), by 1 or 2 workers, against `cover.zip` of the Samba fixture. A foreground listing is asked for 200 ms
 * into each run, to see what it waits for. Prints one line per setting.
 *
 * The decode is a sleep of the given length, since the JVM has no bitmap decoder; it holds the worker as the real one
 * holds the lane. Runs only with `-Dhirari.smb.host`, as printed by `tools/smb-fixture/fixture.sh props`; the
 * label of the network in the output is `-Dhirari.smb.net`, so a run through a delaying relay can be told apart.
 */
class SmbCoverLaneTest {
    private val host = System.getProperty("hirari.smb.host")
    private val port = System.getProperty("hirari.smb.port")?.toInt() ?: 445
    private val net = System.getProperty("hirari.smb.net") ?: "direct"

    private class LibarchiveCalled : RuntimeException()

    private class Run(val totalMillis: Long, val firstMillis: Long, val medianMillis: Long, val listingMillis: Long)

    private fun server(scope: CoroutineScope) =
        SmbServer(SmbAddress(host!!, "books", port), SmbLogin.Password("bookworm7", "hirari-fixture-Pw7q".toCharArray()), scope)

    /** Makes the cover of [BOOK] up to the decode, which takes [decodeMillis]. */
    private fun SmbServer.cover(decodeMillis: Long) {
        val source = open(BOOK, SmbPurpose.Background)
        source.read(0, ByteBuffer.allocate(1024))
        val session = (runBlocking { ArchiveSession.open(source, null) { throw LibarchiveCalled() } } as OpenResult.Opened).session
        session.use {
            val cover = session.entries.minWith(compareBy(NaturalOrder) { session.name(it, Charsets.UTF_8) })
            assertEquals(PAGE_SIZE, (runBlocking { session.read(cover.entryIndex) } as EntryResult.Data).bytes.size)
        }
        Thread.sleep(decodeMillis)
    }

    private fun run(lanes: Int, decodeMillis: Long): Run {
        val scope = CoroutineScope(SupervisorJob())
        val pool = Executors.newFixedThreadPool(lanes + 1)
        try {
            server(scope).use { server ->
                runBlocking { server.list("") }
                val started = System.nanoTime()
                fun since() = (System.nanoTime() - started) / 1_000_000
                val lane = Executors.newFixedThreadPool(lanes)
                val covers = List(COVERS) { lane.submit(Callable { server.cover(decodeMillis); since() }) }
                Thread.sleep(200)
                val listingStarted = since()
                val listing = pool.submit(Callable { runBlocking { server.list("many") }; since() - listingStarted })
                val done = covers.map { it.get() }.sorted()
                lane.shutdown()
                return Run(done.last(), done.first(), done[done.size / 2], listing.get())
            }
        } finally {
            pool.shutdownNow()
            scope.cancel()
        }
    }

    @Test
    fun `a screenful of covers by one or two workers`() {
        assumeTrue("SMB fixture not configured (tools/smb-fixture/fixture.sh props)", host != null)
        for (decodeMillis in listOf(0L, 50L, 150L)) {
            for (lanes in listOf(1, 2)) {
                run(lanes, decodeMillis)
                val runs = List(REPEATS) { run(lanes, decodeMillis) }
                fun median(of: (Run) -> Long) = runs.map(of).sorted()[runs.size / 2]
                println(
                    "COVER-LANE net=$net lanes=$lanes decode=${decodeMillis}ms covers=$COVERS: " +
                        "all=${median { it.totalMillis }} ms, first=${median { it.firstMillis }} ms, " +
                        "median cover=${median { it.medianMillis }} ms, listing=${median { it.listingMillis }} ms " +
                        "(all: ${runs.map { it.totalMillis }})",
                )
            }
        }
    }

    @Test
    fun `a listing while two covers are made and four folder lookups run`() {
        assumeTrue("SMB fixture not configured (tools/smb-fixture/fixture.sh props)", host != null)
        val scope = CoroutineScope(SupervisorJob())
        val pool = Executors.newCachedThreadPool()
        try {
            server(scope).use { server ->
                runBlocking { server.list("") }
                val stop = AtomicBoolean()
                val lookups = AtomicInteger()
                val background = List(2) { pool.submit { while (!stop.get()) server.cover(decodeMillis = 50) } } +
                    List(4) { pool.submit { while (!stop.get()) { runBlocking { server.list("many", SmbPurpose.Background) }; lookups.incrementAndGet() } } }
                Thread.sleep(500)
                val waits = List(40) {
                    val started = System.nanoTime()
                    runBlocking { server.list("many") }
                    ((System.nanoTime() - started) / 1_000_000).also { Thread.sleep(100) }
                }.sorted()
                stop.set(true)
                background.forEach { it.get() }
                println(
                    "COVER-LANE net=$net foreground listing under 2 covers and 4 lookups: median=${waits[waits.size / 2]} ms, " +
                        "worst=${waits.last()} ms, lookups done=${lookups.get()}",
                )
            }
        } finally {
            pool.shutdownNow()
            scope.cancel()
        }
    }

    private companion object {
        const val BOOK = "cover.zip"
        const val PAGE_SIZE = 150_000
        const val COVERS = 15
        const val REPEATS = 5
    }
}
