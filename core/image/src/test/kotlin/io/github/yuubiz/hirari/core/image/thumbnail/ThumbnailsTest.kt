package io.github.yuubiz.hirari.core.image.thumbnail

import io.github.yuubiz.hirari.core.image.BookFailure
import io.github.yuubiz.hirari.core.image.BookResult
import io.github.yuubiz.hirari.core.model.BookId
import io.github.yuubiz.hirari.core.model.CoverCrop
import io.github.yuubiz.hirari.core.model.ReadingDirection
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.Closeable
import java.io.IOException
import java.io.File
import java.time.Instant
import java.util.concurrent.Executors
import kotlin.coroutines.cancellation.CancellationException

private class MemoryStore(private val dir: File) : ThumbnailStore {
    val files = HashMap<ThumbnailKey, File>()
    val failed = LinkedHashSet<ThumbnailKey>()
    val held = ArrayList<ThumbnailKey>()
    val released = ArrayList<ThumbnailKey>()
    var puts = 0
    var afterGet: (ThumbnailKey) -> Unit = {}
    var afterPut: (ThumbnailKey) -> Unit = {}
    var beforeRemove: suspend (ThumbnailKey) -> Unit = {}

    val holding: List<ThumbnailKey> get() = held.toMutableList().apply { released.forEach { remove(it) } }

    /** What the size limit of the cache does at its worst: every thumbnail nobody holds goes, file and all. */
    fun evictUnheld() {
        val kept = holding.toSet()
        files.entries.removeAll { (key, file) -> key !in kept && file.delete() }
    }

    override suspend fun get(key: ThumbnailKey): StoredThumb = when {
        key in failed -> StoredThumb.Failed
        else -> files[key]?.let(StoredThumb::Hit) ?: StoredThumb.Miss
    }.also { afterGet(key) }

    override suspend fun put(key: ThumbnailKey, webp: ByteArray): File =
        File(dir, "${puts++}.webp").also { it.writeBytes(webp) }.also { files[key] = it }.also { afterPut(key) }

    override suspend fun markFailed(key: ThumbnailKey) {
        failed += key
    }

    override suspend fun remove(key: ThumbnailKey) {
        beforeRemove(key)
        files.remove(key)
        failed.remove(key)
    }

    override fun hold(key: ThumbnailKey): Closeable {
        held += key
        return Closeable { released += key }
    }

    /** The keys whose making is recorded now. */
    val making = LinkedHashSet<ThumbnailKey>()

    /** What a process that died left: these are failed and the next start holds the making back. */
    val interrupted = LinkedHashSet<ThumbnailKey>()

    override suspend fun <T> making(key: ThumbnailKey, block: suspend () -> T): T {
        making += key
        try {
            return block()
        } finally {
            making -= key
        }
    }

    override suspend fun settleInterrupted(): Boolean {
        failed += interrupted
        return interrupted.isNotEmpty().also { interrupted.clear() }
    }
}

/** A thumbnail whose bytes name the book it was made from. */
private fun image(book: ThumbSubject.Book) = Made.Webp(book.id.value.toByteArray())

/** The book whose image [result] shows. */
private fun shows(result: ThumbResult?): String = (result as ThumbResult.Ready).file.readText()

private class FakeMaker(var script: suspend (ThumbSubject.Book) -> Made = ::image) : ThumbnailMaker {
    class Call(val book: ThumbSubject.Book, val sizePx: Int, val direction: ReadingDirection)

    val calls = ArrayList<Call>()
    var running = 0
    var most = 0

    override suspend fun make(book: ThumbSubject.Book, sizePx: Int, direction: ReadingDirection): Made {
        calls += Call(book, sizePx, direction)
        running++
        most = maxOf(most, running)
        try {
            return script(book)
        } finally {
            running--
        }
    }

    val ids get() = calls.map { it.book.id.value }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ThumbnailsTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val store by lazy { MemoryStore(temp.root) }
    private val maker = FakeMaker()
    private var clock = 0L

    private fun TestScope.thumbnails(): Thumbnails {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return Thumbnails(store, maker, CoroutineScope(SupervisorJob() + dispatcher), dispatcher, now = { clock })
    }

    private fun book(
        id: String,
        lane: ThumbLane = ThumbLane.Local,
        coverEntry: Int? = null,
        modified: Long = 1000,
        coverCrop: CoverCrop? = null,
    ) = ThumbSubject.Book(
        BookId(id),
        Instant.ofEpochMilli(modified),
        byteSize = 5000,
        coverEntry = coverEntry,
        coverCrop = coverCrop,
        lane = lane,
        open = { BookResult.Failed(BookFailure.ReadError) },
    )

    private fun request(
        subject: ThumbSubject,
        sizePx: Int = 300,
        direction: ReadingDirection = ReadingDirection.RightToLeft,
        priority: ThumbPriority = ThumbPriority.Visible,
        distance: () -> Int = { 0 },
    ) = ThumbRequest(subject, sizePx, direction, priority, distance)

    private fun item(name: String, id: String = name, lane: ThumbLane = ThumbLane.Local) = FolderEntry.Item(name, book(id, lane))

    private fun folder(
        vararg entries: FolderEntry,
        chosen: ThumbSubject.Book? = null,
        id: String = "folder",
        onList: () -> Unit = {},
    ) = ThumbSubject.Folder(BookId(id), Instant.ofEpochMilli(500), chosen) {
        onList()
        entries.toList()
    }

    @Test
    fun aThumbnailIsMadeOnceAndThenFoundInTheStore() = runTest {
        val thumbnails = thumbnails()
        val first = async { thumbnails.thumbnail(request(book("a"))) }
        runCurrent()
        assertTrue(first.await() is ThumbResult.Ready)
        val second = async { thumbnails.thumbnail(request(book("a"))) }
        runCurrent()
        assertEquals(first.await(), second.await())
        assertEquals(1, maker.calls.size)
        assertEquals(1, store.puts)
    }

    @Test
    fun theMakerGetsTheSizeAndTheDirectionOfTheRequest() = runTest {
        val thumbnails = thumbnails()
        launch { thumbnails.thumbnail(request(book("a"), sizePx = 240, direction = ReadingDirection.LeftToRight)) }
        runCurrent()
        assertEquals(240, maker.calls.single().sizePx)
        assertEquals(ReadingDirection.LeftToRight, maker.calls.single().direction)
    }

    @Test
    fun theKeyHasTheCoverTheSizeAndTheDirectionSoEachOfThemIsAnotherThumbnail() = runTest {
        val thumbnails = thumbnails()
        val requests = listOf(
            request(book("a")),
            request(book("a", coverEntry = 3)),
            request(book("a"), sizePx = 400),
            request(book("a"), direction = ReadingDirection.LeftToRight),
            request(book("a", modified = 2000)),
        )
        requests.forEach { launch { thumbnails.thumbnail(it) } }
        runCurrent()
        assertEquals(requests.size, maker.calls.size)
        assertEquals(requests.size, store.files.keys.size)
        val key = store.files.keys.first { it.coverEntry == 3 }
        assertEquals(BookId("a"), key.book)
    }

    @Test
    fun aRangeOfTheCoverPageIsAnotherThumbnailAndTheKeyWithoutOneIsTheSameAsBefore() = runTest {
        val thumbnails = thumbnails()
        val crop = CoverCrop(1000, 0, 6000, 10_000)
        val requests = listOf(
            request(book("a", coverEntry = 3)),
            request(book("a", coverEntry = 3, coverCrop = crop)),
            request(book("a", coverEntry = 3, coverCrop = CoverCrop(1001, 0, 6000, 10_000))),
        )
        requests.forEach { launch { thumbnails.thumbnail(it) } }
        runCurrent()
        assertEquals(requests.size, store.files.keys.size)
        assertEquals(listOf(null, crop, CoverCrop(1001, 0, 6000, 10_000)), store.files.keys.sortedBy { it.coverCrop?.left ?: -1 }.map { it.coverCrop })
        assertEquals(crop, maker.calls[1].book.coverCrop)
    }

    @Test
    fun remakingARangeForgetsOnlyThatRangesThumbnail() = runTest {
        val thumbnails = thumbnails()
        val crop = CoverCrop(0, 0, 5000, 5000)
        launch { thumbnails.thumbnail(request(book("a", coverEntry = 3))) }
        launch { thumbnails.thumbnail(request(book("a", coverEntry = 3, coverCrop = crop))) }
        runCurrent()
        val again = async { thumbnails.regenerate(request(book("a", coverEntry = 3, coverCrop = crop))) }
        runCurrent()
        assertTrue(again.await() is ThumbResult.Ready)
        assertEquals(3, maker.calls.size)
        assertEquals(crop, maker.calls.last().book.coverCrop)
        assertEquals(2, store.files.keys.size)
    }

    @Test
    fun requestsForTheSameThumbnailWhileItIsMadeShareOneRun() = runTest {
        val thumbnails = thumbnails()
        val release = CompletableDeferred<Unit>()
        maker.script = {
            release.await()
            Made.Webp(byteArrayOf(1))
        }
        val results = List(3) { async { thumbnails.thumbnail(request(book("a"))) } }
        runCurrent()
        release.complete(Unit)
        runCurrent()
        assertEquals(1, maker.calls.size)
        assertEquals(1, results.map { it.await() }.toSet().size)
    }

    @Test
    fun theWorkStopsWhenTheLastRequestScrollsAway() = runTest {
        val thumbnails = thumbnails()
        var stopped = false
        maker.script = {
            try {
                awaitCancellation()
            } finally {
                stopped = true
            }
        }
        val first = launch { thumbnails.thumbnail(request(book("a"))) }
        val second = launch { thumbnails.thumbnail(request(book("a"))) }
        runCurrent()
        first.cancel()
        runCurrent()
        assertFalse(stopped)
        second.cancel()
        runCurrent()
        assertTrue(stopped)
        assertTrue(store.files.isEmpty())
        assertTrue(store.failed.isEmpty())
    }

    @Test
    fun aBookThatCannotBeReadIsRecordedAndNotTriedAgain() = runTest {
        val thumbnails = thumbnails()
        maker.script = { Made.Unreadable }
        val first = async { thumbnails.thumbnail(request(book("a"))) }
        runCurrent()
        assertEquals(ThumbResult.Failed, first.await())
        assertEquals(1, store.failed.size)
        val second = async { thumbnails.thumbnail(request(book("a"))) }
        val cached = async { thumbnails.cached(request(book("a"))) }
        runCurrent()
        assertEquals(ThumbResult.Failed, second.await())
        assertEquals(ThumbResult.Failed, cached.await())
        assertEquals(1, maker.calls.size)
    }

    @Test
    fun aChangedFileIsTriedAgainAfterAFailure() = runTest {
        val thumbnails = thumbnails()
        maker.script = { Made.Unreadable }
        launch { thumbnails.thumbnail(request(book("a", modified = 1000))) }
        runCurrent()
        maker.script = { Made.Webp(byteArrayOf(1)) }
        val again = async { thumbnails.thumbnail(request(book("a", modified = 2000))) }
        runCurrent()
        assertTrue(again.await() is ThumbResult.Ready)
    }

    @Test
    fun aRetryTriesAtOnceWhatFailedForAPassingReasonAndAFailureAgainBacksOff() = runTest {
        val thumbnails = thumbnails()
        maker.script = { Made.Unavailable }
        val inFolder = folder(item("child", lane = ThumbLane.Smb))
        assertEquals(ThumbResult.Unavailable, thumbnails.observe(request(book("a", lane = ThumbLane.Smb))).first())
        assertEquals(ThumbResult.Unavailable, thumbnails.observe(request(inFolder)).first())
        assertEquals(2, maker.calls.size)
        maker.script = ::image
        assertEquals(ThumbResult.Unavailable, thumbnails.observe(request(book("a", lane = ThumbLane.Smb))).first())
        assertEquals(2, maker.calls.size)
        assertEquals("a", shows(thumbnails.retry(request(book("a", lane = ThumbLane.Smb))).first()))
        assertEquals("child", shows(thumbnails.retry(request(inFolder)).first()))
        maker.script = { Made.Unavailable }
        assertEquals(ThumbResult.Unavailable, thumbnails.retry(request(book("b", lane = ThumbLane.Smb))).first())
        assertEquals(ThumbResult.Unavailable, thumbnails.observe(request(book("b", lane = ThumbLane.Smb))).first())
        assertEquals("a failure after a retry was tried again at once", 5, maker.calls.size)
    }

    @Test
    fun aThumbnailIsRecordedAsBeingMadeOnlyWhileTheMakerRuns() = runTest {
        val thumbnails = thumbnails()
        val seen = ArrayList<Set<ThumbnailKey>>()
        maker.script = { book ->
            seen += store.making.toSet()
            image(book)
        }
        val made = thumbnails.thumbnail(request(book("a")))
        assertEquals(listOf(setOf((made as ThumbResult.Ready).key)), seen)
        assertTrue(store.making.isEmpty())
    }

    @Test
    fun afterAProcessDiedMakingAThumbnailNoneIsMadeUntilResumedAndThatOneStaysFailed() = runTest {
        val dying = request(book("dying"))
        store.interrupted += ThumbnailKey(BookId("dying"), Instant.ofEpochMilli(1000), 5000, null, 300, ReadingDirection.RightToLeft)
        val thumbnails = thumbnails()
        val held = async { thumbnails.held.first() }
        runCurrent()
        assertTrue(held.await())
        assertEquals(ThumbResult.Failed, thumbnails.thumbnail(dying))
        assertEquals(ThumbResult.Unavailable, thumbnails.thumbnail(request(book("other"))))
        assertEquals(ThumbResult.Unavailable, thumbnails.thumbnail(request(folder(item("in folder")))))
        assertTrue("a thumbnail was made while held back", maker.calls.isEmpty())
        assertEquals("regenerated", shows(thumbnails.regenerate(request(book("regenerated")))))
        val resumed = async { thumbnails.resumptions.first() }
        runCurrent()
        thumbnails.resume()
        resumed.await()
        assertFalse(thumbnails.held.first())
        assertEquals("other", shows(thumbnails.thumbnail(request(book("other")))))
        assertEquals(ThumbResult.Failed, thumbnails.thumbnail(dying))
    }

    @Test
    fun aCoverOverSmbThatTakesLongerThanTheLimitGivesWayAndIsTriedAgainLater() = runTest {
        val thumbnails = thumbnails()
        maker.script = { book ->
            if (book.id.value == "huge") delay(Thumbnails.SMB_COVER_LIMIT_MILLIS * 2)
            image(book)
        }
        val huge = async { thumbnails.thumbnail(request(book("huge", lane = ThumbLane.Smb))) }
        val next = async { thumbnails.thumbnail(request(book("next", lane = ThumbLane.Smb))) }
        advanceTimeBy(Thumbnails.SMB_COVER_LIMIT_MILLIS - 1)
        assertFalse("gave up before the limit", huge.isCompleted)
        advanceTimeBy(2)
        assertEquals(ThumbResult.Unavailable, huge.await())
        assertTrue("a cover over the limit was recorded as failed", store.failed.isEmpty())
        runCurrent()
        assertEquals("next", shows(next.await()))
        clock += Thumbnails.UNAVAILABLE_BACKOFF_MILLIS
        maker.script = ::image
        assertEquals("huge", shows(thumbnails.thumbnail(request(book("huge", lane = ThumbLane.Smb)))))
    }

    @Test
    fun aLocalCoverHasNoTimeLimitOfItsOwn() = runTest {
        val thumbnails = thumbnails()
        maker.script = { book ->
            delay(Thumbnails.SMB_COVER_LIMIT_MILLIS * 2)
            image(book)
        }
        assertEquals("slow", shows(thumbnails.thumbnail(request(book("slow")))))
    }

    @Test
    fun aFailureThatMayPassIsNotRecordedAndIsTriedAgainAfterAWhile() = runTest {
        val thumbnails = thumbnails()
        maker.script = { Made.Unavailable }
        val first = async { thumbnails.thumbnail(request(book("a"))) }
        runCurrent()
        assertEquals(ThumbResult.Unavailable, first.await())
        assertTrue(store.failed.isEmpty())
        val soon = async { thumbnails.thumbnail(request(book("a"))) }
        runCurrent()
        assertEquals(ThumbResult.Unavailable, soon.await())
        assertEquals("tried again at once", 1, maker.calls.size)
        clock += Thumbnails.UNAVAILABLE_BACKOFF_MILLIS
        maker.script = { Made.Webp(byteArrayOf(1)) }
        val later = async { thumbnails.thumbnail(request(book("a"))) }
        runCurrent()
        assertTrue(later.await() is ThumbResult.Ready)
        assertEquals(2, maker.calls.size)
    }

    @Test
    fun aStoreThatThrowsIsAnUnavailableThumbnailNotACrash() = runTest {
        val broken = object : ThumbnailStore by store {
            override suspend fun put(key: ThumbnailKey, webp: ByteArray): File = throw java.io.IOException("disk full")
        }
        val dispatcher = StandardTestDispatcher(testScheduler)
        val thumbnails = Thumbnails(broken, maker, CoroutineScope(SupervisorJob() + dispatcher), dispatcher, now = { clock })
        val result = async { thumbnails.thumbnail(request(book("a"))) }
        runCurrent()
        assertEquals(ThumbResult.Unavailable, result.await())
        assertTrue(store.failed.isEmpty())
    }

    @Test
    fun regeneratingForgetsAFailureAndMakesTheThumbnailAgain() = runTest {
        val thumbnails = thumbnails()
        maker.script = { Made.Unreadable }
        launch { thumbnails.thumbnail(request(book("a"))) }
        runCurrent()
        maker.script = { Made.Webp(byteArrayOf(1)) }
        val again = async { thumbnails.regenerate(request(book("a"))) }
        runCurrent()
        assertTrue(again.await() is ThumbResult.Ready)
        assertTrue(store.failed.isEmpty())
    }

    @Test
    fun cachedNeverMakesAThumbnail() = runTest {
        val thumbnails = thumbnails()
        val miss = async { thumbnails.cached(request(book("a"))) }
        runCurrent()
        assertNull(miss.await())
        assertTrue(maker.calls.isEmpty())
    }

    @Test
    fun twoThumbnailsAreMadeAtOnceFromLocalFilesAndOneFromSmb() = runTest {
        val thumbnails = thumbnails()
        val release = CompletableDeferred<Unit>()
        maker.script = {
            release.await()
            Made.Webp(byteArrayOf(1))
        }
        repeat(4) { launch { thumbnails.thumbnail(request(book("local$it"))) } }
        runCurrent()
        assertEquals(2, maker.running)
        release.complete(Unit)
        runCurrent()
        assertEquals(2, maker.most)
        assertEquals(4, maker.calls.size)

        val smbRelease = CompletableDeferred<Unit>()
        maker.script = {
            smbRelease.await()
            Made.Webp(byteArrayOf(1))
        }
        maker.most = 0
        repeat(3) { launch { thumbnails.thumbnail(request(book("smb$it", lane = ThumbLane.Smb))) } }
        runCurrent()
        assertEquals(1, maker.running)
        smbRelease.complete(Unit)
        runCurrent()
        assertEquals(1, maker.most)
        assertEquals(7, maker.calls.size)
    }

    @Test
    fun theLanesDoNotWaitForEachOther() = runTest {
        val thumbnails = thumbnails()
        val release = CompletableDeferred<Unit>()
        maker.script = { release.await().let { Made.Webp(byteArrayOf(1)) } }
        launch { thumbnails.thumbnail(request(book("smb", lane = ThumbLane.Smb))) }
        launch { thumbnails.thumbnail(request(book("smb2", lane = ThumbLane.Smb))) }
        launch { thumbnails.thumbnail(request(book("local"))) }
        runCurrent()
        assertEquals(listOf("smb", "local"), maker.ids)
        release.complete(Unit)
    }

    @Test
    fun visibleItemsAreMadeBeforeNearbyOnes() = runTest {
        val thumbnails = thumbnails()
        val release = CompletableDeferred<Unit>()
        maker.script = { book ->
            if (book.id.value == "busy") release.await()
            Made.Webp(byteArrayOf(1))
        }
        launch { thumbnails.thumbnail(request(book("busy", lane = ThumbLane.Smb))) }
        runCurrent()
        for ((id, priority) in listOf("nearby" to ThumbPriority.Nearby, "visible" to ThumbPriority.Visible)) {
            launch { thumbnails.thumbnail(request(book(id, lane = ThumbLane.Smb), priority = priority)) }
            runCurrent()
        }
        release.complete(Unit)
        runCurrent()
        assertEquals(listOf("busy", "visible", "nearby"), maker.ids)
    }

    @Test
    fun aNearbyRequestThatBecomesVisibleMovesUp() = runTest {
        val thumbnails = thumbnails()
        val release = CompletableDeferred<Unit>()
        maker.script = { book ->
            if (book.id.value == "busy") release.await()
            Made.Webp(byteArrayOf(1))
        }
        launch { thumbnails.thumbnail(request(book("busy", lane = ThumbLane.Smb))) }
        runCurrent()
        launch { thumbnails.thumbnail(request(book("first", lane = ThumbLane.Smb), priority = ThumbPriority.Nearby)) }
        runCurrent()
        launch { thumbnails.thumbnail(request(book("second", lane = ThumbLane.Smb), priority = ThumbPriority.Nearby)) }
        runCurrent()
        launch { thumbnails.thumbnail(request(book("second", lane = ThumbLane.Smb), priority = ThumbPriority.Visible)) }
        runCurrent()
        release.complete(Unit)
        runCurrent()
        assertEquals(listOf("busy", "second", "first"), maker.ids)
    }

    @Test
    fun theVisibleItemNearestTheMiddleOfTheScreenIsMadeFirst() = runTest {
        val thumbnails = thumbnails()
        val release = CompletableDeferred<Unit>()
        maker.script = { book ->
            if (book.id.value == "busy") release.await()
            Made.Webp(byteArrayOf(1))
        }
        launch { thumbnails.thumbnail(request(book("busy", lane = ThumbLane.Smb))) }
        runCurrent()
        val distances = mapOf("edge" to 4, "middle" to 0, "folder" to 2)
        launch { thumbnails.thumbnail(request(book("edge", lane = ThumbLane.Smb)) { distances.getValue("edge") }) }
        launch { thumbnails.thumbnail(request(folder(item("in folder", lane = ThumbLane.Smb))) { distances.getValue("folder") }) }
        launch { thumbnails.thumbnail(request(book("middle", lane = ThumbLane.Smb)) { distances.getValue("middle") }) }
        runCurrent()
        release.complete(Unit)
        runCurrent()
        assertEquals(listOf("busy", "middle", "in folder", "edge"), maker.ids)
    }

    @Test
    fun aRowScrolledPastGoesBackToTheNearbyPriorityItIsStillAskedFor() = runTest {
        val thumbnails = thumbnails()
        val release = CompletableDeferred<Unit>()
        maker.script = { book ->
            if (book.id.value == "busy") release.await()
            Made.Webp(byteArrayOf(1))
        }
        launch { thumbnails.thumbnail(request(book("busy", lane = ThumbLane.Smb))) }
        runCurrent()
        launch { thumbnails.thumbnail(request(book("passed", lane = ThumbLane.Smb), priority = ThumbPriority.Nearby)) }
        val shown = launch { thumbnails.thumbnail(request(book("passed", lane = ThumbLane.Smb))) }
        runCurrent()
        shown.cancel()
        launch { thumbnails.thumbnail(request(book("now visible", lane = ThumbLane.Smb))) }
        runCurrent()
        release.complete(Unit)
        runCurrent()
        assertEquals(listOf("busy", "now visible", "passed"), maker.ids)
    }

    @Test
    fun aFolderScrolledPastLowersTheBookItsSearchWaitsFor() = runTest {
        val thumbnails = thumbnails()
        val release = CompletableDeferred<Unit>()
        maker.script = { book ->
            if (book.id.value == "busy") release.await()
            Made.Webp(byteArrayOf(1))
        }
        launch { thumbnails.thumbnail(request(book("busy", lane = ThumbLane.Smb))) }
        runCurrent()
        val subject = folder(item("child", lane = ThumbLane.Smb))
        launch { thumbnails.thumbnail(request(subject, priority = ThumbPriority.Nearby)) }
        val shown = launch { thumbnails.thumbnail(request(subject)) }
        runCurrent()
        shown.cancel()
        launch { thumbnails.thumbnail(request(book("now visible", lane = ThumbLane.Smb))) }
        runCurrent()
        release.complete(Unit)
        runCurrent()
        assertEquals(listOf("busy", "now visible", "child"), maker.ids)
    }

    @Test
    fun aNearbyFolderThatBecomesVisibleMovesUpTheBookItWaitsFor() = runTest {
        val thumbnails = thumbnails()
        val release = CompletableDeferred<Unit>()
        maker.script = { book ->
            if (book.id.value == "busy") release.await()
            Made.Webp(byteArrayOf(1))
        }
        launch { thumbnails.thumbnail(request(book("busy", lane = ThumbLane.Smb))) }
        runCurrent()
        launch { thumbnails.thumbnail(request(book("other", lane = ThumbLane.Smb), priority = ThumbPriority.Nearby)) }
        runCurrent()
        val subject = folder(item("child", lane = ThumbLane.Smb))
        launch { thumbnails.thumbnail(request(subject, priority = ThumbPriority.Nearby)) }
        runCurrent()
        launch { thumbnails.thumbnail(request(subject, priority = ThumbPriority.Visible)) }
        runCurrent()
        release.complete(Unit)
        runCurrent()
        assertEquals(listOf("busy", "child", "other"), maker.ids)
    }

    @Test
    fun theThumbnailOfAFolderIsTheFirstItemInNaturalOrder() = runTest {
        val thumbnails = thumbnails()
        val result = async { thumbnails.thumbnail(request(folder(item("10.zip"), item("2.zip"), item("1.zip")))) }
        runCurrent()
        assertEquals("1.zip", shows(result.await()))
        assertEquals(listOf("1.zip"), maker.ids)
    }

    @Test
    fun aFolderSkipsItemsThatCannotBeReadAndShowsTheFirstThatCan() = runTest {
        val thumbnails = thumbnails()
        maker.script = { if (it.id.value in setOf("1", "2")) Made.Unreadable else image(it) }
        val result = async { thumbnails.thumbnail(request(folder(item("1"), item("2"), item("3"), item("4")))) }
        runCurrent()
        assertEquals("3", shows(result.await()))
        assertEquals(listOf("1", "2", "3"), maker.ids)
        assertEquals(setOf(BookId("1"), BookId("2")), store.failed.map { it.book }.toSet())
    }

    @Test
    fun aFolderUsesTheImageTheUserChoseAndFallsBackWhenItFails() = runTest {
        val thumbnails = thumbnails()
        val chosen = book("chosen")
        val first = async { thumbnails.thumbnail(request(folder(item("1"), chosen = chosen))) }
        runCurrent()
        assertEquals("chosen", shows(first.await()))
        assertEquals(listOf("chosen"), maker.ids)

        maker.script = { if (it.id.value == "gone") Made.Unreadable else image(it) }
        val second = async { thumbnails.thumbnail(request(folder(item("1"), chosen = book("gone"), id = "other"))) }
        runCurrent()
        assertEquals("1", shows(second.await()))
    }

    @Test
    fun aFolderSearchesSubFoldersInPlaceInNaturalOrder() = runTest {
        val thumbnails = thumbnails()
        maker.script = { if (it.id.value == "a/1") Made.Unreadable else image(it) }
        val sub = FolderEntry.Directory("a") { listOf(item("1", "a/1"), item("2", "a/2")) }
        val result = async { thumbnails.thumbnail(request(folder(item("b"), sub))) }
        runCurrent()
        assertEquals("a/2", shows(result.await()))
        assertEquals(listOf("a/1", "a/2"), maker.ids)
    }

    @Test
    fun aFolderWithNothingReadableIsRecordedAndNotListedAgain() = runTest {
        val thumbnails = thumbnails()
        maker.script = { Made.Unreadable }
        var listed = 0
        val subject = folder(item("1"), item("2"), onList = { listed++ })
        val first = async { thumbnails.thumbnail(request(subject)) }
        runCurrent()
        assertEquals(ThumbResult.Failed, first.await())
        assertEquals(1, listed)
        val again = async { thumbnails.thumbnail(request(subject)) }
        val cached = async { thumbnails.cached(request(subject)) }
        runCurrent()
        assertEquals(ThumbResult.Failed, again.await())
        assertEquals(ThumbResult.Failed, cached.await())
        assertEquals(1, listed)
        assertEquals(2, maker.calls.size)
    }

    @Test
    fun anEmptyFolderHasNoThumbnail() = runTest {
        val thumbnails = thumbnails()
        val result = async { thumbnails.thumbnail(request(folder())) }
        runCurrent()
        assertEquals(ThumbResult.Failed, result.await())
    }

    @Test
    fun aFolderThatCannotBeListedIsNotRecorded() = runTest {
        val thumbnails = thumbnails()
        val subject = ThumbSubject.Folder(BookId("folder"), Instant.ofEpochMilli(500)) { throw java.io.IOException("gone") }
        val result = async { thumbnails.thumbnail(request(subject)) }
        runCurrent()
        assertEquals(ThumbResult.Unavailable, result.await())
        assertTrue(store.failed.isEmpty())
    }

    @Test
    fun aFolderWhoseItemsMayBeReadLaterIsNotRecorded() = runTest {
        val thumbnails = thumbnails()
        maker.script = { if (it.id.value == "1") Made.Unavailable else Made.Unreadable }
        val result = async { thumbnails.thumbnail(request(folder(item("1"), item("2")))) }
        runCurrent()
        assertEquals(ThumbResult.Unavailable, result.await())
        assertFalse(store.failed.any { it.book == BookId("folder") })
    }

    @Test
    fun theSearchForAFolderCoverStopsAfterTheMostItTries() = runTest {
        val thumbnails = thumbnails()
        maker.script = { Made.Unreadable }
        val items = List(Thumbnails.MAX_SEARCH_BOOKS + 10) { item("%03d".format(it)) }
        val result = async { thumbnails.thumbnail(request(folder(*items.toTypedArray()))) }
        runCurrent()
        assertEquals(ThumbResult.Failed, result.await())
        assertEquals(Thumbnails.MAX_SEARCH_BOOKS, maker.calls.size)
    }

    @Test
    fun theSearchForAFolderCoverStopsAtTheMostFoldersItLists() = runTest {
        val thumbnails = thumbnails()
        val empty = List(Thumbnails.MAX_SEARCH_LISTINGS) { FolderEntry.Directory("d%02d".format(it)) { emptyList() } }
        val result = async { thumbnails.thumbnail(request(folder(*empty.toTypedArray(), item("z")))) }
        runCurrent()
        assertEquals(ThumbResult.Failed, result.await())
        assertTrue("an item after the last folder it may list was tried", maker.calls.isEmpty())
    }

    @Test
    fun theSearchForAFolderCoverStopsAtTheDeepestFolderItEnters() = runTest {
        val thumbnails = thumbnails()
        val deepest = (0..Thumbnails.MAX_SEARCH_DEPTH).fold<Int, FolderEntry>(item("bottom")) { inner, depth ->
            FolderEntry.Directory("a$depth") { listOf(inner) }
        }
        val result = async { thumbnails.thumbnail(request(folder(deepest, item("z")))) }
        runCurrent()
        assertEquals(ThumbResult.Failed, result.await())
        assertTrue("an item after a folder too deep was tried: ${maker.ids}", maker.calls.isEmpty())
    }

    @Test
    fun cachedNeverListsAFolder() = runTest {
        val thumbnails = thumbnails()
        var listed = 0
        val subject = folder(item("1"), item("2"), onList = { listed++ })
        val miss = async { thumbnails.cached(request(subject)) }
        runCurrent()
        assertNull(miss.await())
        assertEquals("pass 1 listed the folder", 0, listed)
        launch { thumbnails.thumbnail(request(subject)) }
        runCurrent()
        assertEquals(1, listed)
        val hit = async { thumbnails.cached(request(subject)) }
        runCurrent()
        assertEquals("1", shows(hit.await()))
        assertEquals("pass 1 listed the folder", 1, listed)
        assertEquals(1, maker.calls.size)
    }

    @Test
    fun aFolderCoverIsFoundWithoutListingAfterARestart() = runTest {
        var listed = 0
        val subject = folder(item("1"), onList = { listed++ })
        launch { thumbnails().thumbnail(request(subject)) }
        runCurrent()
        val hit = async { thumbnails().cached(request(subject)) }
        runCurrent()
        assertEquals("1", shows(hit.await()))
        assertEquals(1, listed)
    }

    @Test
    fun aFolderCoverStaysWhenTheImageItCameFromIsEvicted() = runTest {
        val thumbnails = thumbnails()
        val subject = folder(item("1"))
        launch { thumbnails.thumbnail(request(subject)) }
        runCurrent()
        store.files.keys.removeAll { it.book == BookId("1") }
        val hit = async { thumbnails.cached(request(subject)) }
        runCurrent()
        assertEquals("1", shows(hit.await()))
    }

    @Test
    fun regeneratingAFolderTriesTheItemsThatFailedBefore() = runTest {
        val thumbnails = thumbnails()
        val subject = folder(item("1"), item("2"))
        maker.script = { Made.Unreadable }
        launch { thumbnails.thumbnail(request(subject)) }
        runCurrent()
        maker.script = ::image
        val result = async { thumbnails.regenerate(request(subject)) }
        runCurrent()
        assertEquals("1", shows(result.await()))
    }

    @Test
    fun regeneratingAFolderSearchesItAgain() = runTest {
        val thumbnails = thumbnails()
        var first = true
        val subject = ThumbSubject.Folder(BookId("folder"), Instant.ofEpochMilli(500)) {
            listOf(if (first) item("old") else item("new")).also { first = false }
        }
        launch { thumbnails.thumbnail(request(subject)) }
        runCurrent()
        val result = async { thumbnails.regenerate(request(subject)) }
        runCurrent()
        assertEquals("new", shows(result.await()))
        val hit = async { thumbnails.cached(request(subject)) }
        runCurrent()
        assertEquals("new", shows(hit.await()))
    }

    @Test
    fun regeneratingAFolderWhileAnOlderSearchRunsSearchesWithTheNewChoice() = runTest {
        val thumbnails = thumbnails()
        val oldSearch = CompletableDeferred<Unit>()
        maker.script = {
            if (it.id.value == "A") oldSearch.await()
            image(it)
        }
        val first = async { thumbnails.thumbnail(request(folder(item("1"), chosen = book("A")))) }
        runCurrent()
        val regenerated = async { thumbnails.regenerate(request(folder(item("1"), chosen = book("B")))) }
        runCurrent()
        oldSearch.complete(Unit)
        runCurrent()
        assertEquals("B", shows(regenerated.await()))
        assertEquals("B", shows(first.await()))
        val hit = async { thumbnails.cached(request(folder(item("1")))) }
        runCurrent()
        assertEquals("B", shows(hit.await()))
    }

    @Test
    fun anOlderSearchThatEndsFirstLeavesTheFolderToTheRegeneration() = runTest {
        val thumbnails = thumbnails()
        val oldSearch = CompletableDeferred<Unit>()
        val newSearch = CompletableDeferred<Unit>()
        maker.script = {
            when (it.id.value) {
                "A" -> oldSearch.await()
                "B" -> newSearch.await()
            }
            image(it)
        }
        val first = async { thumbnails.thumbnail(request(folder(item("1"), chosen = book("A")))) }
        runCurrent()
        val regenerated = async { thumbnails.regenerate(request(folder(item("1"), chosen = book("B")))) }
        runCurrent()
        oldSearch.complete(Unit)
        runCurrent()
        val between = async { thumbnails.cached(request(folder(item("1")))) }
        runCurrent()
        assertNull("the older search stored its cover", between.await())
        newSearch.complete(Unit)
        runCurrent()
        assertEquals("B", shows(regenerated.await()))
        assertEquals("B", shows(first.await()))
    }

    @Test
    fun aRegenerationThatEndsUnavailableFirstIsTheAnswerOfTheOlderSearchToo() = runTest {
        val thumbnails = thumbnails()
        val oldSearch = CompletableDeferred<Unit>()
        maker.script = {
            if (it.id.value == "A") {
                oldSearch.await()
                image(it)
            } else {
                Made.Unavailable
            }
        }
        val first = async { thumbnails.thumbnail(request(folder(item("1"), chosen = book("A")))) }
        runCurrent()
        val regenerated = async { thumbnails.regenerate(request(folder(item("1"), chosen = book("B")))) }
        runCurrent()
        assertEquals(ThumbResult.Unavailable, regenerated.await())
        oldSearch.complete(Unit)
        runCurrent()
        assertEquals(ThumbResult.Unavailable, first.await())
        val hit = async { thumbnails.cached(request(folder(item("1")))) }
        runCurrent()
        assertNull("the older search stored its cover", hit.await())
    }

    @Test
    fun aRegenerationGoesOnForTheOlderSearchWhenItsCallerLeaves() = runTest {
        val thumbnails = thumbnails()
        val oldSearch = CompletableDeferred<Unit>()
        val newSearch = CompletableDeferred<Unit>()
        maker.script = {
            when (it.id.value) {
                "A" -> oldSearch.await()
                "B" -> newSearch.await()
            }
            image(it)
        }
        val first = async { thumbnails.thumbnail(request(folder(item("1"), chosen = book("A")))) }
        runCurrent()
        val regenerated = launch { thumbnails.regenerate(request(folder(item("1"), chosen = book("B")))) }
        runCurrent()
        regenerated.cancel()
        runCurrent()
        oldSearch.complete(Unit)
        newSearch.complete(Unit)
        runCurrent()
        assertEquals("B", shows(first.await()))
        val hit = async { thumbnails.cached(request(folder(item("1")))) }
        runCurrent()
        assertEquals("B", shows(hit.await()))
    }

    @Test
    fun anOlderSearchThatEndsWhileTheRegenerationStartsGivesWayToIt() = runTest {
        val thumbnails = thumbnails()
        val oldSearch = CompletableDeferred<Unit>()
        val removing = CompletableDeferred<Unit>()
        store.beforeRemove = { if (it.book == BookId("folder")) removing.await() }
        maker.script = {
            if (it.id.value == "A") oldSearch.await()
            image(it)
        }
        val first = async { thumbnails.thumbnail(request(folder(item("1"), chosen = book("A")))) }
        runCurrent()
        val regenerated = async { thumbnails.regenerate(request(folder(item("1"), chosen = book("B")))) }
        runCurrent()
        oldSearch.complete(Unit)
        runCurrent()
        removing.complete(Unit)
        runCurrent()
        assertEquals("B", shows(regenerated.await()))
        assertEquals("B", shows(first.await()))
        val hit = async { thumbnails.cached(request(folder(item("1")))) }
        runCurrent()
        assertEquals("B", shows(hit.await()))
    }

    @Test
    fun eachRegenerationAnswersEveryoneWaitingForTheFolder() = runTest {
        val thumbnails = thumbnails()
        val gates = mapOf("A" to CompletableDeferred<Unit>(), "B" to CompletableDeferred(), "C" to CompletableDeferred())
        maker.script = {
            gates[it.id.value]?.await()
            image(it)
        }
        val first = async { thumbnails.thumbnail(request(folder(item("1"), chosen = book("A")))) }
        runCurrent()
        val second = async { thumbnails.regenerate(request(folder(item("1"), chosen = book("B")))) }
        runCurrent()
        val third = async { thumbnails.regenerate(request(folder(item("1"), chosen = book("C")))) }
        runCurrent()
        gates.values.forEach { it.complete(Unit) }
        runCurrent()
        assertEquals(listOf("C", "C", "C"), listOf(first, second, third).map { shows(it.await()) })
    }

    @Test
    fun closingEndsTheWaitForAFolder() = runTest {
        val thumbnails = thumbnails()
        maker.script = { awaitCancellation() }
        val waiting = async { runCatching { thumbnails.thumbnail(request(folder(item("1")))) } }
        runCurrent()
        thumbnails.close()
        runCurrent()
        assertTrue("the wait went on after closing", waiting.isCompleted)
        assertTrue(waiting.await().exceptionOrNull() is CancellationException)
    }

    @Test
    fun observeHoldsTheThumbnailBeforeLookingAndReleasesItWhenCancelled() = runTest {
        val thumbnails = thumbnails()
        val seen = ArrayList<ThumbResult>()
        val job = launch { thumbnails.observe(request(book("a"))).collect { seen += it } }
        runCurrent()
        assertEquals(1, seen.size)
        assertTrue(seen.single() is ThumbResult.Ready)
        assertEquals(1, store.held.size)
        assertTrue(store.released.isEmpty())
        job.cancel()
        runCurrent()
        assertEquals(store.held, store.released)
    }

    @Test
    fun aHoldWithoutLookingKeepsWhatObserveShowedThroughAnEviction() = runTest {
        val thumbnails = thumbnails()
        val subject = folder(item("1"))
        val shown = async { thumbnails.observe(request(subject)).first() }
        runCurrent()
        val key = (shown.await() as ThumbResult.Ready).key
        var looked = 0
        store.afterGet = { looked++ }

        val hold = thumbnails.hold(request(subject))
        assertEquals("holding looked in the store", 0, looked)
        store.evictUnheld()
        assertTrue("the held thumbnail was evicted", store.get(key) is StoredThumb.Hit)
        hold.close()
        store.evictUnheld()
        assertTrue(store.get(key) is StoredThumb.Miss)
    }

    @Test
    fun observeHoldsWhatAFolderShowsAndReleasesItWhenCancelled() = runTest {
        val thumbnails = thumbnails()
        val seen = ArrayList<ThumbResult>()
        val job = launch { thumbnails.observe(request(folder(item("1")))).collect { seen += it } }
        runCurrent()
        assertEquals("1", shows(seen.single()))
        store.evictUnheld()
        assertTrue(store.get((seen.single() as ThumbResult.Ready).key) is StoredThumb.Hit)
        job.cancel()
        runCurrent()
        assertTrue(store.holding.isEmpty())
    }

    @Test
    fun aFolderCoverSurvivesAnEvictionBetweenFindingTheImageInTheStoreAndCopyingIt() = runTest {
        val thumbnails = thumbnails()
        launch { thumbnails.thumbnail(request(book("a"))) }
        runCurrent()
        store.afterGet = { key -> if (key.book == BookId("a")) store.evictUnheld() }
        maker.script = { if (it.id.value == "a") Made.Unavailable else image(it) }
        val seen = ArrayList<ThumbResult>()
        val job = launch { thumbnails.observe(request(folder(item("a"), item("b")))).collect { seen += it } }
        runCurrent()
        val shown = seen.single() as ThumbResult.Ready
        assertEquals("a", shows(shown))
        store.evictUnheld()
        assertTrue("the shown thumbnail was evicted", store.get(shown.key) is StoredThumb.Hit)
        job.cancel()
    }

    @Test
    fun aFolderCoverSurvivesAnEvictionBetweenMakingTheImageAndCopyingIt() = runTest {
        val thumbnails = thumbnails()
        store.afterPut = { key -> if (key.book == BookId("a")) store.evictUnheld() }
        val result = async { thumbnails.thumbnail(request(folder(item("a"), item("b")))) }
        runCurrent()
        assertEquals("a", shows(result.await()))
    }

    @Test
    fun aStoreThatFailsWhileAFolderIsShownIsAnUnavailableThumbnailNotACrash() = runTest {
        val failing = object : ThumbnailStore by store {
            override suspend fun get(key: ThumbnailKey): StoredThumb =
                if (key in store.holding) throw IOException("disk") else store.get(key)
        }
        val dispatcher = StandardTestDispatcher(testScheduler)
        val thumbnails = Thumbnails(failing, maker, CoroutineScope(SupervisorJob() + dispatcher), dispatcher, now = { clock })
        val seen = ArrayList<ThumbResult>()
        val job = launch { thumbnails.observe(request(folder(item("1")))).collect { seen += it } }
        runCurrent()
        assertEquals(listOf<ThumbResult>(ThumbResult.Unavailable), seen)
        job.cancel()
    }

    @Test
    fun observeFillsFromTheStoreWithoutWaitingForAMakerTurn() = runTest {
        val thumbnails = thumbnails()
        launch { thumbnails.thumbnail(request(book("a"))) }
        runCurrent()
        val release = CompletableDeferred<Unit>()
        maker.script = {
            release.await()
            Made.Webp(byteArrayOf(1))
        }
        repeat(2) { launch { thumbnails.thumbnail(request(book("busy$it"))) } }
        runCurrent()
        val result = async { thumbnails.observe(request(book("a"))).first() }
        runCurrent()
        assertTrue(result.isCompleted)
        assertTrue(result.await() is ThumbResult.Ready)
        release.complete(Unit)
    }

    @Test
    fun observeShowsAFailureAndStaysHeld() = runTest {
        val thumbnails = thumbnails()
        maker.script = { Made.Unreadable }
        val seen = ArrayList<ThumbResult>()
        val job = launch { thumbnails.observe(request(book("a"))).collect { seen += it } }
        runCurrent()
        assertEquals(listOf<ThumbResult>(ThumbResult.Failed), seen)
        assertEquals(1, store.held.size)
        job.cancel()
    }

    @Test
    fun aPreviewOpensTheBookOnTheIoDispatcherNotOnTheThreadThatAsked() = runTest {
        val executor = Executors.newSingleThreadExecutor { Thread(it, "preview-io") }
        try {
            var ranOn: String? = null
            val previewer = PagePreviewer { _, _, _ ->
                ranOn = Thread.currentThread().name
                PagePreview.Failed
            }
            val dispatcher = StandardTestDispatcher(testScheduler)
            val thumbnails = Thumbnails(
                store, maker, CoroutineScope(SupervisorJob() + dispatcher), executor.asCoroutineDispatcher(), now = { clock }, previewer = previewer,
            )
            assertEquals(PagePreview.Failed, thumbnails.preview(book("a"), 3, 1000))
            assertEquals("preview-io", ranOn)
        } finally {
            executor.shutdown()
        }
    }
}
