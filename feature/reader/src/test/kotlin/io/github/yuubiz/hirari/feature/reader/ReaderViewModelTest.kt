package io.github.yuubiz.hirari.feature.reader

import android.app.Application
import android.net.Uri
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import io.github.yuubiz.hirari.core.data.ProgressWriter
import io.github.yuubiz.hirari.core.data.Storage
import io.github.yuubiz.hirari.core.image.BookFailure
import io.github.yuubiz.hirari.core.image.BookResult
import io.github.yuubiz.hirari.core.image.Books
import io.github.yuubiz.hirari.core.image.FolderFile
import io.github.yuubiz.hirari.core.image.ImageDecoding
import io.github.yuubiz.hirari.core.image.OpenBook
import io.github.yuubiz.hirari.core.model.BookAddress
import io.github.yuubiz.hirari.core.model.BookId
import io.github.yuubiz.hirari.core.model.BookSource
import io.github.yuubiz.hirari.core.model.Container
import io.github.yuubiz.hirari.core.model.FailureReason
import io.github.yuubiz.hirari.core.model.OpenBookRequest
import io.github.yuubiz.hirari.core.model.ReaderAction
import io.github.yuubiz.hirari.core.model.ReadState
import io.github.yuubiz.hirari.core.model.ReadingPosition
import io.github.yuubiz.hirari.core.model.UriString
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.io.IOException
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The reader's response to an archive listing that stopped (docs/spec.md sections 3.3 and 6.5). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@OptIn(ExperimentalCoroutinesApi::class)
class ReaderViewModelTest {
    private val id = BookId("listing-test")
    private val uri = UriString("content://books/book.zip")
    private val store = ViewModelStore()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val decoding = ImageDecoding(16L shl 20, 1)
    private val main = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private lateinit var services: ReaderServices

    @Before
    fun setUp() {
        Dispatchers.setMain(main)
        val application = ApplicationProvider.getApplicationContext<Application>()
        val storage = Storage(application)
        services = ReaderServices(storage, ProgressWriter(storage.progress, scope), decoding, scope)
    }

    @After
    fun tearDown() = runBlocking(main) {
        store.clear()
        scope.cancel()
        Dispatchers.resetMain()
        main.close()
    }

    /** The native listing is outside this JVM test; supply exactly the OpenBook it hands the reader. */
    private suspend fun archive(pages: Int, reason: FailureReason?): OpenBook {
        val files = List(pages) { index -> FolderFile("$index.jpg", 1) { throw IOException("fixture page data is not available") } }
        val book = (Books.openFolder(files, decoding) as BookResult.Opened).book
        ReflectionHelpers.setField(book, "container", Container.Zip)
        ReflectionHelpers.setField(book, "indexError", reason)
        return book
    }

    private fun reader(open: suspend () -> BookResult): ReaderViewModel {
        val ready = CompletableDeferred<ReaderServices>()
        val model = ReaderViewModel(
            ApplicationProvider.getApplicationContext(), ready,
            OpenBookRequest(BookAddress.Document(uri)), Microphone { emptyFlow() },
            locateBook = {
                Located(id, BookSource.External(uri), Uri.parse(uri.value), folder = false,
                    result = open(), overrides = services.overrides.open(id))
            },
        )
        store.put("reader", model)
        // Keep background work bounded; shape and view events are driven below.
        model.onStop()
        ready.complete(services)
        return model
    }

    private suspend fun ReaderViewModel.awaitOpen() = withTimeout(10_000) {
        while (phase == ReaderPhase.Opening) delay(10)
    }

    @Test
    fun aLostListingFailsWithoutSavingProgressAndRetryListsAFreshBook() = runBlocking(main) {
        var opens = 0
        val incomplete = archive(1, FailureReason.ConnectionLost)
        val complete = archive(3, null)
        val model = reader { BookResult.Opened(if (++opens == 1) incomplete else complete) }
        model.awaitOpen()

        val failure = assertIs<ReaderPhase.Failed>(model.phase)
        assertEquals(BookFailure.ReadError, failure.reason)
        assertTrue(failure.retryable)
        assertFalse(ReflectionHelpers.getField<CoroutineScope>(incomplete, "scope").isActive)
        assertNull(model.state)
        assertTrue(model.pageNames.isEmpty())
        assertNull(services.storage.progress.get(id))

        model.retryOpen()
        model.retryOpen() // A second click while opening must not start a second listing.
        model.awaitOpen()
        assertEquals(2, opens)
        assertEquals(ReaderPhase.Reading, model.phase)
        assertEquals(listOf("0.jpg", "1.jpg", "2.jpg"), model.pageNames)
        assertTrue(ReflectionHelpers.getField<CoroutineScope>(complete, "scope").isActive)
    }

    @Test
    fun aLostListingWithoutPagesAlsoOffersRetry() = runBlocking(main) {
        val model = reader { BookResult.Opened(archive(0, FailureReason.ConnectionLost)) }
        model.awaitOpen()
        assertEquals(ReaderPhase.Failed(BookFailure.ReadError, retryable = true), model.phase)
    }

    @Test
    fun aCorruptTailOpensItsListedPagesButDoesNotBecomeReadAtTheirEnd() = runBlocking(main) {
        val model = reader { BookResult.Opened(archive(2, FailureReason.Corrupt)) }
        model.awaitOpen()
        assertEquals(ReaderPhase.Reading, model.phase)
        assertEquals(listOf("0.jpg", "1.jpg"), model.pageNames)
        model.onViewport(PORTRAIT)
        repeat(2) { model.dispatch(ReaderEvent.ShapeLearned(it, TALL)) }
        model.act(ReaderAction.Last, 0)
        assertTrue(checkNotNull(model.state).showsLastView)
        services.progress.flush().join()
        val progress = services.storage.progress.get(id)
        assertEquals(ReadingPosition.Image(1, 2), progress?.position)
        assertEquals(ReadState.Reading, progress?.state)
    }

    @Test
    fun aCompleteListingStillBecomesReadAtItsEnd() = runBlocking(main) {
        val model = reader { BookResult.Opened(archive(2, null)) }
        model.awaitOpen()
        model.onViewport(PORTRAIT)
        repeat(2) { model.dispatch(ReaderEvent.ShapeLearned(it, TALL)) }
        model.act(ReaderAction.Last, 0)
        services.progress.flush().join()
        assertEquals(ReadState.Read, services.storage.progress.get(id)?.state)
    }
}
