package io.github.yuubiz.hirari.core.image

import io.github.yuubiz.hirari.core.model.Container
import io.github.yuubiz.hirari.core.model.ImageType
import io.github.yuubiz.hirari.core.model.PageResult
import io.github.yuubiz.hirari.core.model.FailureReason
import io.github.yuubiz.hirari.core.source.ByteSource
import io.github.yuubiz.hirari.core.source.smb.ConnectionLostException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class OpenBookTest {
    private val decoding = ImageDecoding(budgetBytes = 64L shl 20, parallelism = 1)

    @Test
    fun runningOutOfMemoryReadingAPageIsARetryableReadError() = runTest {
        val files = listOf(FolderFile("001.jpg", size = null) { throw OutOfMemoryError() })
        val book = (Books.openFolder(files, decoding) as BookResult.Opened).book
        assertEquals(readError, book.page(0, PixelSize(1080, 1920)))
        assertEquals(readError, book.shape(0))
        book.close()
    }

    @Test
    fun aReversedBookReadsItsLastPageFirstAndNumbersThePagesInTheNewOrder() = runTest {
        val opened = mutableListOf<String>()
        val files = listOf("1.jpg", "2.jpg", "3.jpg").map { name -> FolderFile(name, size = null) { opened += name; throw IOException("not here") } }
        val book = (Books.openFolder(files, decoding) as BookResult.Opened).book.reversed()
        assertEquals(listOf("3.jpg", "2.jpg", "1.jpg"), book.pages.map { it.name })
        assertEquals(listOf(0, 1, 2), book.pages.map { it.index })
        book.raw(0)
        assertEquals(listOf("3.jpg"), opened)
        book.close()
    }

    @Test
    fun aFolderBookIsOpenedFromAFolder() = runTest {
        val book = (Books.openFolder(emptyList(), decoding) as BookResult.Opened).book
        assertEquals(Container.Folder, book.container)
        book.close()
    }

    @Test
    fun aDecodeStillQueuedAtTheTimeoutNeverStarts() = runTest {
        val scope = CoroutineScope(SupervisorJob())
        val busy = CountDownLatch(1)
        val release = CountDownLatch(1)
        scope.launch(decoding.dispatcher) {
            busy.countDown()
            release.await()
        }
        busy.await()
        val started = AtomicBoolean(false)
        val result = decodeWithTimeout(scope, decoding.dispatcher) {
            started.set(true)
            PageResult.Ready(Unit)
        }
        release.countDown()
        scope.coroutineContext.job.children.toList().joinAll()
        assertEquals(timedOut, result)
        assertFalse("the queued decode started after its caller gave up", started.get())
    }

    @Test
    fun aDecodeRunningAtTheTimeoutIsToldItsResultIsNotWanted() = runTest {
        val scope = CoroutineScope(SupervisorJob())
        val running = CountDownLatch(1)
        val release = CountDownLatch(1)
        val wanted = AtomicBoolean(true)
        val callerGoesOn = Job()
        val result = async {
            decodeWithTimeout(scope, decoding.dispatcher) { isActive ->
                running.countDown()
                release.await()
                wanted.set(isActive())
                PageResult.Ready(Unit)
            }.also { callerGoesOn.join() }
        }
        runCurrent()
        running.await()
        advanceTimeBy(ImageDecoding.DECODE_TIMEOUT_MILLIS + 1)
        release.countDown()
        scope.coroutineContext.job.children.toList().joinAll()
        assertFalse(wanted.get())
        callerGoesOn.complete()
        assertEquals(timedOut, result.await())
    }

    @Test
    fun aResultThatArrivesAfterTheTimeoutIsReleased() = runTest {
        val scope = CoroutineScope(SupervisorJob())
        val running = CountDownLatch(1)
        val release = CountDownLatch(1)
        val released = mutableListOf<String>()
        val result = async {
            decodeWithTimeout<String>(scope, decoding.dispatcher, { synchronized(released) { released += it } }) {
                running.countDown()
                release.await()
                PageResult.Ready("late")
            }
        }
        runCurrent()
        running.await()
        advanceTimeBy(ImageDecoding.DECODE_TIMEOUT_MILLIS + 1)
        assertEquals(timedOut, result.await())
        release.countDown()
        scope.coroutineContext.job.children.toList().joinAll()
        assertEquals(listOf("late"), synchronized(released) { released.toList() })
    }

    @Test
    fun aResultThatIsDeliveredIsNotReleased() = runBlocking {
        val scope = CoroutineScope(SupervisorJob())
        val released = mutableListOf<String>()
        val result = decodeWithTimeout<String>(scope, decoding.dispatcher, { released += it }) { PageResult.Ready("wanted") }
        scope.coroutineContext.job.children.toList().joinAll()
        assertEquals(PageResult.Ready("wanted"), result)
        assertEquals(emptyList<String>(), released)
    }

    @Test
    fun aResultForACallerThatWasCancelledIsReleased() = runTest {
        val scope = CoroutineScope(SupervisorJob())
        val running = CountDownLatch(1)
        val release = CountDownLatch(1)
        val released = mutableListOf<String>()
        val caller = launch {
            decodeWithTimeout<String>(scope, decoding.dispatcher, { synchronized(released) { released += it } }) {
                running.countDown()
                release.await()
                PageResult.Ready("orphan")
            }
        }
        runCurrent()
        running.await()
        caller.cancel()
        runCurrent()
        release.countDown()
        scope.coroutineContext.job.children.toList().joinAll()
        assertEquals(listOf("orphan"), synchronized(released) { released.toList() })
    }

    @Test
    fun aPageDecodesFromItsRawBytesWithoutReadingTheBookAgain() = runBlocking {
        val opens = AtomicInteger()
        val data = "not an image".toByteArray()
        val files = listOf(
            FolderFile("001.jpg", size = data.size.toLong()) {
                opens.incrementAndGet()
                MemorySource(data)
            },
        )
        val book = (Books.openFolder(files, decoding) as BookResult.Opened).book
        val raw = (book.raw(0) as PageResult.Ready).image
        assertEquals(data.size.toLong(), raw.byteCount)
        assertEquals(1, opens.get())
        assertEquals(notAnImage, book.page(0, PixelSize(1080, 1920), raw))
        assertEquals(notAnImage, book.shape(0, raw))
        assertEquals(1, opens.get())
        book.close()
    }

    @Test
    fun aLoneImageOpensAsABookOfOnePageThatReadsTheWholeFile() = runBlocking {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(2000) { it.toByte() }
        val source = MemorySource(png)
        val book = (Books.openBook(source, decoding) as BookResult.Opened).book
        assertEquals(Container.Image, book.container)
        assertEquals(listOf("1"), book.pages.map { it.name })
        assertEquals(ImageType.Png, book.pages.single().sniffed)
        assertEquals(png.size.toLong(), book.pages.single().declaredSize)
        val raw = (book.raw(0) as PageResult.Ready).image as RawPage.Encoded
        assertEquals(ImageType.Png, raw.type)
        assertEquals(png.toList(), java.io.ByteArrayOutputStream().also(raw::writeTo).toByteArray().toList())
        assertFalse(source.closed)
        book.close()
        assertTrue(source.closed)
    }

    @Test
    fun aFileThatFailsToReadGivesNoRawBytes() = runTest {
        val files = listOf(FolderFile("001.jpg", size = null) { throw IOException("gone") })
        val book = (Books.openFolder(files, decoding) as BookResult.Opened).book
        assertEquals(readError, book.raw(0))
        book.close()
    }

    @Test
    fun aFolderWhoseFileCannotBeToldForTheBrokenConnectionDoesNotOpenWithoutIt() = runTest {
        val files = listOf(
            FolderFile("001.jpg", size = null) { MemorySource(ByteArray(10)) },
            FolderFile("002", size = null) { throw ConnectionLostException("SMB is reconnecting") },
        )
        assertEquals(BookResult.Failed(BookFailure.ReadError), Books.openFolder(files, decoding))
    }

    @Test
    fun aFileWithoutAnExtensionWhoseBytesShowNoImageIsLeftOut() = runTest {
        val files = listOf(
            FolderFile("001.jpg", size = null) { MemorySource(ByteArray(10)) },
            FolderFile("002", size = null) { MemorySource("not an image".toByteArray()) },
            FolderFile("003", size = null) { throw IOException("unreadable") },
        )
        val book = (Books.openFolder(files, decoding) as BookResult.Opened).book
        assertEquals(listOf("001.jpg"), book.pages.map { it.name })
        book.close()
    }

    @Test
    fun aFileOnABrokenConnectionIsAPageToRetryOnceItComesBack() = runTest {
        val files = listOf(FolderFile("001.jpg", size = null) { throw ConnectionLostException("SMB is reconnecting") })
        val book = (Books.openFolder(files, decoding) as BookResult.Opened).book
        assertEquals(PageResult.Failed(FailureReason.ConnectionLost, retryable = true), book.raw(0))
        book.close()
    }
}

internal class MemorySource(private val data: ByteArray) : ByteSource {
    override val size: Long = data.size.toLong()

    var closed = false
        private set

    override fun read(offset: Long, dst: ByteBuffer): Int {
        if (offset >= size) return -1
        val count = minOf(dst.remaining(), (size - offset).toInt())
        dst.put(data, offset.toInt(), count)
        return count
    }

    override fun close() {
        closed = true
    }
}
