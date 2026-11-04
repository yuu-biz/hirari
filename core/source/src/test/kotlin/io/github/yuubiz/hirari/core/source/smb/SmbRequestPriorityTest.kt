package io.github.yuubiz.hirari.core.source.smb

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

/**
 * A listing and the reader are not queued behind covers (docs/spec.md section 8.3). The server here waits 15 s for a
 * turn, as the real one does; a request that is queued behind the covers would not finish before the test gives up.
 */
class SmbRequestPriorityTest {
    private val pages = ByteArray(FAR_OFFSET * 3) { it.toByte() }
    private val release = CountDownLatch(1)
    private val reading = AtomicInteger()
    private val pool = Executors.newCachedThreadPool()
    private val coverPool = Executors.newCachedThreadPool { Thread(it, COVER_THREAD) }
    private val scope = CoroutineScope(Job())

    private val connector = FakeConnector(mapOf("a.zip" to pages, "b.zip" to pages, "folder/c.zip" to pages)) {
        if (Thread.currentThread().name == COVER_THREAD) {
            reading.incrementAndGet()
            release.await(30, TimeUnit.SECONDS)
        }
    }
    private val server = SmbServer(connector, SmbLogin.Guest, scope, emptyFlow(), Dispatchers.IO, 15.seconds)

    @After
    fun tearDown() {
        release.countDown()
        pool.shutdownNow()
        coverPool.shutdownNow()
        server.close()
        scope.cancel()
    }

    private fun cover(): Future<*> = coverPool.submit {
        server.open("a.zip", SmbPurpose.Background).use { it.read(0, ByteBuffer.allocate(100)) }
    }

    private fun startFourCovers(): List<Future<*>> {
        val covers = List(4) { cover() }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (reading.get() < MAX_BACKGROUND_REQUESTS_PER_SERVER && System.nanoTime() < deadline) Thread.sleep(5)
        assertTrue("covers hold permits, in their reads", reading.get() >= MAX_BACKGROUND_REQUESTS_PER_SERVER)
        return covers
    }

    @Test
    fun `a listing finishes while four covers hold or wait for the permits`() {
        val covers = startFourCovers()
        val listing = pool.submit<Int> { runBlocking { server.list("folder").size } }
        assertEquals(1, listing.get(5, TimeUnit.SECONDS))
        assertFalse("the covers are still running", covers.any { it.isDone })
    }

    @Test
    fun `the reader reads a page while four covers hold or wait for the permits`() {
        startFourCovers()
        val page = pool.submit<Int> {
            server.open("b.zip").use { source ->
                val out = ByteBuffer.allocate(100)
                source.read(FAR_OFFSET.toLong(), out)
            }
        }
        assertEquals(100, page.get(5, TimeUnit.SECONDS))
    }

    @Test
    fun `the cover that waited gets a permit when the others are done`() {
        val covers = startFourCovers()
        release.countDown()
        covers.forEach { it.get(5, TimeUnit.SECONDS) }
        assertTrue(covers.all { it.isDone })
    }

    private companion object {
        const val COVER_THREAD = "cover"
    }
}
