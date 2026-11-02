package io.github.yuubiz.hirari.core.image.thumbnail

import io.github.yuubiz.hirari.core.image.BookFailure
import io.github.yuubiz.hirari.core.image.BookResult
import io.github.yuubiz.hirari.core.model.BookId
import io.github.yuubiz.hirari.core.model.ReadingDirection
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
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

private class MemoryStore(private val dir: File) : ThumbnailStore {
    val files = HashMap<ThumbnailKey, File>()
    val failed = LinkedHashSet<ThumbnailKey>()
    val held = ArrayList<ThumbnailKey>()
    val released = ArrayList<ThumbnailKey>()
    var puts = 0
    var onHold: (ThumbnailKey) -> Unit = {}

    val holding: List<ThumbnailKey> get() = held.toMutableList().apply { released.forEach { remove(it) } }

    /** What the size limit of the cache does at its worst: every thumbnail nobody holds goes. */
    fun evictUnheld() {
        files.keys.retainAll(holding.toSet())
    }

    override suspend fun get(key: ThumbnailKey): StoredThumb = when {
        key in failed -> StoredThumb.Failed
        else -> files[key]?.let(StoredThumb::Hit) ?: StoredThumb.Miss
    }

    override suspend fun put(key: ThumbnailKey, webp: ByteArray): File =
        File(dir, "${puts++}.webp").also { it.writeBytes(webp) }.also { files[key] = it }

    override suspend fun markFailed(key: ThumbnailKey) {
        failed += key
    }

    override suspend fun remove(key: ThumbnailKey) {
        files.remove(key)
        failed.remove(key)
    }

    override fun hold(key: ThumbnailKey): Closeable {
        held += key
        onHold(key)
        return Closeable { released += key }
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
    ) = ThumbSubject.Book(
        BookId(id),
        Instant.ofEpochMilli(modified),
        byteSize = 5000,
        coverEntry = coverEntry,
        lane = lane,
        open = { BookResult.Failed(BookFailure.ReadError) },
    )

    private fun request(
        subject: ThumbSubject,
        sizePx: Int = 300,
        direction: ReadingDirection = ReadingDirection.RightToLeft,
        priority: ThumbPriority = ThumbPriority.Visible,
    ) = ThumbRequest(subject, sizePx, direction, priority)

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
    fun aFolderCoverThatObserveShowsSurvivesEvictionOfItsImageAndOfAllElse() = runTest {
        val thumbnails = thumbnails()
        launch { thumbnails.thumbnail(request(book("a"))) }
        runCurrent()
        store.onHold = { key -> if (key.book == BookId("a")) store.files.remove(key) }
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
}
